package com.newtube.mobile.ui.common;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE(jelly-radio): Native Android port of the React Bits <JellyRadio /> component.
 *
 * <p>Specification & Physics Constants:
 * <ul>
 *   <li>chipColor: #27272a (surface of unchosen chip)</li>
 *   <li>activeColor: #f5f5f5 (surface of chosen chip)</li>
 *   <li>textColor: #f5f5f5 (label of unchosen chip)</li>
 *   <li>activeTextColor: #18181b (label of chosen chip)</li>
 *   <li>size: md (height 36dp, font 13.5sp, horizontal padding 16dp)</li>
 *   <li>gap: 8dp, radius: 18dp</li>
 *   <li>swell: 0.2 (chosen chip expands 1.20x)</li>
 *   <li>barge: 6dp (extra pixels neighbours are shoved)</li>
 *   <li>shrink: 0.05 (unchosen chips give up 0.95x)</li>
 *   <li>jelly: 1.0 (wide-before-tall elastic squish & stretch)</li>
 *   <li>bounce: 0.25 (spring damping ratio zeta = 0.75)</li>
 *   <li>stagger: 22ms (neighbour propagation delay per step)</li>
 *   <li>stiffness: 580 (mass-spring stiffness)</li>
 * </ul>
 *
 * <p>100% lag-free: uses GPU RenderNode matrix transformations (translationX, scaleX, scaleY)
 * with zero layout invalidations during motion.
 */
public class JellyRadioLayout extends HorizontalScrollView {

    public interface OnChangeListener {
        void onChange(String value, int index);
    }

    public interface Picked {
        void onPicked(int index);
    }

    public static class Item {
        public final String value;
        public final CharSequence label;
        public final boolean disabled;

        public Item(@NonNull String value, @NonNull CharSequence label) {
            this(value, label, false);
        }

        public Item(@NonNull String value, @NonNull CharSequence label, boolean disabled) {
            this.value = value;
            this.label = label;
            this.disabled = disabled;
        }
    }

    // Default reference colors & values
    private static final int DEFAULT_CHIP_COLOR = 0xFF27272A;
    private static final int DEFAULT_ACTIVE_COLOR = 0xFFF5F5F5;
    private static final int DEFAULT_TEXT_COLOR = 0xFFF5F5F5;
    private static final int DEFAULT_ACTIVE_TEXT_COLOR = 0xFF18181B;

    private static final float DEFAULT_SWELL = 0.20f;
    private static final float DEFAULT_BARGE_DP = 6f;
    private static final float DEFAULT_SHRINK = 0.05f;
    private static final float DEFAULT_JELLY = 1.0f;
    private static final float DEFAULT_BOUNCE = 0.25f;
    private static final int DEFAULT_STAGGER_MS = 22;
    private static final float DEFAULT_STIFFNESS = 580f;

    private static final float DEFAULT_GAP_DP = 8f;
    private static final float DEFAULT_RADIUS_DP = 18f;
    private static final float DEFAULT_HEIGHT_DP = 36f;
    private static final float DEFAULT_PAD_X_DP = 16f;

    protected final float mDp;
    protected final int mBargePx;
    protected final int mGapPx;
    protected final int mRadiusPx;
    protected final int mHeightPx;
    protected final int mPadXPx;

    protected int mChipColor = DEFAULT_CHIP_COLOR;
    protected int mActiveColor = DEFAULT_ACTIVE_COLOR;
    protected int mTextColor = DEFAULT_TEXT_COLOR;
    protected int mActiveTextColor = DEFAULT_ACTIVE_TEXT_COLOR;

    protected float mSwell = DEFAULT_SWELL;
    protected float mShrink = DEFAULT_SHRINK;
    protected float mJelly = DEFAULT_JELLY;
    protected float mBounce = DEFAULT_BOUNCE;
    protected int mStaggerMs = DEFAULT_STAGGER_MS;
    protected float mStiffness = DEFAULT_STIFFNESS;
    protected boolean mDisabled = false;

    protected final LinearLayout mRow;
    protected final List<Item> mItems = new ArrayList<>();
    protected int mSelectedIndex = 0;
    protected Picked mPickedListener;
    protected OnChangeListener mChangeListener;

