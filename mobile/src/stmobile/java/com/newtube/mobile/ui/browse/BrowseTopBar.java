package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(you-subscreen): Browse's top bar. On a bottom-nav tab it is the brand; a section opened
 * from a You row (Sports, Playlists, ...) is a sub-screen of You, so it gets a back arrow and the
 * section's name - like YouTube's You sub-pages - instead of a brand that gave no hint where the
 * user was or that back returns to You. GUTUBE(glass): both live in the floating brand pill.
 */
final class BrowseTopBar {
    private final TextView mTitle;
    private final View mBack;
    private final View mMark;
    private boolean mSubScreen;
    private CharSequence mSubTitle;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mMark = activity.findViewById(R.id.mobile_brand_mark);
        syncMark(activity);
        if (mBack != null) {
            mBack.setOnClickListener(v -> onBack.run());
        }
        // GUTUBE(quotes): on a tab the brand pill shows a quote; on a sub-screen it goes back.
        QuoteCard quote = new QuoteCard(activity);
        View brandBar = activity.findViewById(R.id.mobile_brand_glass);
        if (brandBar != null) {
            brandBar.setOnClickListener(v -> {
                if (mBack.getVisibility() == View.VISIBLE) {
                    onBack.run();
                } else {
                    com.newtube.mobile.ui.common.Motion.nudge(v);
                    quote.show();
                }
            });
            // GUTUBE(greeting): held, on a tab or a sub-screen, it greets with three shortcuts.
            com.newtube.mobile.ui.common.Glass.blur(activity.findViewById(R.id.mobile_greet_overlay),
                    activity.findViewById(R.id.mobile_blur_target));
            GreetingOverlay greeting = new GreetingOverlay((androidx.activity.ComponentActivity) activity);
            brandBar.setOnLongClickListener(v -> {
                greeting.show();
                return true;
            });
        }
    }

    /**
     * GUTUBE(app icon): the brand follows the picked icon, and shows or drops the app's name
     * (on resume: Settings may have changed either).
     */
    void syncMark(Activity activity) {
        com.newtube.mobile.ui.common.AppIcon.show((ImageView) mMark, true);
        show(mSubScreen, mSubTitle);
    }

    /**
     * A tab shows the brand; a sub-screen shows the back arrow and {@code title} (the app name if
     * the section came without one - the arrow is what matters).
     */
    void show(boolean subScreen, CharSequence title) {
        mSubScreen = subScreen;
        mSubTitle = title;
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        mMark.setVisibility(subScreen ? View.GONE : View.VISIBLE);
        mTitle.setText(subScreen && title != null && title.length() > 0
                ? title : mTitle.getContext().getString(R.string.app_name));

        // GUTUBE(brand name): without the name, the icon sits alone in a round pill.
        boolean named = subScreen || NavTabs.showBrandName(mTitle.getContext());
        mTitle.setVisibility(named ? View.VISIBLE : View.GONE);
        android.content.Context ctx = mTitle.getContext();
        if (subScreen) {
            mTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19);
            mTitle.setTypeface(com.newtube.mobile.ui.common.AppFonts.displayBold(ctx));
            mTitle.setLetterSpacing(-0.01f);
            mTitle.setIncludeFontPadding(true);
        } else {
            mTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21);
            mTitle.setTypeface(com.newtube.mobile.ui.common.AppFonts.displayBold(ctx));
            mTitle.setLetterSpacing(-0.035f);
            mTitle.setIncludeFontPadding(false);
        }

        float density = mMark.getResources().getDisplayMetrics().density;
        View parent = (View) mMark.getParent();
        if (parent != null) {
            parent.setPaddingRelative(0, parent.getPaddingTop(), Math.round(8 * density), parent.getPaddingBottom());
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mMark.getLayoutParams();
        if (lp != null) {
            lp.setMarginEnd(named ? Math.round(6 * density) : 0);
            mMark.setLayoutParams(lp);
        }
    }
}
