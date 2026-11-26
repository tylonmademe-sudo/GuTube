package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE: Segmented / scrollable chip track backed by {@link UnifiedPillNavView}.
 * Replaces the legacy GooeyNavLayout engine while keeping callers' {@code setChips},
 * {@code setSelected}, {@code setOnPicked}, etc. intact.
 */
public class GlassChipTrack extends FrameLayout {

    public interface Picked {
        void onPicked(int index);
    }

    private final UnifiedPillNavView mPillNav;
    private final HorizontalScrollView mScrollView;
    private final List<String> mBoundLabels = new ArrayList<>();
    private boolean mFixedMode;
    @Nullable private Picked mPicked;

    public GlassChipTrack(@NonNull Context context) {
        this(context, null);
    }

    public GlassChipTrack(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassChipTrack(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipChildren(false);
        setClipToPadding(false);

        boolean fixed = false;
        boolean showTrack = false;
        float pillHeight = -1f;
        float pillTextSize = -1f;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.UnifiedPillNavView);
            fixed = a.getInt(R.styleable.UnifiedPillNavView_pillMode, UnifiedPillNavView.MODE_SCROLLABLE)
                    == UnifiedPillNavView.MODE_FIXED;
            showTrack = a.getBoolean(R.styleable.UnifiedPillNavView_pillShowTrack, false);
            if (a.hasValue(R.styleable.UnifiedPillNavView_pillHeight)) {
                pillHeight = a.getDimension(R.styleable.UnifiedPillNavView_pillHeight, -1f);
            }
            if (a.hasValue(R.styleable.UnifiedPillNavView_pillTextSize)) {
                pillTextSize = a.getDimension(R.styleable.UnifiedPillNavView_pillTextSize, -1f);
            }
            a.recycle();
        }

        int padLeft = getPaddingLeft();
        int padRight = getPaddingRight();
        if (!fixed && (padLeft != 0 || padRight != 0)) {
            super.setPadding(0, getPaddingTop(), 0, getPaddingBottom());
        }

        mFixedMode = fixed;
        // Do NOT forward outer XML attrs (like android:visibility="gone" or android:id) to mPillNav.
        mPillNav = new UnifiedPillNavView(context);
        mPillNav.setFixedMode(fixed);
        mPillNav.setShowTrack(showTrack);
        if (pillHeight > 0f) {
            mPillNav.setPillHeightPx(pillHeight);
        }
        if (pillTextSize > 0f) {
            mPillNav.setPillTextSizePx(pillTextSize);
        }
        mPillNav.setOnTabSelectedListener((index, reselected) -> {
            if (!reselected && mPicked != null) {
                mPicked.onPicked(index);
            }
        });

        mScrollView = new HorizontalScrollView(context);
        mScrollView.setHorizontalScrollBarEnabled(false);
        mScrollView.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        mScrollView.setClipChildren(false);
        mScrollView.setClipToPadding(false);
        if (!fixed && (padLeft != 0 || padRight != 0)) {
            mScrollView.setPadding(padLeft, 0, padRight, 0);
        }

        attachPillHierarchy();
    }

    private void attachPillHierarchy() {
        removeAllViews();
        mScrollView.removeAllViews();
        if (mFixedMode) {
            mPillNav.setHorizontalScrollView(null);
            addView(mPillNav, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            mScrollView.addView(mPillNav, new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mPillNav.setHorizontalScrollView(mScrollView);
            addView(mScrollView, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    public void setFixedMode(boolean fixed) {
        if (mFixedMode != fixed) {
            mFixedMode = fixed;
            mPillNav.setFixedMode(fixed);
            attachPillHierarchy();
        }
    }

    public void setShowTrack(boolean showTrack) {
        mPillNav.setShowTrack(showTrack);
    }

    public void setGlassSource(@Nullable View source) {
        // UnifiedPillNavView renders crisp theme-aware capsules without backdrop sampling.
    }

    public void setOnPicked(@Nullable Picked picked) {
        mPicked = picked;
    }

    public void setChips(@NonNull List<? extends CharSequence> labels) {
        List<String> next = new ArrayList<>(labels.size());
        for (CharSequence label : labels) {
            next.add(label != null ? label.toString() : "");
        }
        if (next.equals(mBoundLabels)) {
            return;
        }
        mBoundLabels.clear();
        mBoundLabels.addAll(next);
        int current = mPillNav.getSelectedIndex();
        mPillNav.setLabels(next, current >= 0 ? current : 0);
    }

    public int getSelectedIndex() {
        return mPillNav.getSelectedIndex();
    }

    public void setSelected(int index) {
        mPillNav.setSelectedIndex(index, false);
    }

    public void select(int index, boolean animate) {
        mPillNav.setSelectedIndex(index, animate);
    }

    public void pick(int index) {
        boolean reselected = index == mPillNav.getSelectedIndex();
        mPillNav.setSelectedIndex(index, true);
        if (!reselected && mPicked != null) {
            mPicked.onPicked(index);
        }
    }
}
