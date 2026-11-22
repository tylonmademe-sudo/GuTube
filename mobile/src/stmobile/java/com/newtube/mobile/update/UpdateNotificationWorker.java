package com.newtube.mobile.update;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.notify.NotificationHelper;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background WorkManager worker for GuTube App Update notifications backed by GitHub Releases.
 * Checks for new releases periodically (every 6-12 hours), prevents duplicate notifications
 * via SharedPreferences, and displays a beautifully formatted BigTextStyle notification with
 * release highlights and an interactive "Update Now" action button.
 */
public class UpdateNotificationWorker extends Worker {
    private static final String TAG = "UpdateNotification";
    public static final String WORK_NAME = "gutube_update_notifications";
    private static final String PREFS_NAME = "gutube_update_notifications";
    private static final String KEY_LAST_NOTIFIED_TAG = "last_notified_release_tag";
    public static final int NOTIFICATION_ID = 0x4E5550;

    public static final String ACTION_TRIGGER_UPDATE = "com.newtube.mobile.action.TRIGGER_UPDATE";
    public static final String EXTRA_TRIGGER_UPDATE = "gutube_trigger_update";

    public static final String GROUP_APP_UPDATES = "gutube_app_update_notifications";

    public UpdateNotificationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Schedules periodic GitHub release checks every 8 hours (online, battery not low).
     */
    public static void schedule(Context context) {
        if (context == null) return;
        WorkManager work = WorkManager.getInstance(context);

        work.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(UpdateNotificationWorker.class, 8, TimeUnit.HOURS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build())
                        .build()
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        NotificationHelper.createNotificationChannels(context);

        if (!NotificationHelper.hasNotificationPermission(context)) {
            return Result.success();
        }

        GitHubAppUpdater.ReleaseInfo release = GitHubAppUpdater.fetchLatestReleaseSync();
        if (release == null || release.tagName == null || release.tagName.isEmpty()) {
            return Result.success();
        }

        String currentVersion = getCurrentVersion(context);
        boolean isNewer = GitHubAppUpdater.isNewerVersion(release.tagName, currentVersion);
        if (!isNewer) {
            Log.d(TAG, "GuTube is up to date: local=" + currentVersion + ", remote=" + release.cleanVersion);
            return Result.success();
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastNotified = prefs.getString(KEY_LAST_NOTIFIED_TAG, "");
        if (release.tagName.equals(lastNotified)) {
            Log.d(TAG, "Already notified user for version " + release.tagName);
            return Result.success();
        }

        // Post notification and record tag
        postUpdateNotification(context, release.cleanVersion, release.changelog);
        prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, release.tagName).apply();

        return Result.success();
    }

    @SuppressLint("MissingPermission")
    public static void postUpdateNotification(Context context, String cleanVersion, List<String> changelog) {
        NotificationHelper.createNotificationChannels(context);

        // Short Title (Zero Truncation): "Update Available • v<versionName>"
        String title = "Update Available • v" + cleanVersion;
        // Clean Body (Zero Cut-Off):
        String contentText = "Tap to install v" + cleanVersion + " or view what's new.";

        // Expanded BigTextStyle: Max 3 concise, single-line bullet points (no redundant intro lines)
        StringBuilder bigText = new StringBuilder();
        if (changelog != null && !changelog.isEmpty()) {
            int count = 0;
            for (String note : changelog) {
                if (note == null || note.trim().isEmpty()) continue;
                String line = note.trim();
                if (line.length() > 65) {
                    line = line.substring(0, 62).trim() + "...";
                }
                if (count > 0) bigText.append("\n");
                bigText.append("• ").append(line);
                count++;
                if (count >= 3) break;
            }
        }
        if (bigText.length() == 0) {
            bigText.append("• Liquid Glass UI & modern card animations\n• High-performance Lottie & offline recovery\n• Rich YouTube notifications & player stability");
        }

        // Tap on notification body: opens GuTube and triggers in-app update sheet
        Intent openIntent = new Intent(context, MobileBrowseActivity.class);
        openIntent.setAction(ACTION_TRIGGER_UPDATE);
        openIntent.putExtra(EXTRA_TRIGGER_UPDATE, true);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentTapIntent = PendingIntent.getActivity(
                context,
                0x4E5551,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Action 1: "Update Now" Action Button PendingIntent
        Intent updateActionIntent = new Intent(context, MobileBrowseActivity.class);
        updateActionIntent.setAction(ACTION_TRIGGER_UPDATE);
        updateActionIntent.putExtra(EXTRA_TRIGGER_UPDATE, true);
        updateActionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent actionPendingIntent = PendingIntent.getActivity(
                context,
                0x4E5552,
                updateActionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Action updateAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_mobile_update,
                "Update Now",
                actionPendingIntent
        ).build();

        // Action 2: "Dismiss" Action Button PendingIntent
        Intent dismissIntent = new Intent(context, com.newtube.mobile.notify.NotificationActionReceiver.class);
        dismissIntent.setAction(com.newtube.mobile.notify.NotificationActionReceiver.ACTION_DISMISS_UPDATE);
        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                0x4E5553,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Action dismissAction = new NotificationCompat.Action.Builder(
                0,
                "Dismiss",
                dismissPendingIntent
        ).build();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_APP_UPDATES)
                .setSmallIcon(R.drawable.ic_mobile_update)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()))
                .setContentIntent(contentTapIntent)
                .addAction(updateAction)
                .addAction(dismissAction)
                .setGroup(GROUP_APP_UPDATES)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * Fires a beautifully formatted demo App Update notification for live testing.
     */
    public static void fireTestNotification(Context context) {
        if (context == null) return;
        List<String> sampleChangelog = Arrays.asList(
                "Liquid Glass UI & modern card animations",
                "High-performance Lottie for Downloads & Offline",
                "Rich YouTube notifications for uploads & shorts"
        );
        postUpdateNotification(context, "3.5.0", sampleChangelog);
    }

    private static String getCurrentVersion(Context context) {
        try {
            if (context != null) {
                String vn = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                if (vn != null && !vn.isEmpty()) {
                    return vn;
                }
            }
        } catch (Exception ignored) {
        }
        return BuildConfig.VERSION_NAME;
    }
}
