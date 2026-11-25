package com.newtube.mobile.update;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.Formatter;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.AppIcon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * GUTUBE: In-app self-updater system backed by GitHub Releases API.
 * Fetches latest release from https://api.github.com/repos/tylonmademe-sudo/GuTube/releases/latest,
 * compares semantic versioning against BuildConfig.VERSION_NAME, presents a frosted dark
 * BottomSheetDialog with changelog bullet points, downloads into internal cacheDir/updates/,
 * and automatically triggers the Android Package Installer.
 */
public final class GitHubAppUpdater {
    private static final String TAG = "GitHubUpdater";
    private static final String RELEASES_API_URL = "https://api.github.com/repos/tylonmademe-sudo/GuTube/releases/latest";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /** Tag name dismissed by user in current app session to avoid repeated prompts. */
    @Nullable private static String sDismissedVersionTag;
    /** APK pending install if user was routed to system "Install unknown apps" settings screen. */
    @Nullable private static File sPendingInstallApk;

    private GitHubAppUpdater() {
    }

    public static class ReleaseInfo {
        public final String tagName;
        public final String cleanVersion;
        public final String downloadUrl;
        public final long apkSize;
        public final List<String> changelog;
        public final String rawBody;

        public ReleaseInfo(String tagName, String cleanVersion, String downloadUrl, long apkSize, List<String> changelog, String rawBody) {
            this.tagName = tagName;
            this.cleanVersion = cleanVersion;
            this.downloadUrl = downloadUrl;
            this.apkSize = apkSize;
            this.changelog = changelog;
            this.rawBody = rawBody != null ? rawBody : "";
        }

        public ReleaseInfo(String tagName, String cleanVersion, String downloadUrl, long apkSize, List<String> changelog) {
            this(tagName, cleanVersion, downloadUrl, apkSize, changelog, "");
        }
    }

    /**
     * Deletes leftover APKs and partial temp files from internal cacheDir/updates/ on startup.
     */
    public static void cleanupOldUpdates(Context context) {
        if (context == null) return;
        EXECUTOR.execute(() -> {
            try {
                File dir = new File(context.getCacheDir(), "updates");
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().endsWith(".apk") || f.getName().endsWith(".tmp")) {
                                boolean deleted = f.delete();
                                Log.d(TAG, "Cleaned old update file: " + f.getName() + " (deleted=" + deleted + ")");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning old updates: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Resumes install if the user just returned from Android's "Install unknown apps" settings.
     */
    public static void resumePendingInstall(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (sPendingInstallApk != null && sPendingInstallApk.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                File apk = sPendingInstallApk;
                sPendingInstallApk = null;
                installApk(activity, apk);
            }
        }
    }

    /**
     * Synchronously fetches the latest release from GitHub API.
     * Safe to call from background worker threads.
     * @return ReleaseInfo if available, null on error or no release.
     */
    @Nullable
    public static ReleaseInfo fetchLatestReleaseSync() {
        try {
            Request request = new Request.Builder()
                    .url(RELEASES_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "GuTube-Android-App")
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "fetchLatestReleaseSync: HTTP " + response.code() + ": " + response.message());
                    return null;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    Log.e(TAG, "fetchLatestReleaseSync: empty body");
                    return null;
                }

                String jsonStr = body.string();
                JSONObject json = new JSONObject(jsonStr);

                String tagName = json.optString("tag_name", "");
                if (tagName.isEmpty()) {
                    return null;
                }

                String cleanRelease = cleanVersionName(tagName);

                JSONArray assets = json.optJSONArray("assets");
                String apkUrl = null;
                String apkName = null;
                long apkSize = 0;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        String downloadUrl = asset.optString("browser_download_url", "");
                        long size = asset.optLong("size", 0);

                        if (name.endsWith(".apk") && !downloadUrl.isEmpty()) {
                            if (name.contains("arm64") || name.contains("v8a")) {
                                apkUrl = downloadUrl;
                                apkSize = size;
                                break;
                            }
                            if (apkUrl == null || name.contains("universal")) {
                                apkUrl = downloadUrl;
                                apkName = name;
                                apkSize = size;
                            }
                        }
                    }
                }

                String rawBody = json.optString("body", "");
                List<String> changelog = parseChangelog(rawBody);
                return new ReleaseInfo(tagName, cleanRelease, apkUrl, apkSize, changelog, rawBody);
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchLatestReleaseSync failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Startup check: queries GitHub Releases API silently in background.
     */
    public static void checkUpdateOnStartup(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkUpdate(activity, false);
    }

    /**
     * Check for updates: queries GitHub Releases API.
     * @param manualCheck If true, shows feedback toast when already up-to-date or on error.
     */
    public static void checkUpdate(Activity activity, boolean manualCheck) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        final String currentVersion = getCurrentVersion(activity);
        final String cleanCurrent = cleanVersionName(currentVersion);

        Log.d(TAG, "Initiating update check (manual=" + manualCheck + "): API URL=" + RELEASES_API_URL);
        Log.d(TAG, "Local app version: raw='" + currentVersion + "', numeric='" + cleanCurrent + "'");

        EXECUTOR.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(RELEASES_API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "GuTube-Android-App")
                        .build();

                Log.d(TAG, "Fetching latest release from GitHub API...");
                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "GitHub releases API failed with HTTP " + response.code() + ": " + response.message());
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        Log.e(TAG, "GitHub releases API returned empty body");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    String jsonStr = body.string();
                    JSONObject json = new JSONObject(jsonStr);

