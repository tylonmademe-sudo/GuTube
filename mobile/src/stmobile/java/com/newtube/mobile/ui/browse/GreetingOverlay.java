package com.newtube.mobile.ui.browse;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.TextViewCompat;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FocusTimer;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.UsageStats;
import com.newtube.mobile.ui.common.YouTubeMode;
import com.newtube.mobile.ui.settings.MobileSettingsActivity;
import com.newtube.mobile.ui.settings.SettingsPages;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * GUTUBE(greeting): a held brand pill frosts the whole screen (a full-screen glass over the page)
 * with a greeting for the time of day and three glass shortcuts: Settings, Customization and Check
 * for updates, under a card of time spent in GuTube (UsageStats). A tap anywhere else, or Back, closes it.
 */
final class GreetingOverlay {
    private static final int[][] PERIODS = {
            {R.string.mobile_greet_morning, R.string.mobile_greet_morning_line},
            {R.string.mobile_greet_afternoon, R.string.mobile_greet_afternoon_line},
            {R.string.mobile_greet_evening, R.string.mobile_greet_evening_line},
            {R.string.mobile_greet_night, R.string.mobile_greet_night_line},
    };

    private final ComponentActivity mActivity;
    private final View mOverlay;
    private final View mScroll;
    private final View mScrollInner;
    private final View mContent;
    private final ViewGroup mActions;
    private final TextView mTitle;
    private final TextView mLine;
    private final View mStats;
    private final View mFocus;
    private final Runnable mFocusTick = this::bindFocus;
    private final Runnable mStatsTick = this::tickStats;
    private int mSelectedDaysAgo = 0;
    private int mMaxBarHeightPx;
    private int mBarWidthPx;
    private boolean mAdaptiveInitialized;
    private boolean mLastCompact;
    private final OnBackPressedCallback mBack = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            hide();
        }
    };

    GreetingOverlay(ComponentActivity activity) {
        mActivity = activity;
        mOverlay = activity.findViewById(R.id.mobile_greet_overlay);
        if (mOverlay instanceof com.newtube.mobile.ui.common.GlassView) {
            ((com.newtube.mobile.ui.common.GlassView) mOverlay).setLiquid(false);
        }
        mScroll = activity.findViewById(R.id.mobile_greet_scroll);
        mScrollInner = activity.findViewById(R.id.mobile_greet_scroll_inner);
        mContent = activity.findViewById(R.id.mobile_greet_content);
        mActions = activity.findViewById(R.id.mobile_greet_actions);
        mTitle = activity.findViewById(R.id.mobile_greet_title);
        mLine = activity.findViewById(R.id.mobile_greet_line);
        if (mOverlay instanceof ViewGroup) {
            ((ViewGroup) mOverlay).setClipChildren(false);
            ((ViewGroup) mOverlay).setClipToPadding(false);
        }
        mOverlay.setOnClickListener(v -> hide());

        if (mScroll instanceof ViewGroup) {
            ((ViewGroup) mScroll).setClipChildren(false);
            ((ViewGroup) mScroll).setClipToPadding(false);
        }
        if (mScroll != null) {
            mScroll.setOnClickListener(v -> hide());
            ViewCompat.setOnApplyWindowInsetsListener(mScroll, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                int bottom = YouTubeMode.isOn(mActivity) ? bars.bottom : 0;
                if (v.getPaddingBottom() != bottom) {
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
                    applyAdaptiveLayout();
                }
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(mScroll);
        }
        if (mScrollInner instanceof ViewGroup) {
            ((ViewGroup) mScrollInner).setClipChildren(false);
            ((ViewGroup) mScrollInner).setClipToPadding(false);
        }
        if (mScrollInner != null) {
            mScrollInner.setOnClickListener(v -> hide());
        }

        ViewGroup content = (ViewGroup) mContent;
        if (content != null) {
            content.setClipChildren(false);
            content.setClipToPadding(false);
        }
        mStats = LayoutInflater.from(activity).inflate(R.layout.item_mobile_greet_stats, content, false);
        int insertIndex = (content != null && mActions != null) ? content.indexOfChild(mActions) : -1;
        if (insertIndex >= 0) {
            content.addView(mStats, insertIndex);
        } else if (content != null) {
            content.addView(mStats);
        }
        frost(mStats);

        ViewGroup actionsParent = mActions != null ? mActions : content;
        mFocus = LayoutInflater.from(activity).inflate(R.layout.item_mobile_greet_focus, actionsParent, false);
        actionsParent.addView(mFocus);
        frost(mFocus);
        TextView focusLabel = mFocus.findViewById(R.id.focus_label);
        if (focusLabel != null) {
            focusLabel.setTypeface(com.newtube.mobile.ui.common.AppFonts.medium(activity));
        }
        mFocus.findViewById(R.id.focus_label).setOnClickListener(v -> {
            if (FocusTimer.remainingMs() < 0) { // running: the countdown is not a picker
                com.newtube.mobile.ui.common.Haptics.tick(v);
                FocusTimer.nextMinutes();
                bindFocus();
            }
        });
        mFocus.findViewById(R.id.focus_label).setOnLongClickListener(v -> {
            if (FocusTimer.remainingMs() >= 0) {
                return false;
            }
            askMinutes();
            return true;
        });
        mFocus.findViewById(R.id.focus_toggle).setOnClickListener(v -> {
            Motion.nudge(v);
            if (FocusTimer.remainingMs() < 0) {
                FocusTimer.start();
                hide();
                com.newtube.mobile.ui.common.MobileSnackbar.show(mActivity,
                        mActivity.getString(R.string.mobile_focus_started, FocusTimer.minutes()));
            } else {
                FocusTimer.stop();
                bindFocus();
            }
        });
        addOption(actionsParent, R.drawable.ic_mobile_settings, R.string.header_settings,
                () -> open(null));
        addOption(actionsParent, R.drawable.ic_mobile_update, R.string.check_for_updates, () -> {
            if (AppUpdatePresenter.isInAppUpdatesDisabled()) {
                open(SettingsPages.ABOUT); // store build: no updater, the version lives in About
            } else {
                AppUpdatePresenter.instance(mActivity).start(true);
            }
        });

        if (mOverlay != null) {
            mOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
                if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                    applyAdaptiveLayout();
                }
            });
        }
        applyAdaptiveLayout();
    }

    private void addOption(ViewGroup parent, @DrawableRes int icon, @StringRes int label, Runnable action) {
        View option = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_greet_option, parent, false);
        TextView text = option.findViewById(R.id.mobile_greet_option_label);
        text.setText(label);
        text.setTypeface(com.newtube.mobile.ui.common.AppFonts.medium(mActivity));
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        frost(option);
        option.setOnClickListener(v -> {
            hide();
            action.run();
        });
        parent.addView(option);
    }

    /** GUTUBE(stats): its own heavier frost of the page, so a card or button reads as frosted glass on the greeting's. */
    private void frost(View glass) {
        float blur = glass.getId() == R.id.tiltCard ? 10f : 24f;
        ((com.newtube.mobile.ui.common.GlassView) glass).setBlur(blur);
        com.newtube.mobile.ui.common.Glass.blur(glass, mActivity.findViewById(R.id.mobile_blur_target));
    }

    private void open(String pageId) {
        mActivity.startActivity(MobileSettingsActivity.intent(mActivity, pageId));
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private void applyAdaptiveLayout() {
        android.util.DisplayMetrics dm = mActivity.getResources().getDisplayMetrics();
        float density = dm.density;
        int widthPx = mOverlay != null && mOverlay.getWidth() > 0 ? mOverlay.getWidth() : dm.widthPixels;
        int heightPx = mOverlay != null && mOverlay.getHeight() > 0 ? mOverlay.getHeight() : dm.heightPixels;
        int scrollPadTop = mScroll != null ? mScroll.getPaddingTop() : 0;
        int scrollPadBottom = mScroll != null ? mScroll.getPaddingBottom() : 0;
        int availHeightPx = Math.max(0, heightPx - scrollPadTop - scrollPadBottom);

        float availableHeightDp = availHeightPx / density;
        boolean compact = availableHeightDp < 700f;

        int padH = dp(20);
        int cardWidth = Math.max(0, Math.min(widthPx - dp(40), dp(340)));
        if (mContent != null) {
            ViewGroup.LayoutParams lp = mContent.getLayoutParams();
            if (lp != null && lp.width != cardWidth) {
                lp.width = cardWidth;
                mContent.setLayoutParams(lp);
            }
        }

        int actionsWidthPx = Math.min(cardWidth, dp(252));
        int pillHeight = dp(compact ? 46 : 50);
        if (mActions != null) {
            ViewGroup.LayoutParams lp = mActions.getLayoutParams();
            if (lp != null && lp.width != actionsWidthPx) {
                lp.width = actionsWidthPx;
                mActions.setLayoutParams(lp);
            }
            for (int i = 0; i < mActions.getChildCount(); i++) {
                View child = mActions.getChildAt(i);
                ViewGroup.LayoutParams clp = child.getLayoutParams();
                if (clp != null && clp.height != pillHeight) {
                    clp.height = pillHeight;
                    child.setLayoutParams(clp);
                }
            }
        }

        if (!mAdaptiveInitialized || mLastCompact != compact) {
            if (mTitle != null) {
                mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 30f : 34f);
            }
            if (mLine != null && mLine.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lineLp = (ViewGroup.MarginLayoutParams) mLine.getLayoutParams();
                int lineBottom = dp(16);
                if (lineLp.bottomMargin != lineBottom) {
                    lineLp.bottomMargin = lineBottom;
                    mLine.setLayoutParams(lineLp);
                }
            }
            TextView hero = mStats.findViewById(R.id.stats_hero);
            if (hero != null) {
                int maxSp = compact ? 36 : 40;
                hero.setMaxLines(1);
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        hero, 26, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
            }
        }

        int chartHeightPx = dp(compact ? 58 : 68);
        int newMaxBarHeightPx = Math.max(dp(20), chartHeightPx - dp(6));
        int newBarWidthPx = Math.min(dp(22), Math.max(dp(14), (cardWidth - dp(44)) / 7 - dp(10)));
        boolean barDimsChanged = mMaxBarHeightPx != newMaxBarHeightPx || mBarWidthPx != newBarWidthPx;
        mMaxBarHeightPx = newMaxBarHeightPx;
        mBarWidthPx = newBarWidthPx;

        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        if (bars != null) {
            ViewGroup.LayoutParams barsLp = bars.getLayoutParams();
            if (barsLp != null && barsLp.height != chartHeightPx) {
                barsLp.height = chartHeightPx;
                bars.setLayoutParams(barsLp);
            }
            if (barDimsChanged && bars.getChildCount() == 7) {
                long max = 1;
                long[] days = new long[7];
                for (int i = 0; i < 7; i++) {
                    days[i] = UsageStats.appMs(i);
                    max = Math.max(max, days[i]);
                }
                int minBarPx = dp(4);
                for (int k = 0; k < 7; k++) {
                    ViewGroup column = (ViewGroup) bars.getChildAt(k);
                    if (column != null && column.getChildCount() > 0) {
                        View bar = column.getChildAt(0);
                        android.widget.FrameLayout.LayoutParams blp =
                                (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
                        int bh = Math.max(minBarPx, Math.round(mMaxBarHeightPx * (float) days[6 - k] / max));
                        if (blp != null && (blp.width != mBarWidthPx || blp.height != bh)) {
                            blp.width = mBarWidthPx;
                            blp.height = bh;
                            bar.setLayoutParams(blp);
                        }
                    }
                }
            }
        }

        // Smart Centering + Scroll Guarantee: center vertically if content fits, else scroll cleanly from top
        int contentHeight = 0;
        if (mContent != null && cardWidth > 0) {
            mContent.measure(
                    View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            contentHeight = mContent.getMeasuredHeight();
        }
        int padTop;
        int padBottom;
        if (contentHeight > 0 && contentHeight + dp(32) <= availHeightPx) {
            int centerPad = Math.max(dp(16), (availHeightPx - contentHeight) / 2);
            padTop = centerPad;
            padBottom = centerPad;
        } else {
            padTop = dp(16);
            padBottom = dp(24);
        }
        if (mScrollInner != null && (mScrollInner.getPaddingLeft() != padH
                || mScrollInner.getPaddingTop() != padTop
                || mScrollInner.getPaddingRight() != padH
                || mScrollInner.getPaddingBottom() != padBottom)) {
            mScrollInner.setPadding(padH, padTop, padH, padBottom);
        }

        mLastCompact = compact;
        mAdaptiveInitialized = true;
    }

    void show() {
        if (mScroll != null) {
            mScroll.scrollTo(0, 0);
            ViewCompat.requestApplyInsets(mScroll);
        }
        int[] period = PERIODS[period(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))];
        mTitle.setText(period[0]);
        mLine.setText(period[1]);
        mSelectedDaysAgo = 0;
        applyAdaptiveLayout();
        bindStats();
        bindFocus();
        applyAdaptiveLayout();
        startStatsTicker();

        StarfieldView starfield = mStats.findViewById(R.id.starfieldCanvas);
        if (starfield != null) {
            starfield.start();
        }

        mBack.remove(); // re-added last, so it is the callback Back reaches first
        mActivity.getOnBackPressedDispatcher().addCallback(mBack);

        mOverlay.animate().cancel();
        mContent.animate().cancel();
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.setAlpha(0f);
        mOverlay.animate().alpha(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        mContent.setScaleX(0.92f);
        mContent.setScaleY(0.92f);
        mContent.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
    }

    private void hide() {
        mBack.remove();
        mFocus.removeCallbacks(mFocusTick);
        mStats.removeCallbacks(mStatsTick);
        StarfieldView starfield = mStats.findViewById(R.id.starfieldCanvas);
        if (starfield != null) {
            starfield.stop();
        }
        if (mOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        mOverlay.animate().cancel();
        mOverlay.animate().alpha(0f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mOverlay.setVisibility(View.GONE)).start();
    }

    /** GUTUBE(focus): a held Timer pill asks for any length in minutes. */
    private void askMinutes() {
        float dp = mActivity.getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(mActivity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
        input.setHint(R.string.mobile_focus_custom_hint);
        input.setText(String.valueOf(FocusTimer.minutes()));
        input.setSelectAllOnFocus(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(mActivity);
        box.setPadding(Math.round(24 * dp), Math.round(8 * dp), Math.round(24 * dp), 0);
        box.addView(input);
        androidx.appcompat.app.AlertDialog dialog = FocusTimer.frosted(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                mActivity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_focus_custom_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        FocusTimer.setMinutes(Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException ignored) {
                        return; // empty: keep the old length
                    }
                    bindFocus();
                })
                .setNegativeButton(android.R.string.cancel, null));
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        dialog.show();
    }

    /** GUTUBE(focus): the picked minutes, or the countdown (ticking each second while shown). */
    private void bindFocus() {
        mFocus.removeCallbacks(mFocusTick);
        long left = FocusTimer.remainingMs();
        TextView label = mFocus.findViewById(R.id.focus_label);
        android.widget.ImageButton toggle = mFocus.findViewById(R.id.focus_toggle);
        boolean running = left >= 0;
        label.setText(running
                ? mActivity.getString(R.string.mobile_focus_running,
                        String.format(java.util.Locale.getDefault(), "%d:%02d", left / 60_000, left / 1000 % 60))
                : mActivity.getString(R.string.mobile_focus_idle, FocusTimer.minutes()));
        toggle.setImageResource(running ? R.drawable.ic_mobile_focus_stop : R.drawable.ic_player_play);
        toggle.setContentDescription(mActivity.getString(running ? R.string.mobile_focus_stop : R.string.mobile_focus_start));
        if (running) {
            mFocus.postDelayed(mFocusTick, left == 0 ? 500 : left % 1000 + 1);
        }
    }

    private void startStatsTicker() {
        mStats.removeCallbacks(mStatsTick);
        mStats.postDelayed(mStatsTick, 1000);
    }

    private void tickStats() {
        if (mOverlay == null || mOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        updateLiveTodayStats();
        mStats.postDelayed(mStatsTick, 1000);
    }

    private void updateLiveTodayStats() {
        long todayAppMs = UsageStats.appMs(0);
        long todayWatchMs = UsageStats.watchMs(0);
        long todayVideos = UsageStats.videos(0);

        long week = 0;
        long max = 1;
        long[] days = new long[7];
        for (int i = 0; i < 7; i++) {
            days[i] = UsageStats.appMs(i);
            week += days[i];
            max = Math.max(max, days[i]);
        }

        TextView weekTitle = mStats.findViewById(R.id.stats_week_title);
        if (weekTitle != null) {
            weekTitle.setText(mActivity.getString(R.string.mobile_stats_week_title, UsageStats.duration(week)));
        }

        ViewGroup numbers = mStats.findViewById(R.id.stats_numbers);
        if (numbers != null && numbers.getChildCount() >= 3) {
            if (mSelectedDaysAgo == 0) {
                ViewGroup col0 = (ViewGroup) numbers.getChildAt(0);
                if (col0 != null && col0.getChildCount() > 0) {
                    ((TextView) col0.getChildAt(0)).setText(UsageStats.duration(todayWatchMs));
                }
                ViewGroup col1 = (ViewGroup) numbers.getChildAt(1);
                if (col1 != null && col1.getChildCount() > 0) {
                    ((TextView) col1.getChildAt(0)).setText(String.valueOf(todayVideos));
                }
            }
            ViewGroup col2 = (ViewGroup) numbers.getChildAt(2);
            if (col2 != null && col2.getChildCount() > 0) {
                ((TextView) col2.getChildAt(0)).setText(UsageStats.duration(week / 7));
            }
        }

        if (mSelectedDaysAgo == 0) {
            TextView hero = mStats.findViewById(R.id.stats_hero);
            if (hero != null) {
                hero.setText(UsageStats.duration(todayAppMs));
            }
        }

        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        if (bars != null && bars.getChildCount() == 7) {
            int minBarPx = dp(4);
            int maxBarHeightPx = mMaxBarHeightPx > 0 ? mMaxBarHeightPx : dp(72);
            for (int k = 0; k < 7; k++) {
                ViewGroup column = (ViewGroup) bars.getChildAt(k);
                if (column != null && column.getChildCount() > 0) {
                    View bar = column.getChildAt(0);
                    int barHeight = Math.max(minBarPx, Math.round(maxBarHeightPx * (float) days[6 - k] / max));
                    android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
                    if (lp != null && lp.height != barHeight) {
                        lp.height = barHeight;
                        bar.setLayoutParams(lp);
                    }
                }
            }
        }
    }

    /**
     * GUTUBE(stats): today's time in GuTube, big; today's watch time and videos and the 7-day daily
     * average under it; the 7 days as bars under their total. A tapped bar lights up and updates the hero stats.
     */
    private void bindStats() {
        if (mMaxBarHeightPx <= 0 || mBarWidthPx <= 0) {
            applyAdaptiveLayout();
        }
        android.content.Context context = mActivity;
        long week = 0;
        long max = 1;
        long[] days = new long[7];
        for (int i = 0; i < 7; i++) {
            days[i] = UsageStats.appMs(i);
            week += days[i];
            max = Math.max(max, days[i]);
        }

        ViewGroup numbers = mStats.findViewById(R.id.stats_numbers);
        numbers.removeAllViews();
        number(numbers, UsageStats.duration(UsageStats.watchMs(0)), R.string.mobile_stats_watched);
        number(numbers, String.valueOf(UsageStats.videos(0)), R.string.mobile_stats_videos);
        number(numbers, UsageStats.duration(week / 7), R.string.mobile_stats_avg);
        ((TextView) mStats.findViewById(R.id.stats_week_title)).setText(
                context.getString(R.string.mobile_stats_week_title, UsageStats.duration(week)));

        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        ViewGroup labels = mStats.findViewById(R.id.stats_days);
        bars.removeAllViews();
        labels.removeAllViews();
        int barWidth = mBarWidthPx > 0 ? mBarWidthPx : dp(22);
        int maxBarHeightPx = mMaxBarHeightPx > 0 ? mMaxBarHeightPx : dp(72);
        int minBarPx = dp(4);
        int colMargin = dp(2);
        for (int i = 6; i >= 0; i--) {
            // The bar fills a full-height, clickable column, so a short bar is as easy to tap as a tall one.
            android.widget.FrameLayout column = new android.widget.FrameLayout(context);
            View bar = new View(context);
            bar.setBackgroundResource(i == 0 ? R.drawable.bg_greet_bar_active : R.drawable.bg_greet_bar_inactive);
            int barHeight = Math.max(minBarPx, Math.round(maxBarHeightPx * (float) days[i] / max));
            android.widget.FrameLayout.LayoutParams barLp = new android.widget.FrameLayout.LayoutParams(
                    barWidth, barHeight, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            column.addView(bar, barLp);

            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginStart(colMargin);
            lp.setMarginEnd(colMargin);
            int daysAgo = i;
            column.setOnClickListener(v -> {
                com.newtube.mobile.ui.common.Haptics.tick(v);
                int next = (daysAgo == mSelectedDaysAgo && daysAgo > 0) ? 0 : daysAgo;
                selectDay(next, true);
            });
            bars.addView(column, lp);

            TextView label = new TextView(context);
            label.setText(UsageStats.weekday(i));
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(12);
            label.setTextColor(i == 0 ? 0xFFFFFFFF : 0x99FFFFFF);
            label.setTypeface(i == 0 ? com.newtube.mobile.ui.common.AppFonts.bold(context) : com.newtube.mobile.ui.common.AppFonts.medium(context));
            labels.addView(label, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        selectDay(0, false);
    }

    /** Lights {@code daysAgo}'s bar and label and updates the top hero stats for that day. */
    private void selectDay(int daysAgo, boolean animate) {
        mSelectedDaysAgo = Math.max(0, Math.min(6, daysAgo));
        android.content.Context context = mActivity;
        ViewGroup bars = mStats.findViewById(R.id.stats_bars);
        ViewGroup labels = mStats.findViewById(R.id.stats_days);
        float dp = context.getResources().getDisplayMetrics().density;
        for (int k = 0; k < bars.getChildCount(); k++) {
            boolean on = 6 - k == mSelectedDaysAgo; // bars run oldest (6 days ago) to today
            View bar = ((ViewGroup) bars.getChildAt(k)).getChildAt(0);
            bar.setBackgroundResource(on ? R.drawable.bg_greet_bar_active : R.drawable.bg_greet_bar_inactive);
            bar.animate().cancel();
            if (animate) {
                bar.animate().alpha(on ? 1f : 0.85f).setDuration(Motion.ENTER_MS).start();
            } else {
                bar.setAlpha(on ? 1f : 0.85f);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                bar.setElevation(on ? 6 * dp : 0f);
                bar.setOutlineSpotShadowColor(0x99EA382B);
            }
            TextView label = (TextView) labels.getChildAt(k);
            label.setTextColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            label.setTypeface(on ? com.newtube.mobile.ui.common.AppFonts.bold(context) : com.newtube.mobile.ui.common.AppFonts.medium(context));
        }

        TextView hero = mStats.findViewById(R.id.stats_hero);
        if (hero != null) {
            hero.setText(UsageStats.duration(UsageStats.appMs(mSelectedDaysAgo)));
            if (animate) pulseMetric(hero);
        }

        TextView heroLabel = mStats.findViewById(R.id.stats_hero_label);
        if (heroLabel != null) {
            if (mSelectedDaysAgo == 0) {
                heroLabel.setText(R.string.mobile_stats_hero_label);
            } else {
                Calendar day = Calendar.getInstance();
                day.add(Calendar.DAY_OF_YEAR, -mSelectedDaysAgo);
                String formatted = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(day.getTime());
                heroLabel.setText(context.getString(R.string.mobile_stats_day_hero_label, formatted));
            }
            if (animate) pulseMetric(heroLabel);
        }

        ViewGroup numbers = mStats.findViewById(R.id.stats_numbers);
        if (numbers != null && numbers.getChildCount() >= 2) {
            ViewGroup col0 = (ViewGroup) numbers.getChildAt(0);
            if (col0 != null && col0.getChildCount() > 0) {
                ((TextView) col0.getChildAt(0)).setText(UsageStats.duration(UsageStats.watchMs(mSelectedDaysAgo)));
                if (animate) pulseMetric(col0);
            }
            ViewGroup col1 = (ViewGroup) numbers.getChildAt(1);
            if (col1 != null && col1.getChildCount() > 0) {
                ((TextView) col1.getChildAt(0)).setText(String.valueOf(UsageStats.videos(mSelectedDaysAgo)));
                if (animate) pulseMetric(col1);
            }
        }
    }

    private void pulseMetric(View v) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.setAlpha(0.72f);
        v.setScaleX(0.97f);
        v.setScaleY(0.97f);
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE)
                .start();
    }

    private void number(ViewGroup row, String value, @StringRes int label) {
        android.widget.LinearLayout column = new android.widget.LinearLayout(mActivity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        androidx.appcompat.widget.AppCompatTextView big = new androidx.appcompat.widget.AppCompatTextView(mActivity);
        big.setText(value);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        big.setMaxLines(1);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(big, 14, 21, 1, TypedValue.COMPLEX_UNIT_SP);
        big.setTypeface(com.newtube.mobile.ui.common.AppFonts.displayBold(mActivity));
        big.setTextColor(0xFFFFFFFF);
        big.setIncludeFontPadding(false);
        big.setShadowLayer(3f, 0f, 1f, 0x33000000);
        TextView small = new TextView(mActivity);
        small.setText(label);
        small.setTextSize(12);
        small.setMaxLines(1);
        small.setEllipsize(android.text.TextUtils.TruncateAt.END);
        small.setTypeface(com.newtube.mobile.ui.common.AppFonts.regular(mActivity));
        small.setTextColor(0x99FFFFFF);
        small.setIncludeFontPadding(false);
        small.setShadowLayer(2f, 0f, 1f, 0x33000000);
        android.widget.LinearLayout.LayoutParams lpBig = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        android.widget.LinearLayout.LayoutParams lpSmall = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSmall.topMargin = Math.round(2 * mActivity.getResources().getDisplayMetrics().density);
        big.setGravity(android.view.Gravity.CENTER); // a column's children fill its width: centre the text
        small.setGravity(android.view.Gravity.CENTER);
        column.addView(big, lpBig);
        column.addView(small, lpSmall);
        row.addView(column, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Index into PERIODS: morning 5-11, afternoon 12-16, evening 17-20, night 21-4. */
    static int period(int hour) {
        return hour >= 5 && hour < 12 ? 0 : hour >= 12 && hour < 17 ? 1 : hour >= 17 && hour < 21 ? 2 : 3;
    }
}
