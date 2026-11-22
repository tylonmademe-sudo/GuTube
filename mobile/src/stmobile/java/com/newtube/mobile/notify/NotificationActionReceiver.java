package com.newtube.mobile.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.newtube.mobile.update.UpdateNotificationWorker;

/**
 * Handles background actions for GuTube notifications:
 * - "Watch Later": saves the video to the user's Watch Later playlist and shows user feedback.
 * - "Dismiss": cleanly dismisses the App Update notification.
 */
public class NotificationActionReceiver extends BroadcastReceiver {
    private static final String TAG = "NotifActionReceiver";

    public static final String ACTION_WATCH_LATER = "com.newtube.mobile.action.WATCH_LATER";
    public static final String ACTION_DISMISS_UPDATE = "com.newtube.mobile.action.DISMISS_UPDATE";

    public static final String EXTRA_VIDEO_ID = "video_id";
    public static final String EXTRA_VIDEO_TITLE = "title";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (ACTION_WATCH_LATER.equals(action)) {
            String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
            String title = intent.getStringExtra(EXTRA_VIDEO_TITLE);

            if (videoId != null && !videoId.isEmpty()) {
                try {
                    Video video = new Video();
                    video.videoId = videoId;
                    video.title = title != null ? title : "";
                    MediaServiceManager.instance().addToWatchLaterPlaylist(video);
                } catch (Throwable t) {
                    Log.w(TAG, "addToWatchLaterPlaylist error: " + t.getMessage());
                }

                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context.getApplicationContext(), "Saved to Watch later", Toast.LENGTH_SHORT).show());
            }
        } else if (ACTION_DISMISS_UPDATE.equals(action)) {
            NotificationManagerCompat.from(context).cancel(UpdateNotificationWorker.NOTIFICATION_ID);
        }
    }
}