                    String tagName = json.optString("tag_name", "");
                    if (tagName.isEmpty()) {
                        Log.e(TAG, "GitHub release JSON missing 'tag_name' field");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    String cleanRelease = cleanVersionName(tagName);
                    Log.d(TAG, "Fetched release tag: raw='" + tagName + "', numeric='" + cleanRelease + "'");

                    // Compare version
                    boolean isNewer = isNewerVersion(tagName, currentVersion);
                    Log.d(TAG, "Version comparison: local='" + cleanCurrent + "' vs remote='" + cleanRelease + "' -> isNewer=" + isNewer);

                    if (!isNewer) {
                        Log.d(TAG, "GuTube is up to date (current: " + cleanCurrent + " >= remote: " + cleanRelease + ")");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date (" + cleanCurrent + ")"));
                        }
                        return;
                    }

                    Log.d(TAG, "Newer version available! Remote: " + cleanRelease + " > Local: " + cleanCurrent);

                    // Check if already dismissed this session (only for auto-check)
                    if (!manualCheck && tagName.equals(sDismissedVersionTag)) {
                        Log.d(TAG, "Update " + tagName + " was dismissed by user in this session");
                        return;
                    }

                    // Extract APK download URL & size from assets array
                    JSONArray assets = json.optJSONArray("assets");
                    String apkUrl = null;
                    String apkName = null;
                    long apkSize = 0;
                    if (assets != null) {
                        Log.d(TAG, "Release contains " + assets.length() + " asset(s), searching for APK...");
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) continue;
                            String name = asset.optString("name", "");
                            String downloadUrl = asset.optString("browser_download_url", "");
                            long size = asset.optLong("size", 0);

