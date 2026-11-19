package com.newtube.mobile.update;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

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
    private static final String TAG = "GitHubAppUpdater";
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

        public ReleaseInfo(String tagName, String cleanVersion, String downloadUrl, long apkSize, List<String> changelog) {
            this.tagName = tagName;
            this.cleanVersion = cleanVersion;
            this.downloadUrl = downloadUrl;
            this.apkSize = apkSize;
            this.changelog = changelog;
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
                                Log.d(TAG, "Cleaned update file: " + f.getName() + " (deleted=" + deleted + ")");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error cleaning old updates: " + e.getMessage());
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

        EXECUTOR.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(RELEASES_API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "GuTube-Android-App")
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        Log.d(TAG, "GitHub releases API returned code: " + response.code());
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    String jsonStr = body.string();
                    JSONObject json = new JSONObject(jsonStr);

                    String tagName = json.optString("tag_name", "");
                    if (tagName.isEmpty()) {
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date"));
                        }
                        return;
                    }

                    // Compare version
                    final String currentVersion = getCurrentVersion(activity);
                    if (!isNewerVersion(tagName, currentVersion)) {
                        Log.d(TAG, "Current version (" + currentVersion + ") is >= latest release (" + tagName + ")");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "GuTube is up to date (" + currentVersion + ")"));
                        }
                        return;
                    }

                    // Check if already dismissed this session (only for auto-check)
                    if (!manualCheck && tagName.equals(sDismissedVersionTag)) {
                        Log.d(TAG, "Update " + tagName + " was dismissed for this session");
                        return;
                    }

                    // Extract APK download URL & size from assets array
                    JSONArray assets = json.optJSONArray("assets");
                    String apkUrl = null;
                    long apkSize = 0;
                    if (assets != null) {
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
                                    apkSize = size;
                                    break;
                                }
                                if (apkUrl == null || name.contains("universal")) {
                                    apkUrl = downloadUrl;
                                    apkSize = size;
                                }
                            }
                        }
                    }

                    if (apkUrl == null) {
                        Log.w(TAG, "Release " + tagName + " found, but no .apk asset attached");
                        if (manualCheck) {
                            MAIN_HANDLER.post(() -> showToast(activity, "New release " + tagName + " found, but no APK is available yet."));
                        }
                        return;
                    }

                    // Parse changelog bullet points
                    String rawBody = json.optString("body", "");
                    List<String> changelog = parseChangelog(rawBody);

                    String cleanVersion = cleanVersionName(tagName);
                    ReleaseInfo release = new ReleaseInfo(tagName, cleanVersion, apkUrl, apkSize, changelog);

                    MAIN_HANDLER.post(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            showUpdateDialog(activity, release);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "GitHub update check failed silently: " + e.getMessage());
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

        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.ThemeOverlay_NewTube_BottomSheetDialog);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_github_update, null);
        dialog.setContentView(view);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ImageView iconView = view.findViewById(R.id.github_update_icon);
        TextView titleView = view.findViewById(R.id.github_update_title);
        TextView versionView = view.findViewById(R.id.github_update_version);
        View progressContainer = view.findViewById(R.id.github_update_progress_container);
        LinearProgressIndicator progressBar = view.findViewById(R.id.github_update_progress_bar);
        TextView progressText = view.findViewById(R.id.github_update_progress_text);
        LinearLayout notesContainer = view.findViewById(R.id.github_update_notes_container);
        LinearLayout notesList = view.findViewById(R.id.github_update_notes_list);
        MaterialButton btnCancel = view.findViewById(R.id.github_update_btn_cancel);
        MaterialButton btnAction = view.findViewById(R.id.github_update_btn_action);

        com.newtube.mobile.ui.update.MaxHeightScrollView scroll = view.findViewById(R.id.github_update_scroll);
        if (scroll != null) {
            scroll.setMaxHeight((int) (activity.getResources().getDisplayMetrics().heightPixels * 0.35f));
        }

        AppIcon.show(iconView, false);

        titleView.setText("Update Available");
        String sizeStr = release.apkSize > 0 ? Formatter.formatFileSize(activity, release.apkSize) : "";
        versionView.setText("Version " + release.cleanVersion + (sizeStr.isEmpty() ? "" : " · " + sizeStr));

        // Populate changelog bullet points
        notesList.removeAllViews();
        if (release.changelog.isEmpty()) {
            TextView item = createBulletView(activity, "Bug fixes and performance improvements.");
            notesList.addView(item);
        } else {
            for (String note : release.changelog) {
                TextView item = createBulletView(activity, note);
                notesList.addView(item);
            }
        }

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
            dialog.dismiss();
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

            // Switch to downloading state
            titleView.setText("Downloading update");
            progressContainer.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            progressText.setText("Connecting...");
            btnAction.setEnabled(false);
            btnAction.setText("Downloading...");

            EXECUTOR.execute(() -> {
                File updatesDir = new File(activity.getCacheDir(), "updates");
                updatesDir.mkdirs();
                File tempFile = new File(updatesDir, "update.apk.tmp");
                File finalFile = new File(updatesDir, "update.apk");

                if (tempFile.exists()) tempFile.delete();

                try {
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

                    MAIN_HANDLER.post(() -> {
                        dialog.dismiss();
                        installApk(activity, finalFile);
                    });

                } catch (Exception e) {
                    if (!isCancelled[0]) {
                        Log.e(TAG, "Download error: " + e.getMessage());
                        tempFile.delete();
                        MAIN_HANDLER.post(() -> {
                            if (dialog.isShowing()) {
                                titleView.setText("Update Available");
                                progressContainer.setVisibility(View.GONE);
                                btnAction.setEnabled(true);
                                btnAction.setText("Retry");
                                isDownloading[0] = false;
                                showToast(activity, "Download failed. Please try again.");
                            }
                        });
                    }
                }
            });
        });

        dialog.show();
    }

    private static TextView createBulletView(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText("•  " + text);
        tv.setTextColor(context.getResources().getColor(R.color.mobile_color_on_surface_secondary));
        tv.setTextSize(13.5f);
        tv.setPadding(0, 4, 0, 4);
        tv.setLineSpacing(0, 1.15f);
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
            return;
        }

        // Android 8.0+ Unknown sources permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                sPendingInstallApk = apkFile;
                showToast(activity, "Please allow GuTube to install updates");
                try {
                    Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(permissionIntent);
                } catch (Exception e) {
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

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start package installer", e);
            showToast(activity, "Failed to launch package installer");
        }
    }

    /**
     * Compares release tag with app version name. Returns true if releaseTag is newer.
     */
    public static boolean isNewerVersion(String releaseTag, String currentVersion) {
        if (releaseTag == null || currentVersion == null) return false;
        String cleanRelease = releaseTag.trim().toLowerCase();
        if (cleanRelease.startsWith("v")) {
            cleanRelease = cleanRelease.substring(1).trim();
        }
        cleanRelease = cleanRelease.split(" ")[0].trim();

        String cleanCurrent = currentVersion.trim().toLowerCase();
        if (cleanCurrent.startsWith("v")) {
            cleanCurrent = cleanCurrent.substring(1).trim();
        }
        cleanCurrent = cleanCurrent.split(" ")[0].trim();

        String[] relParts = cleanRelease.split("\\.");
        String[] curParts = cleanCurrent.split("\\.");

        int maxLen = Math.max(relParts.length, curParts.length);
        for (int i = 0; i < maxLen; i++) {
            int r = 0;
            int c = 0;
            if (i < relParts.length) {
                String num = relParts[i].replaceAll("[^0-9]", "");
                if (!num.isEmpty()) {
                    try { r = Integer.parseInt(num); } catch (NumberFormatException ignored) {}
                }
            }
            if (i < curParts.length) {
                String num = curParts[i].replaceAll("[^0-9]", "");
                if (!num.isEmpty()) {
                    try { c = Integer.parseInt(num); } catch (NumberFormatException ignored) {}
                }
            }
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    private static String getCurrentVersion(Context context) {
        try {
            if (context != null) {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            }
        } catch (Exception ignored) {
        }
        return BuildConfig.VERSION_NAME;
    }

    private static String cleanVersionName(String tag) {
        if (tag == null) return "";
        String v = tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1).trim();
        }
        return v.split(" ")[0];
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
                    || line.toLowerCase().startsWith("full changelog")) {
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
