package com.newtube.mobile.ui.common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.lang.reflect.Field;

/**
 * GUTUBE: YouTube-styled pull-to-refresh with native Haptic feedback.
 *
 * <p>Replaces the default Android SwipeRefreshLayout circle and arrow spinner with
 * an overlay of {@link YouTubeLoadingView} (32dp × 32dp inside a clean, floating dark-glass disc).
 * The default CircleImageView is completely suppressed from drawing, while pull distance,
 * tension haptics, and smooth translation/fade/scale animations mirror YouTube's exact feel.</p>
 */
public class HapticSwipeRefreshLayout extends SwipeRefreshLayout {
    /** SwipeRefreshLayout.DRAG_RATE: its own touch pull moves the spinner at half the finger. */
    private static final float DRAG_RATE = 0.5f;
    /** Clicks closer together than this are the finger trembling on the line, not two crossings. */
    private static final long CLICK_GAP_MS = 120;

    private static boolean sLookedUp;
    @Nullable
    private static Field sTotalUnconsumed;
    @Nullable
    private static Field sTotalDragDistance;
    @Nullable
    private static Field sIsBeingDragged;
    @Nullable
    private static Field sInitialMotionY;
    @Nullable
    private static Field sActivePointerId;
    @Nullable
    private static Field sCircleViewField;
    @Nullable
    private static Field sTargetField;

    /** The pull is past the refresh point: letting go now refreshes. */
    private boolean mEngaged;
    private long mLastClickAt;

    @Nullable
    private View mCircleView;
    private FrameLayout mIndicatorContainer;
    private YouTubeLoadingView mLoadingView;

    public HapticSwipeRefreshLayout(@NonNull Context context) {
        this(context, null);
    }