                            if (name.endsWith(".apk") && !downloadUrl.isEmpty()) {
                                // Prefer arm64 build for modern phones
                                if (name.contains("arm64") || name.contains("v8a")) {
                                    apkUrl = downloadUrl;
                                    apkName = name;
                                    apkSize = size;
                                    break;
                                }
                                if (apkUrl == null || name.contains("universal")) {
                                    apkUrl = downloadUrl;
                                    apkName = name;
                                    apkSize = size;
                                }
                            }
                        }
                    }

                    if (apkUrl == null) {
                        Log.e(TAG, "Release " + tagName + " found, but no matching .apk asset in release assets!");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "New release " + tagName + " found, but no APK is available yet."));
                        }
                        return;
                    }

                    Log.d(TAG, "Resolved APK download: name='" + apkName + "', url=" + apkUrl + ", size=" + apkSize + " bytes");

                    // Parse changelog bullet points
                    String rawBody = json.optString("body", "");
                    List<String> changelog = parseChangelog(rawBody);

                    ReleaseInfo release = new ReleaseInfo(tagName, cleanRelease, apkUrl, apkSize, changelog, rawBody);

                    Log.d(TAG, "Showing update bottom sheet dialog for version " + cleanRelease);
                    MAIN_HANDLER.post(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            showUpdateDialog(activity, release);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "GitHub update check failed with exception: " + e.getMessage(), e);
                if (manualCheck) {
                    MAIN_HANDLER.post(() -> showToast(activity, "Could not check for updates. Check connection."));
                }
            }
        });
    }

    /**
     * Shows a dark frosted bottom sheet modal dialog with release details and live download progress.
     */
    @SuppressLint("SetTextI18n")
    private static void showUpdateDialog(Activity activity, ReleaseInfo release) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.ThemeOverlay_GuTube_BottomSheetDialog_LiquidGlass);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_github_update, null);
        dialog.setContentView(view);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        // Find views
        View dragHandle = view.findViewById(R.id.github_update_drag_handle);
        View iconContainer = view.findViewById(R.id.github_update_icon_container);
        ImageView iconView = view.findViewById(R.id.github_update_icon);
        View iconTarget = iconContainer != null ? iconContainer : iconView;
        TextView titleView = view.findViewById(R.id.github_update_title);
        TextView versionView = view.findViewById(R.id.github_update_version);
        TextView whatsNewHeader = view.findViewById(R.id.github_update_whats_new_header);
        View subheading = view.findViewById(R.id.github_update_subheading);
        View progressContainer = view.findViewById(R.id.github_update_progress_container);
        LinearProgressIndicator progressBar = view.findViewById(R.id.github_update_progress_bar);
        TextView progressText = view.findViewById(R.id.github_update_progress_text);
        LinearLayout notesContainer = view.findViewById(R.id.github_update_notes_container);
        LinearLayout notesList = view.findViewById(R.id.github_update_notes_list);
        MaterialButton btnCancel = view.findViewById(R.id.github_update_btn_cancel);
        MaterialButton btnAction = view.findViewById(R.id.github_update_btn_action);
        com.newtube.mobile.ui.update.MaxHeightScrollView scroll = view.findViewById(R.id.github_update_scroll);

        // Dedicated Liquid Glass Background Layer (AGSL distortion / blur)
        View glassBg = view.findViewById(R.id.github_update_glass_bg);
        com.newtube.mobile.ui.common.LiquidGlass.applyLiquidEffect(glassBg);

        float density = activity.getResources().getDisplayMetrics().density;

        if (scroll != null) {
            scroll.setMaxHeight((int) (activity.getResources().getDisplayMetrics().heightPixels * 0.35f));
        }

        AppIcon.show(iconView, true);

        titleView.setText("Update Available");
        String sizeStr = release.apkSize > 0 ? Formatter.formatFileSize(activity, release.apkSize) : "";
        versionView.setText("GuTube " + release.cleanVersion + (sizeStr.isEmpty() ? "" : " · " + sizeStr));
        if (whatsNewHeader != null) {
            whatsNewHeader.setText("What's new in " + release.cleanVersion);
        }
        btnAction.setVisibility(View.VISIBLE);
        btnAction.setEnabled(true);
        btnAction.setText("Update now");
        btnCancel.setText("Not now");

        // Attach tactile feedback micro-interactions
        attachTactileFeedback(btnAction);
        attachTactileFeedback(btnCancel);

        // Populate changelog bullet points with clean Markdown-to-Spannable parser
        renderMarkdownNotes(activity, notesList, release.rawBody, release.changelog);

        dialog.setOnShowListener(d -> {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.newtube.mobile.ui.common.LiquidGlass.configureBottomSheetContainer(bottomSheet);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setHideable(true);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View sheet, int newState) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            dialog.cancel();
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View sheet, float slideOffset) {
                        if (dragHandle != null) {
                            float scale = 1.0f + Math.max(0f, -slideOffset) * 0.25f;
                            dragHandle.setScaleX(Math.min(1.25f, scale));
                        }
                    }
                });
            }

            // Google Stitch entrance animations & micro-interactions
            view.setTranslationY(activity.getResources().getDisplayMetrics().heightPixels * 0.35f);
            view.setAlpha(0f);
            view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(360)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();

            if (iconTarget != null) {
                iconTarget.setScaleX(0.65f);
                iconTarget.setScaleY(0.65f);
                iconTarget.setRotation(-6f);
                iconTarget.setAlpha(0f);
                iconTarget.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotation(0f)
                        .alpha(1f)
                        .setStartDelay(100)
                        .setDuration(420)
                        .setInterpolator(new OvershootInterpolator(2.0f))
                        .start();
            }

            titleView.setAlpha(0f);
            titleView.setTranslationY(14 * density);
            titleView.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(340).setInterpolator(new DecelerateInterpolator(1.5f)).start();

            versionView.setAlpha(0f);
            versionView.setTranslationY(14 * density);
            versionView.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(340).setInterpolator(new DecelerateInterpolator(1.5f)).start();

            if (whatsNewHeader != null) {
                whatsNewHeader.setAlpha(0f);
                whatsNewHeader.setTranslationY(14 * density);
                whatsNewHeader.animate().alpha(1f).translationY(0f).setStartDelay(160).setDuration(340).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }

            if (subheading != null) {
                subheading.setAlpha(0f);
                subheading.setTranslationY(14 * density);
                subheading.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(340).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }

            int count = notesList.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = notesList.getChildAt(i);
                if (child != null) {
                    child.setAlpha(0f);
                    child.setTranslationY(12 * density);
                    child.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(200 + i * 40)
                            .setDuration(340)
                            .setInterpolator(new DecelerateInterpolator(1.5f))
                            .start();
                }
            }
        });

        // Real backdrop blur (blur(8px - 12dp)) and true transparent window styling
        com.newtube.mobile.ui.common.LiquidGlass.configureWindow(dialog, activity);

        final Call[] activeCall = new Call[1];
        final boolean[] isDownloading = new boolean[]{false};
        final boolean[] isCancelled = new boolean[]{false};

        btnCancel.setOnClickListener(v -> {
            sDismissedVersionTag = release.tagName;
            if (isDownloading[0]) {
                isCancelled[0] = true;
                if (activeCall[0] != null) {
                    activeCall[0].cancel();
                }
                EXECUTOR.execute(() -> deleteTempUpdateFile(activity));
            }
            float slideDistance = view.getHeight() > 0 ? view.getHeight() : 350 * density;
            view.animate()
                    .translationY(slideDistance)
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(dialog::dismiss)
                    .start();
        });

        dialog.setOnCancelListener(dialogInterface -> {
            sDismissedVersionTag = release.tagName;
            if (isDownloading[0]) {
                isCancelled[0] = true;
                if (activeCall[0] != null) {
                    activeCall[0].cancel();
                }
                EXECUTOR.execute(() -> deleteTempUpdateFile(activity));
            }
        });

        btnAction.setOnClickListener(v -> {
            if (isDownloading[0]) return;
            isDownloading[0] = true;

            // Switch to downloading state with smooth animations
            btnAction.animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> btnAction.setVisibility(View.GONE))
                    .start();

            titleView.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        titleView.setText("Downloading update");
                        titleView.animate().alpha(1f).setDuration(160).start();
                    })
                    .start();

            progressContainer.setVisibility(View.VISIBLE);
            progressContainer.setAlpha(0f);
            progressContainer.setTranslationY(-8 * density);
            progressContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();

            progressBar.setIndeterminate(true);
            progressText.setText("Connecting...");
            btnCancel.setText("Cancel");

            EXECUTOR.execute(() -> {
                File updatesDir = new File(activity.getCacheDir(), "updates");
                updatesDir.mkdirs();
                File tempFile = new File(updatesDir, "update.apk.tmp");
                File finalFile = new File(updatesDir, "update.apk");

                if (tempFile.exists()) tempFile.delete();

                try {
                    Log.d(TAG, "User clicked update. Starting download from: " + release.downloadUrl);
                    Request req = new Request.Builder()
                            .url(release.downloadUrl)
                            .header("User-Agent", "GuTube-Android-App")
                            .build();

                    activeCall[0] = HTTP_CLIENT.newCall(req);
                    Response resp = activeCall[0].execute();

                    if (!resp.isSuccessful()) {
                        throw new IOException("HTTP error " + resp.code());
                    }

                    ResponseBody respBody = resp.body();
                    if (respBody == null) throw new IOException("Empty response body");

                    long totalLength = respBody.contentLength();
                    if (totalLength <= 0 && release.apkSize > 0) {
                        totalLength = release.apkSize;
                    }

                    final long finalTotal = totalLength;

                    try (InputStream in = respBody.byteStream();
                         FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        long downloaded = 0;
                        int read;
                        long lastUpdateMs = 0;

                        MAIN_HANDLER.post(() -> {
                            if (finalTotal > 0) {
                                progressBar.setIndeterminate(false);
                                progressBar.setMax(100);
                                progressBar.setProgress(0);
                            }
                        });

                        while ((read = in.read(buffer)) != -1) {
                            if (isCancelled[0]) {
                                tempFile.delete();
                                return;
                            }
                            out.write(buffer, 0, read);
                            downloaded += read;

                            long now = SystemClock.uptimeMillis();
                            if (now - lastUpdateMs >= 100 || (finalTotal > 0 && downloaded == finalTotal)) {
                                lastUpdateMs = now;
                                final long currentDownloaded = downloaded;
                                MAIN_HANDLER.post(() -> {
                                    if (dialog.isShowing()) {
                                        if (finalTotal > 0) {
                                            int percent = (int) Math.min(100, currentDownloaded * 100 / finalTotal);
                                            progressBar.setProgress(percent);
                                            progressText.setText(Formatter.formatFileSize(activity, currentDownloaded)
                                                    + " of " + Formatter.formatFileSize(activity, finalTotal));
                                        } else {
                                            progressText.setText(Formatter.formatFileSize(activity, currentDownloaded));
                                        }
                                    }
                                });
                            }
                        }
                        out.flush();
                    }

                    if (isCancelled[0]) {
                        tempFile.delete();
                        return;
                    }

                    if (finalFile.exists()) finalFile.delete();
                    if (!tempFile.renameTo(finalFile)) {
                        // Fallback rename/copy
                        copyFile(tempFile, finalFile);
                        tempFile.delete();
                    }

                    Log.d(TAG, "APK download completed: " + finalFile.getAbsolutePath() + " (size: " + finalFile.length() + " bytes)");

                    MAIN_HANDLER.post(() -> {
                        dialog.dismiss();
                        installApk(activity, finalFile);
                    });

                } catch (Exception e) {
                    if (!isCancelled[0]) {
                        Log.e(TAG, "Download error: " + e.getMessage(), e);
                        tempFile.delete();
                        MAIN_HANDLER.post(() -> {
                            if (dialog.isShowing()) {
                                titleView.animate()
                                        .alpha(0f)
                                        .setDuration(120)
                                        .withEndAction(() -> {
                                            titleView.setText("Update Available");
                                            titleView.animate().alpha(1f).setDuration(160).start();
                                        })
                                        .start();

                                progressContainer.animate()
                                        .alpha(0f)
                                        .setDuration(180)
                                        .withEndAction(() -> progressContainer.setVisibility(View.GONE))
                                        .start();

                                btnAction.setText("Retry");
                                btnAction.setVisibility(View.VISIBLE);
                                btnAction.setEnabled(true);
                                btnAction.setScaleX(0.85f);
                                btnAction.setScaleY(0.85f);
                                btnAction.setAlpha(0f);
                                btnAction.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .alpha(1f)
                                        .setDuration(200)
                                        .setInterpolator(new OvershootInterpolator(1.5f))
                                        .start();

                                btnCancel.setText("Not now");
                                isDownloading[0] = false;
                                showToast(activity, "Download failed. Please try again.");
                            }
                        });
                    }
                }
            });
        });

        dialog.setOnDismissListener(dialogInterface -> {
            view.animate().cancel();
            if (iconTarget != null) iconTarget.animate().cancel();
            titleView.animate().cancel();
            versionView.animate().cancel();
            if (whatsNewHeader != null) whatsNewHeader.animate().cancel();
            if (subheading != null) subheading.animate().cancel();
            int c = notesList.getChildCount();
            for (int i = 0; i < c; i++) {
                View child = notesList.getChildAt(i);
                if (child != null) child.animate().cancel();
            }
            btnAction.animate().cancel();
            btnCancel.animate().cancel();
            progressContainer.animate().cancel();
        });

        dialog.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void attachTactileFeedback(View button) {
        if (button == null) return;
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).setInterpolator(new OvershootInterpolator(1.5f)).start();
                    break;
            }
            return false;
        });
    }

    /**
     * Parses inline markdown tokens:
     * - **bold** -> Bold white text (#FFFFFF) with asterisks removed
     * - `code/tag` -> Monospace pill style (#E2E8F0 on 15% white background) with backticks removed
     * - [link](url) -> Link text in accent color (#818CF8)
     * - Cleans trailing GitHub commit/PR references ("in https://github.com/...")
     * - Safely strips any leftover unmatched raw asterisks or backticks
     */
    private static SpannableStringBuilder parseInlineMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return new SpannableStringBuilder();
        }
        // Strip trailing commit/PR URLs e.g. " in https://github.com/..."
        text = text.replaceAll("\\s+in https?://github\\.com/\\S+", "");

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        Pattern pattern = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[(.+?)\\]\\((.+?)\\)");
        Matcher matcher = pattern.matcher(text);
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                ssb.append(text.substring(lastIndex, matcher.start()));
            }

            if (matcher.group(1) != null) {
                // **bold text**
                String boldContent = matcher.group(1);
                int start = ssb.length();
                ssb.append(boldContent);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(2) != null) {
                // `code/tag`
                String codeContent = matcher.group(2);
                int start = ssb.length();
                ssb.append(" ").append(codeContent).append(" ");
                ssb.setSpan(new TypefaceSpan("monospace"), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new BackgroundColorSpan(0x26FFFFFF), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(0xFFE2E8F0), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(3) != null) {
                // [link](url)
                String linkText = matcher.group(3);
                int start = ssb.length();
                ssb.append(linkText);
                ssb.setSpan(new ForegroundColorSpan(0xFF818CF8), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            lastIndex = matcher.end();
        }

        if (lastIndex < text.length()) {
            ssb.append(text.substring(lastIndex));
        }

        // Clean any leftover raw markers if unmatched (e.g. lone ** or `)
        int idx;
        while ((idx = ssb.toString().indexOf("**")) != -1) {
            ssb.delete(idx, idx + 2);
        }
        while ((idx = ssb.toString().indexOf("`")) != -1) {
            ssb.delete(idx, idx + 1);
        }

        return ssb;
    }

    /**
     * Renders release notes line-by-line:
     * - Dividers (---, ***, • ---) -> Native 1dp horizontal divider View (no raw --- text)
     * - Headings (###, ##) -> Bold white section headers with proper top spacing
     * - Bullets (* , - , • ) -> Indented bullet points with colored bullet prefix & 4dp line spacing
     */
    private static void renderMarkdownNotes(Activity activity, LinearLayout container, String rawBody, List<String> fallbackChangelog) {
        container.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;

        if (rawBody == null || rawBody.trim().isEmpty()) {
            if (fallbackChangelog != null && !fallbackChangelog.isEmpty()) {
                for (String note : fallbackChangelog) {
                    container.addView(createBulletView(activity, parseInlineMarkdown(note), density));
                }
            } else {
                container.addView(createBulletView(activity, new SpannableStringBuilder("Bug fixes and performance improvements."), density));
            }
            return;
        }

        String[] lines = rawBody.split("\r?\n");
        boolean hasAddedAnyView = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 1. Horizontal Dividers: ---, ***, ___ or • ---
            if (line.matches("^[\\s•*-]*---[\\s•*-]*$") || line.matches("^[\\s•*-]*\\*\\*\\*[\\s•*-]*$") || line.matches("^_{3,}$")) {
                if (hasAddedAnyView) {
                    View divider = new View(activity);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, 1 * density));
                    lp.topMargin = (int) (10 * density);
                    lp.bottomMargin = (int) (10 * density);
                    divider.setLayoutParams(lp);
                    divider.setBackgroundColor(0x26FFFFFF);
                    container.addView(divider);
                }
                continue;
            }

            // 2. Headings: #, ##, ###, ####, etc.
            if (line.startsWith("#")) {
                String headingText = line.replaceFirst("^#+\\s*", "").trim();
                if (headingText.isEmpty() || headingText.equalsIgnoreCase("what's changed")
                        || headingText.equalsIgnoreCase("changelog")) {
                    continue;
                }
                TextView tv = new TextView(activity);
                tv.setText(parseInlineMarkdown(headingText));
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(14.5f);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (12 * density);
                lp.bottomMargin = (int) (4 * density);
                tv.setLayoutParams(lp);
                tv.setShadowLayer(3f, 0f, 1f, 0x33000000);
                container.addView(tv);
                hasAddedAnyView = true;
                continue;
            }

            // Skip "Full Changelog" line
            if (line.toLowerCase().startsWith("full changelog") || line.toLowerCase().startsWith("**full changelog")) {
                continue;
            }

            // 3. Bullet points: - , * , + , • , or 1.
            boolean isBullet = false;
            if (line.startsWith("* ") || line.startsWith("- ") || line.startsWith("+ ") || line.startsWith("• ")) {
                line = line.substring(2).trim();
                isBullet = true;
            } else if (line.matches("^\\d+\\.\\s+.*")) {
                line = line.replaceFirst("^\\d+\\.\\s+", "").trim();
                isBullet = true;
            }

            // If stripped line was just divider (e.g. • ---)
            if (line.matches("^-{2,}$") || line.matches("^\\*{2,}$")) {
                if (hasAddedAnyView) {
                    View divider = new View(activity);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, 1 * density));
                    lp.topMargin = (int) (10 * density);
                    lp.bottomMargin = (int) (10 * density);
                    divider.setLayoutParams(lp);
                    divider.setBackgroundColor(0x26FFFFFF);
                    container.addView(divider);
                }
                continue;
            }

            if (line.isEmpty()) {
                continue;
            }

            SpannableStringBuilder content = parseInlineMarkdown(line);
            if (isBullet) {
                SpannableStringBuilder bulletSsb = new SpannableStringBuilder();
                bulletSsb.append("•  ");
                bulletSsb.setSpan(new ForegroundColorSpan(0xFFCBD5E1), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                bulletSsb.setSpan(new StyleSpan(Typeface.BOLD), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                bulletSsb.append(content);
                container.addView(createBulletView(activity, bulletSsb, density));
            } else {
                container.addView(createBulletView(activity, content, density));
            }
            hasAddedAnyView = true;
        }

        if (!hasAddedAnyView) {
            container.addView(createBulletView(activity, new SpannableStringBuilder("Bug fixes and performance improvements."), density));
        }
    }

    private static TextView createBulletView(Context context, CharSequence text, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFCBD5E1); // Slate 300
        tv.setTextSize(13.5f);
        tv.setPadding(0, (int) (3 * density), 0, (int) (3 * density));
        tv.setLineSpacing(4 * density, 1.0f); // 4dp line spacing extra
        tv.setShadowLayer(3f, 0f, 1f, 0x33000000); // 0 1px 3px rgba(0,0,0,0.2)
        return tv;
    }

    private static void deleteTempUpdateFile(Context context) {
        try {
            File temp = new File(new File(context.getCacheDir(), "updates"), "update.apk.tmp");
            if (temp.exists()) temp.delete();
        } catch (Exception ignored) {
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    /**
     * Launches Android Package Installer using FileProvider. Prompts for unknown sources permission if needed.
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "installApk aborted: activity invalid or apkFile does not exist: " + (apkFile != null ? apkFile.getAbsolutePath() : "null"));
            return;
        }

        Log.d(TAG, "Preparing to install APK: " + apkFile.getAbsolutePath() + " (size: " + apkFile.length() + " bytes)");

        // Android 8.0+ Unknown sources permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                Log.d(TAG, "Permission REQUEST_INSTALL_PACKAGES not granted yet. Prompting user to enable unknown sources...");
                sPendingInstallApk = apkFile;
                showToast(activity, "Please allow GuTube to install updates");
                try {
                    Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(permissionIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch ACTION_MANAGE_UNKNOWN_APP_SOURCES, falling back to security settings: " + e.getMessage(), e);
                    Intent fallback = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                    activity.startActivity(fallback);
                }
                return;
            }
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".update_provider",
                    apkFile
            );

            Log.d(TAG, "Launching Android Package Installer with URI: " + apkUri);
            // Trigger B: Flag post-update confetti so it triggers immediately when user opens updated app
            activity.getSharedPreferences("gutube_app", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("pending_update_confetti", true)
                    .apply();
            Log.d("UpdateConfetti", "GitHubAppUpdater: Set pending_update_confetti = true before launching installer.");

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start package installer: " + e.getMessage(), e);
            showToast(activity, "Failed to launch package installer");
        }
    }

    /**
     * Compares release tag with app version name. Returns true if releaseTag is strictly newer.
     * Both inputs are parsed to purely numeric dot-separated integers (e.g. 3.4.3 > 3.4.2).
     */
    public static boolean isNewerVersion(String releaseTag, String currentVersion) {
        if (releaseTag == null || currentVersion == null) return false;
        String cleanRelease = cleanVersionName(releaseTag);
        String cleanCurrent = cleanVersionName(currentVersion);

        String[] relParts = cleanRelease.split("\\.");
        String[] curParts = cleanCurrent.split("\\.");

        int maxLen = Math.max(relParts.length, curParts.length);
        for (int i = 0; i < maxLen; i++) {
            int r = 0;
            int c = 0;
            if (i < relParts.length) {
                try { r = Integer.parseInt(relParts[i]); } catch (NumberFormatException ignored) {}
            }
            if (i < curParts.length) {
                try { c = Integer.parseInt(curParts[i]); } catch (NumberFormatException ignored) {}
            }
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    private static String getCurrentVersion(Context context) {
        try {
            if (context != null) {
                String vn = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                if (vn != null && !vn.isEmpty()) {
                    return vn;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "PackageManager lookup failed, using BuildConfig.VERSION_NAME: " + e.getMessage());
        }
        return BuildConfig.VERSION_NAME;
    }

    /**
     * Cleans version strings by extracting pure numeric semver (e.g., 'v3.4.2 Severus' -> '3.4.2').
     * Strips leading 'v'/'V', codenames, suffixes, and non-numeric leading/trailing tokens.
     */
    public static String cleanVersionName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "0";
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(raw.trim());
        if (m.find()) {
            return m.group(1);
        }
        return raw.trim().replaceAll("^[vV]", "").trim();
    }

    /**
     * Parses release body markdown/plain text into clean bullet points.
     */
    private static List<String> parseChangelog(String body) {
        List<String> list = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return list;

        String[] lines = body.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.equalsIgnoreCase("what's changed")
                    || line.toLowerCase().startsWith("full changelog")
                    || line.matches("^[\\s•*-]*---[\\s•*-]*$")
                    || line.matches("^[\\s•*-]*\\*\\*\\*[\\s•*-]*$")
                    || line.matches("^_{3,}$")) {
                continue;
            }
            // Strip leading bullet markers
            if (line.startsWith("* ") || line.startsWith("- ") || line.startsWith("+ ") || line.startsWith("• ")) {
                line = line.substring(2).trim();
            } else if (line.matches("^\\d+\\.\\s+.*")) {
                line = line.replaceFirst("^\\d+\\.\\s+", "").trim();
            }
            if (!line.isEmpty()) {
                list.add(line);
            }
        }
        return list;
    }

    private static void showToast(Context context, String msg) {
        if (context == null) return;
        Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
