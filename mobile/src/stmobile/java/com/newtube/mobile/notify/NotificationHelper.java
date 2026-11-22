package com.newtube.mobile.notify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.concurrent.TimeUnit;

/**
 * Unified notification helper for GuTube:
 * - Manages notification channels ("gutube_channel_activity" and "gutube_app_updates").
 * - Handles Android 13+ (API 33+) POST_NOTIFICATIONS runtime permissions safely.
 * - Provides synchronous image loading for rich notifications.
 */
public final class NotificationHelper {
    private static final String TAG = "NotificationHelper";

    public static final String CHANNEL_CHANNEL_ACTIVITY = "gutube_channel_activity";
    public static final String CHANNEL_NAME_CHANNEL_ACTIVITY = "Channel Uploads, Shorts & Posts";
    public static final String CHANNEL_DESC_CHANNEL_ACTIVITY = "Notifications for new video uploads, Shorts, and community posts from subscribed channels";

    public static final String CHANNEL_APP_UPDATES = "gutube_app_updates";
    public static final String CHANNEL_NAME_APP_UPDATES = "App Updates";
    public static final String CHANNEL_DESC_APP_UPDATES = "Notifications when a new version of GuTube is available";

    public static final int REQUEST_CODE_POST_NOTIFICATIONS = 1002;

    private NotificationHelper() {
    }

    /**
     * Creates and registers dedicated notification channels on Android 8.0+ (API 26+).
     */
    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context == null) {
            return;
        }

        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;

            // 1. Channel Activity (Uploads, Shorts, Posts) - IMPORTANCE_HIGH
            NotificationChannel channelActivity = new NotificationChannel(
                    CHANNEL_CHANNEL_ACTIVITY,
                    CHANNEL_NAME_CHANNEL_ACTIVITY,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channelActivity.setDescription(CHANNEL_DESC_CHANNEL_ACTIVITY);
            channelActivity.enableLights(true);
            channelActivity.enableVibration(true);
            channelActivity.setShowBadge(true);
            nm.createNotificationChannel(channelActivity);

            // 2. App Updates - IMPORTANCE_DEFAULT
            NotificationChannel channelUpdates = new NotificationChannel(
                    CHANNEL_APP_UPDATES,
                    CHANNEL_NAME_APP_UPDATES,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channelUpdates.setDescription(CHANNEL_DESC_APP_UPDATES);
            channelUpdates.enableLights(true);
            channelUpdates.setShowBadge(true);
            nm.createNotificationChannel(channelUpdates);
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification channels: " + e.getMessage());
        }
    }

    /**
     * Checks if notifications are permitted on the device.
     */
    public static boolean hasNotificationPermission(Context context) {
        if (context == null) return false;

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    /**
     * Safely requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     */
    public static void requestNotificationPermission(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_POST_NOTIFICATIONS
                );
            }
        }
    }

    /**
     * Synchronously loads a bitmap using Glide with a timeout.
     * Safe to run from background worker threads or executors.
     */
    @Nullable
    public static Bitmap loadBitmapSync(Context context, String url, int width, int height, boolean circleCrop) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            RequestOptions options = new RequestOptions().timeout(8000);
            if (circleCrop) {
                options = options.circleCrop();
            }
            return Glide.with(context.getApplicationContext())
                    .asBitmap()
                    .load(url.trim())
                    .apply(options)
                    .submit(width, height)
                    .get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load bitmap from " + url + ": " + e.getMessage());
            return null;
        }
    }
}
