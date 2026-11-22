package com.newtube.mobile.notify;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.web.WebSession;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background WorkManager worker for YouTube Channel activity and inbox notifications.
 * Automatically polls both:
 * 1. YouTube Internal Notifications / Bell Icon Feed (for signed-in users via WebSession and MediaService):
 *    captures Comment Replies, Mentions, Creator Hearts, Community Posts, Live Streams & Video Uploads.
 * 2. YouTube Subscriptions Feed: captures latest uploads, Shorts and live streams.
 *
 * Features:
 * - Periodic scheduling (every 15–30 min) enqueued with ExistingPeriodicWorkPolicy.KEEP.
 * - Deduplication via SharedPreferences (notified_item_ids) with safe limit of max 2–3 new notifications per sync cycle.
 * - Tapping a notification routes directly into GuTube (player for video/shorts/live or channel view).
 * - Authentic YouTube notification format: Channel/Actor Name, clean body, subText badges, circular largeIcon,
 *   and high-res BigPicture banner with quick actions (Watch Later & Share).
 */
public class ChannelNotificationWorker extends Worker {
    private static final String TAG = "ChannelNotification";
    public static final String WORK_NAME = "gutube_channel_notifications";
    private static final String PREFS_NAME = "gutube_channel_notifications";
    private static final String KEY_NOTIFIED_ITEM_IDS = "notified_item_ids";
    private static final int MAX_POSTS_PER_RUN = 3; // Limit to max 2-3 per sync cycle to prevent spam/lag
    private static final int MAX_SEEN_STORED = 500;