    public HapticSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initCustomIndicator(context);
        suppressDefaultCircle();
    }

    private void initCustomIndicator(Context context) {
        setClipChildren(false);

        float dp = getResources().getDisplayMetrics().density;
        int containerSize = Math.round(42 * dp);
        int spinnerSize = Math.round(32 * dp);

        mIndicatorContainer = new FrameLayout(context);
        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(containerSize, containerSize);
        containerLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        mIndicatorContainer.setLayoutParams(containerLp);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE6212121); // YouTube dark translucent disc (~90% opacity)
        bg.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF); // Clean translucent rim
        mIndicatorContainer.setBackground(bg);
        mIndicatorContainer.setElevation(4 * dp);
        mIndicatorContainer.setVisibility(View.GONE);
        mIndicatorContainer.setAlpha(0f);
        mIndicatorContainer.setScaleX(0.4f);
        mIndicatorContainer.setScaleY(0.4f);
        mIndicatorContainer.setTranslationY(-containerSize);

        // Passthrough touches
        mIndicatorContainer.setClickable(false);
        mIndicatorContainer.setFocusable(false);

        mLoadingView = new YouTubeLoadingView(context);
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(spinnerSize, spinnerSize);
        spinnerLp.gravity = Gravity.CENTER;
        mIndicatorContainer.addView(mLoadingView, spinnerLp);
    }

    private void suppressDefaultCircle() {
        lookUp();
        if (mCircleView == null && sCircleViewField != null) {
            try {
                mCircleView = (View) sCircleViewField.get(this);
            } catch (Exception ignored) {
            }
        }
        if (mCircleView != null) {
            mCircleView.setVisibility(View.GONE);
            mCircleView.setAlpha(0f);
            if (mCircleView instanceof ImageView) {
                ((ImageView) mCircleView).setImageDrawable(null);
            }
        }
    }

    private void ensureIndicator() {
        if (mIndicatorContainer != null && mIndicatorContainer.getParent() == null) {
            lookUp();
            try {
                if (sTargetField != null && sTargetField.get(this) == null) {
                    for (int i = 0; i < getChildCount(); i++) {
                        View child = getChildAt(i);
                        if (!child.getClass().getName().contains("CircleImageView")
                                && child != mIndicatorContainer) {
                            sTargetField.set(this, child);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            addView(mIndicatorContainer);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        suppressDefaultCircle();
        ensureIndicator();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        suppressDefaultCircle();
        ensureIndicator();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        suppressDefaultCircle();
        ensureIndicator();
        if (mIndicatorContainer != null) {
            float dp = getResources().getDisplayMetrics().density;
            int size = Math.round(42 * dp);
            mIndicatorContainer.measure(
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        suppressDefaultCircle();
        ensureIndicator();
        if (mIndicatorContainer != null) {
            int width = getMeasuredWidth();
            int indW = mIndicatorContainer.getMeasuredWidth();
            int indH = mIndicatorContainer.getMeasuredHeight();
            int indLeft = (width - indW) / 2;
            int indTop = getPaddingTop();
            mIndicatorContainer.layout(indLeft, indTop, indLeft + indW, indTop + indH);
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (mCircleView != null && child == mCircleView) {
            return false; // Suppress default CircleImageView completely
        }
        if (child != null && child.getClass().getName().contains("CircleImageView")) {
            mCircleView = child;
            return false;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public void setRefreshing(boolean refreshing) {
        super.setRefreshing(refreshing);
        updateRefreshingState(refreshing);
    }

    private void updateRefreshingState(boolean refreshing) {
        if (mIndicatorContainer == null) {
            return;
        }
        ensureIndicator();
        float dp = getResources().getDisplayMetrics().density;
        float targetRestY = 18 * dp;
        float indH = mIndicatorContainer.getMeasuredHeight() > 0
                ? mIndicatorContainer.getMeasuredHeight() : 42 * dp;

        mIndicatorContainer.animate().cancel();
        if (refreshing) {
            mIndicatorContainer.setVisibility(View.VISIBLE);
            if (mIndicatorContainer.getAlpha() < 0.1f) {
                mIndicatorContainer.setTranslationY(targetRestY);
                mIndicatorContainer.setScaleX(0.4f);
                mIndicatorContainer.setScaleY(0.4f);
                mIndicatorContainer.setAlpha(0f);
            }
            mIndicatorContainer.animate()
                    .translationY(targetRestY)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            if (mLoadingView != null) {
                mLoadingView.playAnimation();
            }
        } else {
            // Smoothly scale/fade it out and pause the Lottie animation
            mIndicatorContainer.animate()
                    .scaleX(0f)
                    .scaleY(0f)
                    .alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        mIndicatorContainer.setVisibility(View.GONE);
                        mIndicatorContainer.setTranslationY(-indH);
                        if (mLoadingView != null) {
                            mLoadingView.pauseAnimation();
                        }
                    })
                    .start();
        }
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        super.onNestedPreScroll(target, dx, dy, consumed);
        follow(nestedPull());
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
            int dyUnconsumed, int type, @NonNull int[] consumed) {
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
        follow(nestedPull());
    }

    @Override
    public void onStopNestedScroll(View target) {
        super.onStopNestedScroll(target);
        mEngaged = false;
        onPullEnded();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mEngaged = false;
            onPullEnded();
        } else if (action == MotionEvent.ACTION_MOVE) {
            follow(touchPull(ev));
        }
        return handled;
    }

    private void follow(float pull) {
        float distance = readFloat(sTotalDragDistance);
        if (pull < 0f || distance <= 0f || isRefreshing()) {
            return;
        }

        updatePullProgress(pull, distance);

        boolean engaged = pull > distance;
        if (engaged != mEngaged) {
            mEngaged = engaged;
            long now = SystemClock.uptimeMillis();
            if (now - mLastClickAt >= CLICK_GAP_MS) {
                mLastClickAt = now;
                Haptics.threshold(this, engaged);
            }
        } else if (!engaged) {
            Haptics.tension(this, pull / distance);
        }
    }

    private void updatePullProgress(float pull, float distance) {
        if (mIndicatorContainer == null) {
            return;
        }
        ensureIndicator();
        mIndicatorContainer.animate().cancel();

        float dp = getResources().getDisplayMetrics().density;
        float targetRestY = 18 * dp;
        float indH = mIndicatorContainer.getMeasuredHeight() > 0
                ? mIndicatorContainer.getMeasuredHeight() : 42 * dp;
        float startY = -indH;

        if (pull <= 0) {
            mIndicatorContainer.setVisibility(View.GONE);
            mIndicatorContainer.setTranslationY(startY);
            mIndicatorContainer.setAlpha(0f);
            mIndicatorContainer.setScaleX(0.4f);
            mIndicatorContainer.setScaleY(0.4f);
            if (mLoadingView != null) {
                mLoadingView.pauseAnimation();
            }
            return;
        }

        mIndicatorContainer.setVisibility(View.VISIBLE);
        float fraction = Math.min(1f, pull / distance);
        float currentY;
        if (pull <= distance) {
            currentY = startY + (targetRestY - startY) * fraction;
        } else {
            float overscroll = (pull - distance) * 0.25f;
            currentY = targetRestY + Math.min(overscroll, 32 * dp);
        }

        mIndicatorContainer.setTranslationY(currentY);
        mIndicatorContainer.setAlpha(Math.min(1f, fraction * 1.4f));
        float scale = Math.min(1f, 0.4f + fraction * 0.6f);
        mIndicatorContainer.setScaleX(scale);
        mIndicatorContainer.setScaleY(scale);

        if (mLoadingView != null) {
            if (!isRefreshing()) {
                if (mLoadingView.isAnimating()) {
                    mLoadingView.pauseAnimation();
                }
                mLoadingView.setProgress(fraction % 1.0f);
            } else {
                if (!mLoadingView.isAnimating()) {
                    mLoadingView.playAnimation();
                }
            }
        }
    }

    private void onPullEnded() {
        if (mIndicatorContainer == null) {
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        float targetRestY = 18 * dp;
        float indH = mIndicatorContainer.getMeasuredHeight() > 0
                ? mIndicatorContainer.getMeasuredHeight() : 42 * dp;

        mIndicatorContainer.animate().cancel();
        if (isRefreshing()) {
            mIndicatorContainer.setVisibility(View.VISIBLE);
            mIndicatorContainer.animate()
                    .translationY(targetRestY)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            if (mLoadingView != null) {
                mLoadingView.playAnimation();
            }
        } else {
            mIndicatorContainer.animate()
                    .translationY(-indH)
                    .alpha(0f)
                    .scaleX(0.4f)
                    .scaleY(0.4f)
                    .setDuration(220)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!isRefreshing()) {
                            mIndicatorContainer.setVisibility(View.GONE);
                            if (mLoadingView != null) {
                                mLoadingView.pauseAnimation();
                            }
                        }
                    })
                    .start();
        }
    }

    /** How far a nested-scroll pull has gone (px), or -1 when unknown. */
    private float nestedPull() {
        lookUp();
        return readFloat(sTotalUnconsumed);
    }

    /**
     * How far a pull caught by this layout itself has gone (px), or -1 when it is not dragging.
     * Measured on the finger SwipeRefreshLayout follows: a second finger takes the pull over.
     */
    private float touchPull(MotionEvent ev) {
        lookUp();
        try {
            if (sIsBeingDragged == null || sInitialMotionY == null || sActivePointerId == null
                    || !sIsBeingDragged.getBoolean(this)) {
                return -1f;
            }
            int index = ev.findPointerIndex(sActivePointerId.getInt(this));
            if (index < 0) {
                return -1f;
            }
            return (ev.getY(index) - sInitialMotionY.getFloat(this)) * DRAG_RATE;
        } catch (IllegalAccessException e) {
            return -1f;
        }
    }

    private float readFloat(@Nullable Field field) {
        lookUp();
        if (field == null) {
            return -1f;
        }
        try {
            return field.getFloat(this);
        } catch (IllegalAccessException e) {
            return -1f;
        }
    }

    private static void lookUp() {
        if (sLookedUp) {
            return;
        }
        sLookedUp = true;
        sTotalUnconsumed = field("mTotalUnconsumed");
        sTotalDragDistance = field("mTotalDragDistance");
        sIsBeingDragged = field("mIsBeingDragged");
        sInitialMotionY = field("mInitialMotionY");
        sActivePointerId = field("mActivePointerId");
        sCircleViewField = field("mCircleView");
        sTargetField = field("mTarget");
    }

    @Nullable
    private static Field field(String name) {
        try {
            Field field = SwipeRefreshLayout.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }
}