    // In-flight motion states
    private ValueAnimator mAnimator;
    private float[] mStartX;
    private float[] mStartSx;
    private float[] mStartSy;
    private float[] mTargetX;
    private float[] mTargetSx;
    private float[] mTargetSy;
    private float[] mCurrentX;
    private float[] mCurrentSx;
    private float[] mCurrentSy;

    private final ArgbEvaluator mColorEvaluator = new ArgbEvaluator();

    public JellyRadioLayout(@NonNull Context context) {
        this(context, null);
    }

    public JellyRadioLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JellyRadioLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDp = getResources().getDisplayMetrics().density;
        mBargePx = Math.round(DEFAULT_BARGE_DP * mDp);
        mGapPx = Math.round(DEFAULT_GAP_DP * mDp);
        mRadiusPx = Math.round(DEFAULT_RADIUS_DP * mDp);
        mHeightPx = Math.round(DEFAULT_HEIGHT_DP * mDp);
        mPadXPx = Math.round(DEFAULT_PAD_X_DP * mDp);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.JellyRadioLayout);
            mChipColor = a.getColor(R.styleable.JellyRadioLayout_jrChipColor, DEFAULT_CHIP_COLOR);
            mActiveColor = a.getColor(R.styleable.JellyRadioLayout_jrActiveColor, DEFAULT_ACTIVE_COLOR);
            mTextColor = a.getColor(R.styleable.JellyRadioLayout_jrTextColor, DEFAULT_TEXT_COLOR);
            mActiveTextColor = a.getColor(R.styleable.JellyRadioLayout_jrActiveTextColor, DEFAULT_ACTIVE_TEXT_COLOR);
            mSwell = a.getFloat(R.styleable.JellyRadioLayout_jrSwell, DEFAULT_SWELL);
            mShrink = a.getFloat(R.styleable.JellyRadioLayout_jrShrink, DEFAULT_SHRINK);
            mJelly = a.getFloat(R.styleable.JellyRadioLayout_jrJelly, DEFAULT_JELLY);
            mBounce = a.getFloat(R.styleable.JellyRadioLayout_jrBounce, DEFAULT_BOUNCE);
            mStaggerMs = a.getInt(R.styleable.JellyRadioLayout_jrStagger, DEFAULT_STAGGER_MS);
            mStiffness = a.getFloat(R.styleable.JellyRadioLayout_jrStiffness, DEFAULT_STIFFNESS);
            mDisabled = a.getBoolean(R.styleable.JellyRadioLayout_jrDisabled, false);
            a.recycle();
        }

        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);

        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.setClipChildren(false);
        mRow.setClipToPadding(false);
        int padH = Math.round(16f * mDp);
        int padV = Math.round(6f * mDp);
        mRow.setPadding(padH, padV, padH, padV);

        addView(mRow, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setChips(@NonNull List<? extends CharSequence> labels) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            CharSequence label = labels.get(i);
            items.add(new Item(label != null ? label.toString() : String.valueOf(i), label != null ? label : ""));
        }
        setItems(items);
    }

    public void setItems(@NonNull List<Item> items) {
        mItems.clear();
        mItems.addAll(items);
        mRow.removeAllViews();
        int count = items.size();
        mStartX = new float[count];
        mStartSx = new float[count];
        mStartSy = new float[count];
        mTargetX = new float[count];
        mTargetSx = new float[count];
        mTargetSy = new float[count];
        mCurrentX = new float[count];
        mCurrentSx = new float[count];
        mCurrentSy = new float[count];

        if (mSelectedIndex >= count) {
            mSelectedIndex = Math.max(0, count - 1);
        }

        Context context = getContext();
        for (int i = 0; i < count; i++) {
            final int index = i;
            Item item = items.get(i);
            TextView chip = new TextView(context);
            chip.setText(item.label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            chip.setTypeface(com.newtube.mobile.ui.common.AppFonts.medium(context));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setIncludeFontPadding(false);
            chip.setPadding(mPadXPx, 0, mPadXPx, 0);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mHeightPx);
            if (i > 0) {
                lp.leftMargin = mGapPx;
            }
            chip.setLayoutParams(lp);
            chip.setEnabled(!mDisabled && !item.disabled);
            chip.setFocusable(true);
            chip.setClickable(true);

            // Active press feedback: scale(0.97)
            chip.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float curSx = mCurrentSx != null && index < mCurrentSx.length ? mCurrentSx[index] : 1f;
                    float curSy = mCurrentSy != null && index < mCurrentSy.length ? mCurrentSy[index] : 1f;
                    v.animate().scaleX(curSx * 0.97f).scaleY(curSy * 0.97f).setDuration(100).start();
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    float curSx = mCurrentSx != null && index < mCurrentSx.length ? mCurrentSx[index] : 1f;
                    float curSy = mCurrentSy != null && index < mCurrentSy.length ? mCurrentSy[index] : 1f;
                    v.animate().scaleX(curSx).scaleY(curSy).setDuration(160).start();
                }
                return false;
            });

            chip.setOnClickListener(v -> commit(index, false));

            chip.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int next = stepFrom(index, 1);
                        commit(next, false);
                        mRow.getChildAt(next).requestFocus();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        int prev = stepFrom(index, -1);
                        commit(prev, false);
                        mRow.getChildAt(prev).requestFocus();
                        return true;
                    }
                }
                return false;
            });

            mRow.addView(chip);
        }

        post(() -> apply(mSelectedIndex, true));
    }

    private int stepFrom(int i, int dir) {
        int n = mItems.size();
        if (n == 0) return 0;
        int j = i;
        for (int tries = 0; tries < n; tries++) {
            j = (j + dir + n) % n;
            if (!mItems.get(j).disabled) return j;
        }
        return i;
    }

    public void setSelected(int index) {
        setSelected(index, true);
    }

    public void setSelected(int index, boolean animate) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        commit(index, !animate);
    }

    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    public void setOnPicked(@Nullable Picked picked) {
        mPickedListener = picked;
    }

    public void setOnChangeListener(@Nullable OnChangeListener listener) {
        mChangeListener = listener;
    }

    private void commit(int index, boolean instant) {
        if (mDisabled || index < 0 || index >= mItems.size() || mItems.get(index).disabled) {
            return;
        }
        if (index == mSelectedIndex && !instant) {
            return;
        }
        int oldIndex = mSelectedIndex;
        mSelectedIndex = index;
        apply(mSelectedIndex, instant);

        Item item = mItems.get(index);
        if (mPickedListener != null) {
            mPickedListener.onPicked(index);
        }
        if (mChangeListener != null) {
            mChangeListener.onChange(item.value, index);
        }
        smoothScrollToCenter(index);
    }

    private void smoothScrollToCenter(int index) {
        View child = mRow.getChildAt(index);
        if (child == null) return;
        int scrollX = (child.getLeft() + child.getRight()) / 2 - getWidth() / 2;
        smoothScrollTo(Math.max(0, scrollX), 0);
    }

    private void apply(int sel, boolean instant) {
        int count = mRow.getChildCount();
        if (count == 0) return;

        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        View selView = mRow.getChildAt(sel);
        float selWidth = selView != null && selView.getWidth() > 0 ? selView.getWidth() : (72f * mDp);
        float push = (selWidth * mSwell) / 2f + mBargePx;

        boolean inFlight = mAnimator != null && mAnimator.isRunning();
        if (mAnimator != null) {
            mAnimator.cancel();
        }

        for (int i = 0; i < count; i++) {
            View chip = mRow.getChildAt(i);
            boolean on = (i == sel);
            float dir = (i == sel) ? 0f : (i > sel ? 1f : -1f) * (rtl ? -1f : 1f);
            float x = dir * push;
            float s = on ? (1f + mSwell) : (1f - mShrink);

            mTargetX[i] = x;
            mTargetSx[i] = s;
            mTargetSy[i] = s;

            if (instant) {
                mCurrentX[i] = x;
                mCurrentSx[i] = s;
                mCurrentSy[i] = s;
                chip.setTranslationX(x);
                chip.setScaleX(s);
                chip.setScaleY(s);
                styleChip((TextView) chip, on, 1f);
            } else {
                mStartX[i] = mCurrentX[i];
                mStartSx[i] = mCurrentSx[i];
                mStartSy[i] = mCurrentSy[i];
            }
        }

        if (instant) {
            return;
        }

        final int durationMs = 450;
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(durationMs);
        mAnimator.setInterpolator(new LinearInterpolator());

        final JellySpringPhysics.SpringConfig[] springsX = new JellySpringPhysics.SpringConfig[count];
        final JellySpringPhysics.SpringConfig[] springsSx = new JellySpringPhysics.SpringConfig[count];
        final JellySpringPhysics.SpringConfig[] springsSy = new JellySpringPhysics.SpringConfig[count];

        for (int i = 0; i < count; i++) {
            int far = Math.abs(i - sel);
            float k = mStiffness * (1f - 0.12f * Math.min(far, 3));
            springsX[i] = new JellySpringPhysics.SpringConfig(k, 0.9f, mBounce);
            float j = mJelly;
            springsSx[i] = new JellySpringPhysics.SpringConfig(
                    k * (1f + 0.24f * j),
                    Math.max(0.1f, 0.9f - 0.1f * j),
                    Math.min(0.85f, mBounce + 0.3f * j));
            springsSy[i] = new JellySpringPhysics.SpringConfig(
                    k * (1f - 0.14f * j),
                    0.9f + 0.05f * j,
                    mBounce);
        }

        final boolean hadInFlight = inFlight;
        mAnimator.addUpdateListener(animation -> {
            long elapsedMs = animation.getCurrentPlayTime();
            float fraction = animation.getAnimatedFraction();

            for (int i = 0; i < count; i++) {
                View chip = mRow.getChildAt(i);
                if (chip == null) continue;

                int far = Math.abs(i - sel);
                long delayMs = hadInFlight ? 0 : (long) (far * mStaggerMs);
                float j = mJelly;
                long delayYMs = delayMs + (long) (50f * j);

                float tX = Math.max(0f, (elapsedMs - delayMs) / 1000f);
                float tSx = Math.max(0f, (elapsedMs - delayMs) / 1000f);
                float tSy = Math.max(0f, (elapsedMs - delayYMs) / 1000f);

                float curX = JellySpringPhysics.eval(springsX[i], mStartX[i], 0f, mTargetX[i], tX);
                float curSx = JellySpringPhysics.eval(springsSx[i], mStartSx[i], 0f, mTargetSx[i], tSx);
                float curSy = JellySpringPhysics.eval(springsSy[i], mStartSy[i], 0f, mTargetSy[i], tSy);

                mCurrentX[i] = curX;
                mCurrentSx[i] = curSx;
                mCurrentSy[i] = curSy;

                chip.setTranslationX(curX);
                chip.setScaleX(curSx);
                chip.setScaleY(curSy);

                boolean on = (i == sel);
                float colorProgress = Math.min(1f, elapsedMs / 200f);
                styleChip((TextView) chip, on, colorProgress);
            }
        });

        mAnimator.start();
    }

    private void styleChip(TextView chip, boolean active, float progress) {
        int bgTarget = active ? mActiveColor : mChipColor;
        int textTarget = active ? mActiveTextColor : mTextColor;

        int bgColor = progress >= 1f ? bgTarget : (int) mColorEvaluator.evaluate(progress, active ? mChipColor : mActiveColor, bgTarget);
        int textColor = progress >= 1f ? textTarget : (int) mColorEvaluator.evaluate(progress, active ? mTextColor : mActiveTextColor, textTarget);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(mRadiusPx);
        shape.setColor(bgColor);
        if (!active) {
            shape.setStroke(Math.round(1f * mDp), 0x22FFFFFF);
        }

        int rippleColor = active ? 0x22000000 : 0x22FFFFFF;
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(mRadiusPx);
        mask.setColor(Color.WHITE);

        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
        chip.setBackground(ripple);
        chip.setTextColor(textColor);
    }
}