    public static final String GROUP_CHANNEL_NOTIFICATIONS = "gutube_channel_notifications";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public ChannelNotificationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Schedules periodic channel activity checks (every 15–30 minutes) using KEEP policy.
     */
    public static void schedule(Context context) {
        if (context == null) return;
        WorkManager work = WorkManager.getInstance(context);

        if (!isEnabled(context)) {
            work.cancelUniqueWork(WORK_NAME);
            return;
        }

        int intervalMinutes = NewUploadsWorker.everyMinutes(context);
        if (intervalMinutes < 15) {
            intervalMinutes = 15;
        } else if (intervalMinutes > 30) {
            intervalMinutes = 30; // user requested 15-30 min periodic
        }

        work.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(ChannelNotificationWorker.class, intervalMinutes, TimeUnit.MINUTES)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build())
                        .build()
        );
    }

    public static boolean isEnabled(Context context) {
        return NewUploadsWorker.isEnabled(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        NotificationHelper.createNotificationChannels(context);

        if (!isEnabled(context) || !NotificationHelper.hasNotificationPermission(context)) {
            return Result.success();
        }

        boolean signedIn = false;
        try {
            signedIn = YouTubeServiceManager.instance().getSignInService().isSigned();
        } catch (Throwable ignored) {}
        if (!signedIn) {
            try {
                signedIn = WebSession.isSignedIn();
            } catch (Throwable ignored) {}
        }

        List<NotificationEntry> candidates = new ArrayList<>();

        // 1. If signed in, poll YouTube's internal Notifications / Bell Icon feed
        if (signedIn) {
            // A) WebSession notification inbox (Comment replies, mentions, creator hearts, community posts, uploads, live streams)
            try {
                if (WebSession.isSignedIn()) {
                    JSONObject query = new JSONObject();
                    query.put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX");
                    JSONObject resp = WebSession.post("notification/get_notification_menu", query);
                    List<NotificationEntry> webEntries = parseWebNotifications(resp);
                    if (webEntries != null && !webEntries.isEmpty()) {
                        candidates.addAll(webEntries);
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "WebSession inbox check: " + t.getMessage());
            }

            // B) MediaService NotificationsService (OAuth/TV internal inbox)
            try {
                MediaGroup inbox = YouTubeServiceManager.instance().getNotificationsService().getNotificationItems();
                if (inbox != null && inbox.getMediaItems() != null) {
                    for (MediaItem mi : inbox.getMediaItems()) {
                        NotificationEntry entry = fromMediaItem(mi);
                        if (entry != null) {
                            candidates.add(entry);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "NotificationsService inbox check: " + t.getMessage());
            }
        }

        // 2. Also poll YouTube Subscriptions feed (captures new uploads, shorts & live streams)
        try {
            MediaGroup feed = YouTubeServiceManager.instance().getContentService().getSubscriptions();
            if (feed != null && feed.getMediaItems() != null) {
                for (MediaItem mi : feed.getMediaItems()) {
                    NotificationEntry entry = fromMediaItem(mi);
                    if (entry != null) {
                        candidates.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Subscriptions feed check failed: " + e.getMessage());
        }

        if (candidates.isEmpty()) {
            return Result.success();
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_NOTIFIED_ITEM_IDS, null);
        boolean firstRun = (stored == null);

        Set<String> seen = new LinkedHashSet<>(firstRun ? new ArrayList<>() : Arrays.asList(TextUtils.split(stored, ",")));
        List<NotificationEntry> fresh = new ArrayList<>();
        java.util.Map<String, String> muted = NewUploadsWorker.muted(context);

        // Deduplicate using SharedPreferences (notified_item_ids) and limit to max 2-3 per sync cycle
        for (NotificationEntry entry : candidates) {
            String id = entry.id;
            if (id == null || id.isEmpty() || !seen.add(id)) {
                continue;
            }
            if (!firstRun && fresh.size() < MAX_POSTS_PER_RUN && !isMuted(muted, entry)) {
                fresh.add(entry);
            }
        }

        // Persist seen items trimmed to MAX_SEEN_STORED
        List<String> idsList = new ArrayList<>(seen);
        int fromIdx = Math.max(0, idsList.size() - MAX_SEEN_STORED);
        prefs.edit().putString(KEY_NOTIFIED_ITEM_IDS, TextUtils.join(",", idsList.subList(fromIdx, idsList.size()))).apply();

        // On subsequent runs, post fresh items (oldest first so newest appears on top)
        if (!fresh.isEmpty() && NotificationHelper.hasNotificationPermission(context)) {
            for (int i = fresh.size() - 1; i >= 0; i--) {
                postNotification(context, fresh.get(i));
            }
        }

        return Result.success();
    }

    private static List<NotificationEntry> parseWebNotifications(JSONObject json) {
        List<NotificationEntry> entries = new ArrayList<>();
        if (json == null) return entries;
        try {
            JSONArray actions = json.optJSONArray("actions");
            if (actions == null || actions.length() == 0) return entries;
            JSONObject openPopup = actions.getJSONObject(0).optJSONObject("openPopupAction");
            if (openPopup == null) return entries;
            JSONObject popup = openPopup.optJSONObject("popup");
            if (popup == null) return entries;
            JSONObject menu = popup.optJSONObject("multiPageMenuRenderer");
            if (menu == null) return entries;
            JSONArray sections = menu.optJSONArray("sections");
            if (sections == null || sections.length() == 0) return entries;
            JSONObject sec = sections.getJSONObject(0).optJSONObject("multiPageMenuNotificationSectionRenderer");
            if (sec == null) return entries;
            JSONArray items = sec.optJSONArray("items");
            if (items == null) return entries;

            for (int i = 0; i < items.length(); i++) {
                JSONObject itemObj = items.getJSONObject(i).optJSONObject("notificationRenderer");
                if (itemObj == null) continue;

                // Extract message
                String fullMessage = "";
                JSONObject shortMessage = itemObj.optJSONObject("shortMessage");
                if (shortMessage != null) {
                    if (shortMessage.has("simpleText")) {
                        fullMessage = shortMessage.optString("simpleText");
                    } else if (shortMessage.has("runs")) {
                        JSONArray runs = shortMessage.optJSONArray("runs");
                        if (runs != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int r = 0; r < runs.length(); r++) {
                                JSONObject run = runs.optJSONObject(r);
                                if (run != null) {
                                    sb.append(run.optString("text", ""));
                                }
                            }
                            fullMessage = sb.toString();
                        }
                    }
                }

                if (TextUtils.isEmpty(fullMessage)) continue;

                // Extract navigation endpoint
                String videoId = null;
                String browseId = null;
                JSONObject endpoint = itemObj.optJSONObject("navigationEndpoint");
                if (endpoint != null) {
                    JSONObject watch = endpoint.optJSONObject("watchEndpoint");
                    if (watch != null) {
                        videoId = watch.optString("videoId", null);
                    }
                    if (videoId == null) {
                        JSONObject reel = endpoint.optJSONObject("reelWatchEndpoint");
                        if (reel != null) {
                            videoId = reel.optString("videoId", null);
                        }
                    }
                    JSONObject browse = endpoint.optJSONObject("browseEndpoint");
                    if (browse != null) {
                        browseId = browse.optString("browseId", null);
                    }
                }

                // Extract thumbnails
                String avatarUrl = null;
                JSONObject thumbObj = itemObj.optJSONObject("thumbnail");
                if (thumbObj != null && thumbObj.has("thumbnails")) {
                    JSONArray thumbs = thumbObj.optJSONArray("thumbnails");
                    if (thumbs != null && thumbs.length() > 0) {
                        avatarUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url");
                    }
                }

                String videoThumbUrl = null;
                JSONObject vThumbObj = itemObj.optJSONObject("videoThumbnail");
                if (vThumbObj != null && vThumbObj.has("thumbnails")) {
                    JSONArray thumbs = vThumbObj.optJSONArray("thumbnails");
                    if (thumbs != null && thumbs.length() > 0) {
                        videoThumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url");
                    }
                }
                if (videoThumbUrl == null) {
                    videoThumbUrl = avatarUrl;
                }

                String notifId = itemObj.optString("notificationId", null);
                NotificationEntry entry = buildEntry(notifId, videoId, browseId, fullMessage, avatarUrl, videoThumbUrl);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parseWebNotifications error: " + e.getMessage());
        }
        return entries;
    }

    private static NotificationEntry fromMediaItem(MediaItem item) {
        if (item == null || item.isUpcoming()) return null;

        String videoId = item.getVideoId();
        String channelId = item.getChannelId();
        String author = cleanChannelName(item.getAuthor());
        String title = item.getTitle();

        if (title == null || title.isEmpty()) {
            return null;
        }

        boolean isShort = item.isShorts() || title.toLowerCase(Locale.ROOT).contains("#short");
        boolean isLive = item.isLive();
        boolean isPost = (item.getType() == MediaItem.TYPE_CHANNEL);

        String subText = null;
        if (isLive) {
            subText = "Live Stream";
        } else if (isShort) {
            subText = "New Short";
        } else if (isPost) {
            subText = "Community Post";
        }

        String thumbUrl = item.getCardImageUrl();
        if ((thumbUrl == null || thumbUrl.isEmpty()) && videoId != null) {
            thumbUrl = "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
        }

        String id = videoId != null && !videoId.isEmpty() ? videoId : (item.getId() != 0 ? String.valueOf(item.getId()) : String.valueOf((author + "_" + title).hashCode()));

        return new NotificationEntry(
                id,
                videoId,
                channelId,
                author,
                title,
                subText,
                null,
                thumbUrl,
                isShort,
                isPost,
                isLive
        );
    }

    private static NotificationEntry buildEntry(String notifId, String videoId, String browseId,
                                                String fullMessage, String avatarUrl, String videoThumbUrl) {
        if (TextUtils.isEmpty(fullMessage)) return null;

        String lower = fullMessage.toLowerCase(Locale.ROOT);
        String subText = null;
        boolean isLive = false;
        boolean isShort = false;
        boolean isPost = false;

        if (lower.contains("replied")) {
            subText = "Comment Reply";
        } else if (lower.contains("mentioned")) {
            subText = "Mention";
        } else if (lower.contains("loved your comment") || lower.contains("added a heart") || lower.contains("heart")) {
            subText = "Creator Heart";
        } else if (lower.contains("is live") || lower.contains("streaming")) {
            subText = "Live Stream";
            isLive = true;
        } else if (lower.contains("#short") || lower.contains("short")) {
            subText = "New Short";
            isShort = true;
        } else if (lower.contains("posted") || lower.contains("post") || videoId == null) {
            subText = "Community Post";
            isPost = true;
        }

        String actor = fullMessage;
        String body = fullMessage;
        int colonIdx = fullMessage.indexOf(": ");
        if (colonIdx > 0) {
            actor = fullMessage.substring(0, colonIdx).trim();
            body = fullMessage.substring(colonIdx + 2).trim();
            String actorLower = actor.toLowerCase(Locale.ROOT);
            if (actorLower.endsWith(" uploaded")) {
                actor = actor.substring(0, actor.length() - 9).trim();
            } else if (actorLower.endsWith(" is live")) {
                actor = actor.substring(0, actor.length() - 8).trim();
                subText = "Live Stream";
                isLive = true;
            }
        }

        String id = notifId != null && !notifId.isEmpty() ? notifId : (videoId != null ? videoId : String.valueOf(fullMessage.hashCode()));

        return new NotificationEntry(
                id,
                videoId,
                browseId,
                actor,
                body,
                subText,
                avatarUrl,
                videoThumbUrl,
                isShort,
                isPost,
                isLive
        );
    }

    @SuppressLint("MissingPermission")
    public static void postNotification(Context context, NotificationEntry entry) {
        if (context == null || entry == null) return;
        NotificationHelper.createNotificationChannels(context);

        // Tap opens video / channel / target directly in GuTube
        Intent openIntent;
        if (entry.videoId != null && !entry.videoId.isEmpty()) {
            openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + entry.videoId));
            openIntent.setComponent(new ComponentName(context, "com.liskovsoft.smartyoutubetv2.tv.ui.main.IntentRouterActivity"));
            openIntent.putExtra("video_id", entry.videoId);
        } else if (entry.browseId != null && !entry.browseId.isEmpty()) {
            String url = (entry.browseId.startsWith("UC") || entry.browseId.startsWith("FE"))
                    ? "https://www.youtube.com/channel/" + entry.browseId
                    : "https://www.youtube.com/" + entry.browseId;
            openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            openIntent.setComponent(new ComponentName(context, "com.liskovsoft.smartyoutubetv2.tv.ui.main.IntentRouterActivity"));
            openIntent.putExtra("channel_id", entry.browseId);
        } else {
            openIntent = new Intent(context, MobileBrowseActivity.class);
        }
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int notifId = entry.id.hashCode();
        PendingIntent tapIntent = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Authentic YouTube Notification Formatting:
        // Title = Channel / Actor Name, Body = Video Title / Comment / Post text
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_CHANNEL_ACTIVITY)
                .setSmallIcon(R.drawable.ic_about_notifications)
                .setContentTitle(entry.channelOrActor)
                .setContentText(entry.titleOrBody)
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .setGroup(GROUP_CHANNEL_NOTIFICATIONS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);

        if (entry.subText != null && !entry.subText.isEmpty()) {
            builder.setSubText(entry.subText);
        }

        // Action 1: "Watch Later" (for videos/shorts/live)
        if (entry.videoId != null && !entry.videoId.isEmpty()) {
            Intent watchLaterIntent = new Intent(context, NotificationActionReceiver.class);
            watchLaterIntent.setAction(NotificationActionReceiver.ACTION_WATCH_LATER);
            watchLaterIntent.putExtra(NotificationActionReceiver.EXTRA_VIDEO_ID, entry.videoId);
            watchLaterIntent.putExtra(NotificationActionReceiver.EXTRA_VIDEO_TITLE, entry.titleOrBody);
            PendingIntent watchLaterPending = PendingIntent.getBroadcast(
                    context,
                    notifId + 1,
                    watchLaterIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Action watchLaterAction = new NotificationCompat.Action.Builder(
                    0,
                    "Watch Later",
                    watchLaterPending
            ).build();
            builder.addAction(watchLaterAction);
        }

        // Action 2: "Share"
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, entry.channelOrActor);
        if (entry.videoId != null && !entry.videoId.isEmpty()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + entry.videoId);
        } else if (entry.browseId != null && !entry.browseId.isEmpty()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/channel/" + entry.browseId);
        } else {
            shareIntent.putExtra(Intent.EXTRA_TEXT, entry.titleOrBody);
        }
        Intent chooser = Intent.createChooser(shareIntent, "Share");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent sharePending = PendingIntent.getActivity(
                context,
                notifId + 2,
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Action shareAction = new NotificationCompat.Action.Builder(
                0,
                "Share",
                sharePending
        ).build();
        builder.addAction(shareAction);

        // Load Avatar for large icon
        Bitmap avatar = NotificationHelper.loadBitmapSync(context, entry.avatarUrl != null ? entry.avatarUrl : entry.thumbUrl, 128, 128, true);
        if (avatar != null) {
            builder.setLargeIcon(avatar);
        }

        // Load banner thumbnail for BigPictureStyle (or use BigTextStyle for text comments/posts)
        Bitmap thumbnail = null;
        if (entry.thumbUrl != null && !entry.thumbUrl.isEmpty() && !entry.thumbUrl.equals(entry.avatarUrl)) {
            thumbnail = NotificationHelper.loadBitmapSync(context, entry.thumbUrl, 720, 405, false);
        }

        if (thumbnail != null) {
            if (avatar == null) {
                builder.setLargeIcon(thumbnail);
            }
            builder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .bigLargeIcon((Bitmap) null)
                    .setSummaryText(entry.titleOrBody));
        } else if (entry.titleOrBody != null && !entry.titleOrBody.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(entry.titleOrBody));
        }

        NotificationManagerCompat.from(context).notify(TAG, notifId, builder.build());
    }

    @SuppressLint("MissingPermission")
    public static void postNotification(Context context, String videoId, String channelName, String title,
                                        boolean isShort, boolean isPost, @Nullable String avatarUrl, @Nullable String thumbUrl) {
        String subText = null;
        if (isShort) subText = "New Short";
        else if (isPost) subText = "Community Post";

        String id = videoId != null && !videoId.isEmpty() ? videoId : String.valueOf((channelName + "_" + title).hashCode());
        NotificationEntry entry = new NotificationEntry(
                id,
                videoId,
                null,
                cleanChannelName(channelName),
                title,
                subText,
                avatarUrl,
                thumbUrl,
                isShort,
                isPost,
                false
        );
        postNotification(context, entry);
    }

    /**
     * Fires a realistic, richly formatted demo YouTube Channel notification for live testing.
     */
    public static void fireTestNotification(Context context) {
        if (context == null) return;
        EXECUTOR.execute(() -> {
            try {
                String testVideoId = "0e3GPea1Tyg";
                String testChannel = "MrBeast";
                String testTitle = "$1,000,000 Hotel Room vs $1 Hotel Room!";
                String testAvatar = "https://yt3.googleusercontent.com/fxGKYucJAVme-YzgnKiYDhPucNfikmCHdmtee98lflKaRhOqwKp5Rpn19mKN2yOxZbIR8vdv=s176-c-k-c0x00ffffff-no-rj";
                String testThumb = "https://i.ytimg.com/vi/0e3GPea1Tyg/maxresdefault.jpg";

                NotificationEntry entry = new NotificationEntry(
                        testVideoId,
                        testVideoId,
                        "UCX6OQ3DkcsbYNE6H8uQQuVA",
                        testChannel,
                        testTitle,
                        null,
                        testAvatar,
                        testThumb,
                        false,
                        false,
                        false
                );
                postNotification(context, entry);
            } catch (Exception e) {
                Log.e(TAG, "fireTestNotification error: " + e.getMessage());
            }
        });
    }

    private static boolean isMuted(java.util.Map<String, String> muted, NotificationEntry entry) {
        if (entry == null || muted == null || muted.isEmpty()) return false;
        if (entry.browseId != null && muted.containsKey(entry.browseId)) return true;
        if (entry.channelOrActor != null && muted.containsValue(entry.channelOrActor)) return true;
        return false;
    }

    private static String cleanChannelName(String author) {
        if (author == null) return "Channel";
        int dot = author.indexOf(" • ");
        return dot > 0 ? author.substring(0, dot) : author;
    }

    public static class NotificationEntry {
        public final String id;
        public final String videoId;
        public final String browseId;
        public final String channelOrActor;
        public final String titleOrBody;
        public final String subText;
        public final String avatarUrl;
        public final String thumbUrl;
        public final boolean isShort;
        public final boolean isPost;
        public final boolean isLive;

        public NotificationEntry(String id, String videoId, String browseId,
                                 String channelOrActor, String titleOrBody, String subText,
                                 String avatarUrl, String thumbUrl,
                                 boolean isShort, boolean isPost, boolean isLive) {
            this.id = id;
            this.videoId = videoId;
            this.browseId = browseId;
            this.channelOrActor = channelOrActor != null ? channelOrActor : "YouTube";
            this.titleOrBody = titleOrBody != null ? titleOrBody : "";
            this.subText = subText;
            this.avatarUrl = avatarUrl;
            this.thumbUrl = thumbUrl;
            this.isShort = isShort;
            this.isPost = isPost;
            this.isLive = isLive;
        }
    }
}
