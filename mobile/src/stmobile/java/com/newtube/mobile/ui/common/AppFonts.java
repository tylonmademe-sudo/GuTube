package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.Typeface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * GUTUBE(typography): lazy-cached bundled Google Roboto typefaces.
 * Avoids repeated disk I/O and asset inflation across scrolling feeds, adapters, and custom views.
 */
public final class AppFonts {
    private static volatile Typeface sRegular;
    private static volatile Typeface sMedium;
    private static volatile Typeface sBold;
    private static volatile Typeface sBlack;
    private static volatile Typeface sDisplayBold;
    private static volatile Typeface sDisplaySemiBold;

    private AppFonts() {}

    @NonNull
    public static Typeface regular(@Nullable Context context) {
        if (sRegular == null) {
            synchronized (AppFonts.class) {
                if (sRegular == null) {
                    sRegular = load(context, R.font.roboto_regular, Typeface.create("sans-serif", Typeface.NORMAL));
                }
            }
        }
        return sRegular;
    }

    @NonNull
    public static Typeface medium(@Nullable Context context) {
        if (sMedium == null) {
            synchronized (AppFonts.class) {
                if (sMedium == null) {
                    sMedium = load(context, R.font.roboto_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
                }
            }
        }
        return sMedium;
    }

    @NonNull
    public static Typeface bold(@Nullable Context context) {
        if (sBold == null) {
            synchronized (AppFonts.class) {
                if (sBold == null) {
                    sBold = load(context, R.font.roboto_bold, Typeface.create("sans-serif", Typeface.BOLD));
                }
            }
        }
        return sBold;
    }

    @NonNull
    public static Typeface black(@Nullable Context context) {
        if (sBlack == null) {
            synchronized (AppFonts.class) {
                if (sBlack == null) {
                    sBlack = load(context, R.font.roboto_black, Typeface.create("sans-serif-black", Typeface.NORMAL));
                }
            }
        }
        return sBlack;
    }

    @NonNull
    public static Typeface displayBold(@Nullable Context context) {
        if (sDisplayBold == null) {
            synchronized (AppFonts.class) {
                if (sDisplayBold == null) {
                    sDisplayBold = load(context, R.font.roboto_condensed_bold, Typeface.create("sans-serif-condensed", Typeface.BOLD));
                }
            }
        }
        return sDisplayBold;
    }

    @NonNull
    public static Typeface displaySemiBold(@Nullable Context context) {
        if (sDisplaySemiBold == null) {
            synchronized (AppFonts.class) {
                if (sDisplaySemiBold == null) {
                    sDisplaySemiBold = load(context, R.font.roboto_condensed_semibold, Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                }
            }
        }
        return sDisplaySemiBold;
    }

    @NonNull
    public static Typeface condensedBold(@Nullable Context context) {
        return displayBold(context);
    }

    @NonNull
    private static Typeface load(@Nullable Context context, int resId, @NonNull Typeface fallback) {
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext();
                Typeface tf = ResourcesCompat.getFont(appCtx != null ? appCtx : context, resId);
                if (tf != null) {
                    return tf;
                }
            } catch (Throwable ignored) {
                // Fall back to safe system typeface
            }
        }
        return fallback;
    }
}
