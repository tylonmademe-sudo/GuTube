package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * GUTUBE: Lightweight local watch-position store for regular videos.
 * Backed by SharedPreferences ("gutube_watch_progress"), capped at 300 entries via LRU timestamp eviction (<20 KB).
 */
public final class WatchProgressStore {
    public static final String EXTRA_START_POSITION_MS = "extra_start_position_ms";
    private static final String PREFS_NAME = "gutube_watch_progress";
    private static final int MAX_ENTRIES = 300;
    private static final long MIN_POSITION_MS = 3000L;

    // Fast transient bridge for inline preview -> full player handoff
    private static volatile String sPendingPreviewVideoId = null;
    private static volatile long sPendingPreviewPositionMs = 0L;

    private WatchProgressStore() {
    }

    public static void setPendingPreviewPosition(@Nullable String videoId, long positionMs) {
        if (videoId != null && positionMs > 0L) {
            sPendingPreviewVideoId = videoId;
            sPendingPreviewPositionMs = positionMs;
        } else {
            sPendingPreviewVideoId = null;
            sPendingPreviewPositionMs = 0L;
        }
    }

    public static long getPendingPreviewPosition() {
        return sPendingPreviewPositionMs;
    }

    public static long consumePendingPreviewPosition(@Nullable String videoId) {
        if (videoId != null && videoId.equals(sPendingPreviewVideoId)) {
            long pos = sPendingPreviewPositionMs;
            sPendingPreviewVideoId = null;
            sPendingPreviewPositionMs = 0L;
            return pos;
        }
        return 0L;
    }

    public static void clearPendingPreviewPosition() {
        sPendingPreviewVideoId = null;
        sPendingPreviewPositionMs = 0L;
    }

    public static void savePosition(@Nullable Context context, @Nullable String videoId, long positionMs) {
        if (context == null || videoId == null || videoId.trim().isEmpty() || positionMs <= MIN_POSITION_MS) {
            return;
        }

        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();
            SharedPreferences.Editor editor = prefs.edit();

            if (!all.containsKey(videoId) && all.size() >= MAX_ENTRIES) {
                // Evict the oldest entry based on timestamp
                String oldestKey = null;
                long oldestTime = Long.MAX_VALUE;

                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    Object val = entry.getValue();
                    long timestamp = 0L;
                    if (val instanceof String) {
                        String s = (String) val;
                        int idx = s.indexOf(':');
                        if (idx != -1) {
                            try {
                                timestamp = Long.parseLong(s.substring(idx + 1));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    if (timestamp < oldestTime) {
                        oldestTime = timestamp;
                        oldestKey = entry.getKey();
                    }
                }

                if (oldestKey != null) {
                    editor.remove(oldestKey);
                }
            }

            long now = System.currentTimeMillis();
            editor.putString(videoId, positionMs + ":" + now);
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    public static long getPosition(@Nullable Context context, @Nullable String videoId) {
        if (context == null || videoId == null || videoId.trim().isEmpty()) {
            return 0L;
        }

        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String val = prefs.getString(videoId, null);
            if (val == null) {
                return 0L;
            }

            int idx = val.indexOf(':');
            String posStr = idx != -1 ? val.substring(0, idx) : val;
            long pos = Long.parseLong(posStr);
            return pos > MIN_POSITION_MS ? pos : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void removePosition(@Nullable Context context, @Nullable String videoId) {
        if (context == null || videoId == null || videoId.trim().isEmpty()) {
            return;
        }

        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(videoId).apply();
        } catch (Throwable ignored) {
        }
    }
}
