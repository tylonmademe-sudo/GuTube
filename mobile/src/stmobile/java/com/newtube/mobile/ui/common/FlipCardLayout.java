package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE(flip-card): High-fidelity 3D FlipCard component achieving exact feature parity with the
 * React FlipCard component specification, combining native 3D spring tilt physics, finger-following
 * Spotlight illumination, and spring-driven 180° rotation on the Y (or X) axis.
 *
 * <p>Specification & Physics Constants:
 * <ul>
 *   <li>TILT_SPRING: stiffness = 240, damping = 24, mass = 0.6;</li>
 *   <li>LIFT_SPRING: stiffness = 320, damping = 26;</li>
 *   <li>FLIP_SPRING: stiffness = 170, damping = 20;</li>
 *   <li>FLING = 0.16, HISTORY_MS = 90;</li>
 *   <li>Perspective = 1100px;</li>
 *   <li>Corner radius = 22dp, zinc background = #27272a, text color = #f5f5f5;</li>
 *   <li>Shadow opacity = 0.45, glare opacity = 0.22, hover scale = 1.03x;</li>
 *   <li>Full finger-following Spotlight illumination with 15-stop Gaussian falloff.</li>
 * </ul>
 */
public class FlipCardLayout extends FrameLayout {

    public enum Axis {
        Y, X
    }

    public interface OnFlipListener {
        void onFlipChange(boolean flipped);
    }

    // Reference physics constants
    private static final float FLIP_STIFFNESS = 170.0f;
    private static final float FLIP_DAMPING = 20.0f;
    private static final float FLING = 0.16f;
    private static final long HISTORY_MS = 90L;

    private static final float TILT_STIFFNESS = 240.0f;
    private static final float TILT_DAMPING = 24.0f;
    private static final float TILT_MASS = 0.6f;

    private static final float LIFT_STIFFNESS = 320.0f;
    private static final float LIFT_DAMPING = 26.0f;
    private static final float DEFAULT_HOVER_SCALE = 1.03f;
    private static final float DEFAULT_PERSPECTIVE_PX = 1100.0f;
    private static final float DEFAULT_TILT_MAX = 12.0f;
    private static final float DEFAULT_GLARE_OPACITY = 0.22f;
    private static final float DEFAULT_SHADOW_OPACITY = 0.45f;
    private static final int DEFAULT_CARD_BG = 0xFF27272A; // #27272a zinc dark
    private static final int DEFAULT_TEXT_COLOR = 0xFFF5F5F5; // #f5f5f5 ink
    private static final int DEFAULT_SHADOW_COLOR = 0xFF000000;

    // Component configurations
    private float mDensity = 2.5f;
    private Axis mAxis = Axis.Y;
    private boolean mFlipOnClick = true;
    private boolean mDraggable = true;
    private float mDragDistance = 0f; // 0 means auto (width for Y-axis, height for X-axis)
    private boolean mTiltEnabled = true;
    private float mTiltMax = DEFAULT_TILT_MAX;
    private boolean mGlareEnabled = true;
    private float mGlareOpacity = DEFAULT_GLARE_OPACITY;
    private float mHoverScale = DEFAULT_HOVER_SCALE;
    private float mPerspectivePx = DEFAULT_PERSPECTIVE_PX;
    private float mFlipStiffness = FLIP_STIFFNESS;
    private float mFlipDamping = FLIP_DAMPING;

    private int mCardWidth = -1; // -1 means layout_width
    private int mCardHeight = -1; // -1 means layout_height
    private float mCornerRadius; // in px
    private int mCardBackground = DEFAULT_CARD_BG;
    private int mTextColor = DEFAULT_TEXT_COLOR;

    private boolean mShadowEnabled = true;
    private int mShadowColor = DEFAULT_SHADOW_COLOR;
    private float mShadowOpacity = DEFAULT_SHADOW_OPACITY;
    private boolean mDisabled = false;

    // Dynamic state
    private float mCurrentTurn = 0f;
    private float mTargetTurn = 0f;
    private float mVelocityTurn = 0f;
    private boolean mLastNotifiedFlipped = false;

    private float mCurrentTiltX = 0f;
    private float mCurrentTiltY = 0f;
    private float mVelocityTiltX = 0f;
    private float mVelocityTiltY = 0f;
    private float mTargetTiltX = 0f;
    private float mTargetTiltY = 0f;

    private float mCurrentLift = 1.0f;
    private float mVelocityLift = 0f;
    private float mTargetLift = 1.0f;

    private float mCurrentSheen = 0f;
    private float mVelocitySheen = 0f;
    private float mTargetSheen = 0f;
    private float mGlareX = 0f;
    private float mGlareY = 0f;

    // Touch & Gesture tracking
    private final float mTouchSlop;
    private float mDownX = 0f;
    private float mDownY = 0f;
    private float mBaseTurn = 0f;
    private boolean mIsDragging = false;
    private boolean mIsTouchActive = false;

    private static final class HistoryPoint {
        final long t;
        final float v;

        HistoryPoint(long t, float v) {
            this.t = t;
            this.v = v;
        }
    }

    private final List<HistoryPoint> mHistory = new ArrayList<>();
    private OnFlipListener mFlipListener;

    // Animation loop
    private boolean mIsAnimating = false;
    private long mLastTimeMs = 0L;
    @Nullable
    private Choreographer.FrameCallback mFrameCallback;

    // Rendering assets
    private final RectF mBounds = new RectF();
    private final RectF mShadowBounds = new RectF();
    private final Path mClipPath = new Path();
    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Spotlight lighting engine integration
    private final SpotlightTouchHelper mSpotlightHelper;

    public FlipCardLayout(@NonNull Context context) {
        this(context, null);
    }

    public FlipCardLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlipCardLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = getResources().getDisplayMetrics().density;
        mCornerRadius = 22f * mDensity;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mBackgroundPaint.setColor(mCardBackground);
        mBackgroundPaint.setStyle(Paint.Style.FILL);

        mShadowPaint.setColor(mShadowColor);
        mShadowPaint.setStyle(Paint.Style.FILL);

        mGlarePaint.setStyle(Paint.Style.FILL);
        mGlarePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));

        mSpotlightHelper = new SpotlightTouchHelper(mDensity);
        mSpotlightHelper.setColor(0xFFFFFFFF)
                .setIntensity(0.11f)
                .setBorderGlow(0.70f)
                .setSoftness(1.0f)
                .setSmoothing(0.3f)
                .setDrawEdge(false);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FlipCardLayout);
            int axisVal = a.getInt(R.styleable.FlipCardLayout_fcAxis, 0);
            mAxis = axisVal == 1 ? Axis.X : Axis.Y;
            mFlipOnClick = a.getBoolean(R.styleable.FlipCardLayout_fcFlipOnClick, true);
            mDraggable = a.getBoolean(R.styleable.FlipCardLayout_fcDraggable, true);
            mDragDistance = a.getDimension(R.styleable.FlipCardLayout_fcDragDistance, 0f);
            mTiltEnabled = a.getBoolean(R.styleable.FlipCardLayout_fcTilt, true);
            mTiltMax = a.getFloat(R.styleable.FlipCardLayout_fcTiltMax, DEFAULT_TILT_MAX);
            mGlareEnabled = a.getBoolean(R.styleable.FlipCardLayout_fcGlare, true);
            mGlareOpacity = a.getFloat(R.styleable.FlipCardLayout_fcGlareOpacity, DEFAULT_GLARE_OPACITY);
            mHoverScale = a.getFloat(R.styleable.FlipCardLayout_fcHoverScale, DEFAULT_HOVER_SCALE);
            mPerspectivePx = a.getFloat(R.styleable.FlipCardLayout_fcPerspective, DEFAULT_PERSPECTIVE_PX);
            mFlipStiffness = a.getFloat(R.styleable.FlipCardLayout_fcStiffness, FLIP_STIFFNESS);
            mFlipDamping = a.getFloat(R.styleable.FlipCardLayout_fcDamping, FLIP_DAMPING);
            mCardWidth = a.getDimensionPixelSize(R.styleable.FlipCardLayout_fcCardWidth, -1);
            mCardHeight = a.getDimensionPixelSize(R.styleable.FlipCardLayout_fcCardHeight, -1);
            if (a.hasValue(R.styleable.FlipCardLayout_fcRadius)) {
                mCornerRadius = a.getDimension(R.styleable.FlipCardLayout_fcRadius, 22f * mDensity);
            }
            mCardBackground = a.getColor(R.styleable.FlipCardLayout_fcBackground, DEFAULT_CARD_BG);
            mTextColor = a.getColor(R.styleable.FlipCardLayout_fcColor, DEFAULT_TEXT_COLOR);
            mShadowEnabled = a.getBoolean(R.styleable.FlipCardLayout_fcShadow, true);
            mShadowColor = a.getColor(R.styleable.FlipCardLayout_fcShadowColor, DEFAULT_SHADOW_COLOR);
            mShadowOpacity = a.getFloat(R.styleable.FlipCardLayout_fcShadowOpacity, DEFAULT_SHADOW_OPACITY);
            boolean flipped = a.getBoolean(R.styleable.FlipCardLayout_fcFlipped, false);
            if (flipped) {
                mCurrentTurn = 180f;
                mTargetTurn = 180f;
                mLastNotifiedFlipped = true;
            }
            mDisabled = a.getBoolean(R.styleable.FlipCardLayout_fcDisabled, false);
            a.recycle();

            mBackgroundPaint.setColor(mCardBackground);
            mShadowPaint.setColor(mShadowColor);
        }

        setWillNotDraw(false);
        setCameraDistance(mDensity * mPerspectivePx);
    }

    // ---------------------------------------------------------------------------------------------
    // Public Configuration API (Parity with React FlipCard Props)
    // ---------------------------------------------------------------------------------------------

    public void setAxis(@NonNull Axis axis) {
        mAxis = axis;
        requestLayout();
        invalidate();
    }

    public void setAxis(@NonNull String axis) {
        if ("x".equalsIgnoreCase(axis)) {
            setAxis(Axis.X);
        } else {
            setAxis(Axis.Y);
        }
    }

    @NonNull
    public Axis getAxis() {
        return mAxis;
    }

    public void setOnFlipListener(@Nullable OnFlipListener listener) {
        mFlipListener = listener;
    }

    public void setFlipOnClick(boolean flipOnClick) {
        mFlipOnClick = flipOnClick;
    }

    public boolean isFlipOnClick() {
        return mFlipOnClick;
    }

    public void setDraggable(boolean draggable) {
        mDraggable = draggable;
    }

    public boolean isDraggable() {
        return mDraggable;
    }

    public void setDragDistance(float dragDistance) {
        mDragDistance = Math.max(0f, dragDistance);
    }

    public float getDragDistance() {
        return mDragDistance;
    }

    public void setTiltEnabled(boolean tiltEnabled) {
        mTiltEnabled = tiltEnabled;
        if (!tiltEnabled) {
            mTargetTiltX = 0f;
            mTargetTiltY = 0f;
            wakeAnimation();
        }
    }

    public boolean isTiltEnabled() {
        return mTiltEnabled;
    }

    public void setTiltMax(float tiltMax) {
        mTiltMax = Math.max(0f, Math.min(30f, tiltMax));
    }

    public float getTiltMax() {
        return mTiltMax;
    }

    public void setGlareEnabled(boolean glareEnabled) {
        mGlareEnabled = glareEnabled;
        if (!glareEnabled) {
            mTargetSheen = 0f;
        }
        invalidate();
    }

    public boolean isGlareEnabled() {
        return mGlareEnabled;
    }

    public void setGlareOpacity(float glareOpacity) {
        mGlareOpacity = Math.max(0f, Math.min(1.0f, glareOpacity));
        invalidate();
    }

    public float getGlareOpacity() {
        return mGlareOpacity;
    }

    public void setHoverScale(float hoverScale) {
        mHoverScale = Math.max(0.5f, Math.min(2.0f, hoverScale));
    }

    public float getHoverScale() {
        return mHoverScale;
    }

    public void setPerspective(float perspectivePx) {
        mPerspectivePx = Math.max(100f, perspectivePx);
        setCameraDistance(mDensity * mPerspectivePx);
        invalidate();
    }

    public float getPerspective() {
        return mPerspectivePx;
    }

    public void setFlipSpring(float stiffness, float damping) {
        mFlipStiffness = Math.max(10f, stiffness);
        mFlipDamping = Math.max(1f, damping);
    }

    public float getFlipStiffness() {
        return mFlipStiffness;
    }

    public float getFlipDamping() {
        return mFlipDamping;
    }

    public void setCardWidth(int widthDp) {
        mCardWidth = widthDp > 0 ? Math.round(widthDp * mDensity) : -1;
        requestLayout();
    }

    public int getCardWidth() {
        return mCardWidth;
    }

    public void setCardHeight(int heightDp) {
        mCardHeight = heightDp > 0 ? Math.round(heightDp * mDensity) : -1;
        requestLayout();
    }

    public int getCardHeight() {
        return mCardHeight;
    }

    public void setCornerRadius(float radiusDp) {
        mCornerRadius = Math.max(0f, radiusDp * mDensity);
        invalidate();
    }

    public float getCornerRadius() {
        return mCornerRadius / mDensity;
    }

    public void setCardBackground(int color) {
        mCardBackground = color;
        mBackgroundPaint.setColor(color);
        invalidate();
    }

    public int getCardBackground() {
        return mCardBackground;
    }

    public void setTextColor(int color) {
        mTextColor = color;
        applyTextColorRecursively(this, color);
    }

    public int getTextColor() {
        return mTextColor;
    }

    public void setShadowEnabled(boolean enabled) {
        mShadowEnabled = enabled;
        invalidate();
    }

    public boolean isShadowEnabled() {
        return mShadowEnabled;
    }

    public void setShadowColor(int color) {
        mShadowColor = color;
        mShadowPaint.setColor(color);
        invalidate();
    }

    public int getShadowColor() {
        return mShadowColor;
    }

    public void setShadowOpacity(float opacity) {
        mShadowOpacity = Math.max(0f, Math.min(1.0f, opacity));
        invalidate();
    }

    public float getShadowOpacity() {
        return mShadowOpacity;
    }

    public void setDisabled(boolean disabled) {
        mDisabled = disabled;
        if (disabled) {
            rest();
        }
    }

    public boolean isDisabled() {
        return mDisabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setDisabled(!enabled);
    }

    public boolean isFlipped() {
        return isBack(mTargetTurn);
    }

    public void setFlipped(boolean flipped, boolean animate) {
        float target = flipped ? 180f : 0f;
        settle(target, 0f, !animate);
    }

    public void flip() {
        flip(false);
    }

    public void flip(boolean instant) {
        float base = Math.round(mCurrentTurn / 180f) * 180f;
        float next = isBack(base) ? base - 180f : base + 180f;
        settle(next, 0f, instant);
    }

    public void setFrontView(@NonNull View view) {
        if (getChildCount() > 0) {
            removeViewAt(0);
        }
        addView(view, 0);
    }

    @Nullable
    public View getFrontView() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    public void setBackView(@NonNull View view) {
        if (getChildCount() > 1) {
            removeViewAt(1);
        }
        addView(view, Math.min(1, getChildCount()));
    }

    @Nullable
    public View getBackView() {
        return getChildCount() > 1 ? getChildAt(1) : null;
    }

    @NonNull
    public SpotlightTouchHelper getSpotlightHelper() {
        return mSpotlightHelper;
    }

    // ---------------------------------------------------------------------------------------------
    // Layout & Sizing
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpec = widthMeasureSpec;
        int heightSpec = heightMeasureSpec;

        if (mCardWidth > 0) {
            widthSpec = MeasureSpec.makeMeasureSpec(mCardWidth, MeasureSpec.EXACTLY);
        }
        if (mCardHeight > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(mCardHeight, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0, 0, w, h);
        if (mTextColor != DEFAULT_TEXT_COLOR) {
            applyTextColorRecursively(this, mTextColor);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (getChildCount() >= 2) {
            View backView = getChildAt(1);
            if (mAxis == Axis.Y) {
                if (backView.getRotationY() != 180f) {
                    backView.setRotationY(180f);
                }
                if (backView.getRotationX() != 0f) {
                    backView.setRotationX(0f);
                }
            } else {
                if (backView.getRotationX() != 180f) {
                    backView.setRotationX(180f);
                }
                if (backView.getRotationY() != 0f) {
                    backView.setRotationY(0f);
                }
            }
        }
    }

    private static void applyTextColorRecursively(@NonNull ViewGroup root, int color) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof ViewGroup) {
                applyTextColorRecursively((ViewGroup) child, color);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Touch & Gesture Interaction Engine
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mDisabled) {
            return super.dispatchTouchEvent(ev);
        }

        // Stream touch coordinates directly to the Spotlight lighting engine
        mSpotlightHelper.onTouchEvent(this, ev);

        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        int width = getWidth();
        int height = getHeight();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mBaseTurn = mCurrentTurn;
                mIsDragging = false;
                mIsTouchActive = true;
                mTargetLift = mHoverScale;
                mTargetSheen = 1.0f;
                mGlareX = x;
                mGlareY = y;

                mHistory.clear();
                mHistory.add(new HistoryPoint(SystemClock.uptimeMillis(), mCurrentTurn));
                updateTiltTarget(x, y, width, height);
                wakeAnimation();
                break;

            case MotionEvent.ACTION_MOVE:
                mGlareX = x;
                mGlareY = y;
                float dx = x - mDownX;
                float dy = y - mDownY;

                if (!mIsDragging && mDraggable && width > 0 && height > 0) {
                    if (mAxis == Axis.Y) {
                        if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                            mIsDragging = true;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    } else {
                        if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                            mIsDragging = true;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    }
                }

                if (mIsDragging) {
                    float span = mDragDistance > 0f ? mDragDistance : (mAxis == Axis.X ? height : width);
                    float d = mAxis == Axis.X ? dy : dx;
                    float deg = mBaseTurn + (mAxis == Axis.X ? -1f : 1f) * (d / Math.max(1f, span)) * 180f;
                    mCurrentTurn = deg;
                    mTargetTurn = deg;

                    long now = SystemClock.uptimeMillis();
                    mHistory.add(new HistoryPoint(now, deg));
                    while (mHistory.size() > 2 && (now - mHistory.get(0).t) > HISTORY_MS) {
                        mHistory.remove(0);
                    }
                    mTargetTiltX = 0f;
                    mTargetTiltY = 0f;
                    mTargetSheen = 0f;
                } else {
                    updateTiltTarget(x, y, width, height);
                    mTargetSheen = 1.0f;
                }
                wakeAnimation();
                break;

            case MotionEvent.ACTION_UP:
                mIsTouchActive = false;
                mTargetLift = 1.0f;
                mTargetSheen = 0f;
                mTargetTiltX = 0f;
                mTargetTiltY = 0f;

                if (!mIsDragging) {
                    if (mFlipOnClick) {
                        flip(false);
                    } else {
                        settle(Math.round(mCurrentTurn / 180f) * 180f, 0f, false);
                    }
                } else {
                    // Compute release velocity (degrees per second) from history
                    float velocity = 0f;
                    if (mHistory.size() >= 2) {
                        HistoryPoint first = mHistory.get(0);
                        HistoryPoint last = mHistory.get(mHistory.size() - 1);
                        long dt = last.t - first.t;
                        if (dt > 0 && (SystemClock.uptimeMillis() - last.t) < 60) {
                            velocity = ((last.v - first.v) / (float) dt) * 1000f;
                        }
                    }
                    float here = mCurrentTurn;
                    float baseSnap = Math.round(here / 180f) * 180f;
                    float projected = here + velocity * FLING;
                    float to = Math.round(projected / 180f) * 180f;
                    // Clamp to maximum 1 full flip ahead/behind
                    to = Math.max(baseSnap - 180f, Math.min(baseSnap + 180f, to));
                    settle(to, velocity, false);
                }
                mIsDragging = false;
                wakeAnimation();
                break;

            case MotionEvent.ACTION_CANCEL:
                mIsTouchActive = false;
                mIsDragging = false;
                mTargetLift = 1.0f;
                mTargetSheen = 0f;
                mTargetTiltX = 0f;
                mTargetTiltY = 0f;
                settle(Math.round(mBaseTurn / 180f) * 180f, 0f, false);
                wakeAnimation();
                break;
        }

        if (!mIsDragging) {
            super.dispatchTouchEvent(ev);
        } else if (action == MotionEvent.ACTION_MOVE) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        return true;
    }

    private void updateTiltTarget(float x, float y, int width, int height) {
        if (!mTiltEnabled || width <= 0 || height <= 0) {
            mTargetTiltX = 0f;
            mTargetTiltY = 0f;
            return;
        }
        float px = Math.max(0f, Math.min(1f, x / (float) width));
        float py = Math.max(0f, Math.min(1f, y / (float) height));
        mTargetTiltX = (0.5f - py) * 2.0f * mTiltMax;
        mTargetTiltY = (px - 0.5f) * 2.0f * mTiltMax;
    }

    private void settle(float to, float velocity, boolean instant) {
        mTargetTurn = to;
        if (instant) {
            mCurrentTurn = to;
            mVelocityTurn = 0f;
            notifyFlipChangeIfNeeded();
            invalidate();
            return;
        }
        mVelocityTurn = velocity;
        wakeAnimation();
        notifyFlipChangeIfNeeded();
    }

    private void notifyFlipChangeIfNeeded() {
        boolean nextFlipped = isBack(mTargetTurn);
        if (nextFlipped != mLastNotifiedFlipped) {
            mLastNotifiedFlipped = nextFlipped;
            if (mFlipListener != null) {
                mFlipListener.onFlipChange(nextFlipped);
            }
        }
    }

    private static boolean isBack(float deg) {
        return Math.abs(Math.round(deg / 180f)) % 2 == 1;
    }

    private void rest() {
        mTargetTiltX = 0f;
        mTargetTiltY = 0f;
        mTargetLift = 1.0f;
        mTargetSheen = 0f;
        wakeAnimation();
    }

    // ---------------------------------------------------------------------------------------------
    // Animation Tick & Sub-stepped Spring Physics
    // ---------------------------------------------------------------------------------------------

    private void wakeAnimation() {
        if (!mIsAnimating) {
            mIsAnimating = true;
            mLastTimeMs = SystemClock.uptimeMillis();
            if (mFrameCallback == null) {
                mFrameCallback = frameTimeNanos -> tick();
            }
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
        invalidate();
    }

    private void tick() {
        if (!mIsAnimating || !isAttachedToWindow()) {
            mIsAnimating = false;
            return;
        }

        long nowMs = SystemClock.uptimeMillis();
        if (mLastTimeMs == 0L) {
            mLastTimeMs = nowMs;
        }
        float dt = Math.min(0.05f, Math.max(0.001f, (nowMs - mLastTimeMs) / 1000f));
        mLastTimeMs = nowMs;

        // Sub-stepped integration (240Hz)
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / steps;

        for (int step = 0; step < steps; step++) {
            // Spring physics for flip rotation (FLIP_SPRING: stiffness=170, damping=20)
            float accelTurn = mFlipStiffness * (mTargetTurn - mCurrentTurn) - mFlipDamping * mVelocityTurn;
            mVelocityTurn += accelTurn * h;
            mCurrentTurn += mVelocityTurn * h;

            // Spring physics for 3D tilt (TILT_SPRING: stiffness=240, damping=24, mass=0.6)
            float accelTiltX = (TILT_STIFFNESS * (mTargetTiltX - mCurrentTiltX) - TILT_DAMPING * mVelocityTiltX) / TILT_MASS;
            mVelocityTiltX += accelTiltX * h;
            mCurrentTiltX += mVelocityTiltX * h;

            float accelTiltY = (TILT_STIFFNESS * (mTargetTiltY - mCurrentTiltY) - TILT_DAMPING * mVelocityTiltY) / TILT_MASS;
            mVelocityTiltY += accelTiltY * h;
            mCurrentTiltY += mVelocityTiltY * h;

            // Spring physics for lift scale (LIFT_SPRING: stiffness=320, damping=26)
            float accelLift = LIFT_STIFFNESS * (mTargetLift - mCurrentLift) - LIFT_DAMPING * mVelocityLift;
            mVelocityLift += accelLift * h;
            mCurrentLift += mVelocityLift * h;

            // Spring physics for specular sheen (LIFT_SPRING: stiffness=320, damping=26)
            float accelSheen = LIFT_STIFFNESS * (mTargetSheen - mCurrentSheen) - LIFT_DAMPING * mVelocitySheen;
            mVelocitySheen += accelSheen * h;
            mCurrentSheen += mVelocitySheen * h;
        }

        // Settling checks
        if (Math.abs(mTargetTurn - mCurrentTurn) < 0.05f && Math.abs(mVelocityTurn) < 0.1f) {
            mCurrentTurn = mTargetTurn;
            mVelocityTurn = 0f;
        }
        if (Math.abs(mTargetTiltX - mCurrentTiltX) < 0.01f && Math.abs(mVelocityTiltX) < 0.05f) {
            mCurrentTiltX = mTargetTiltX;
            mVelocityTiltX = 0f;
        }
        if (Math.abs(mTargetTiltY - mCurrentTiltY) < 0.01f && Math.abs(mVelocityTiltY) < 0.05f) {
            mCurrentTiltY = mTargetTiltY;
            mVelocityTiltY = 0f;
        }
        if (Math.abs(mTargetLift - mCurrentLift) < 0.001f && Math.abs(mVelocityLift) < 0.005f) {
            mCurrentLift = mTargetLift;
            mVelocityLift = 0f;
        }
        if (Math.abs(mTargetSheen - mCurrentSheen) < 0.001f && Math.abs(mVelocitySheen) < 0.005f) {
            mCurrentSheen = mTargetSheen;
            mVelocitySheen = 0f;
        }

        // Apply 3D transforms to View
        if (mAxis == Axis.Y) {
            setRotationX(mCurrentTiltX);
            setRotationY(mCurrentTurn + mCurrentTiltY);
        } else {
            setRotationX(mCurrentTurn + mCurrentTiltX);
            setRotationY(mCurrentTiltY);
        }
        setScaleX(mCurrentLift);
        setScaleY(mCurrentLift);

        // Update child interactive visibility outside drawing pass
        float normalizedTurn = ((mCurrentTurn % 360f) + 360f) % 360f;
        boolean isBack = normalizedTurn > 90f && normalizedTurn < 270f;
        if (getChildCount() >= 2) {
            View frontView = getChildAt(0);
            View backView = getChildAt(1);
            if (isBack) {
                if (frontView.getVisibility() == View.VISIBLE) frontView.setVisibility(View.INVISIBLE);
                if (backView.getVisibility() != View.VISIBLE) backView.setVisibility(View.VISIBLE);
            } else {
                if (frontView.getVisibility() != View.VISIBLE) frontView.setVisibility(View.VISIBLE);
                if (backView.getVisibility() == View.VISIBLE) backView.setVisibility(View.INVISIBLE);
            }
        }

        boolean settled = mCurrentTurn == mTargetTurn
                && mCurrentTiltX == mTargetTiltX
                && mCurrentTiltY == mTargetTiltY
                && mCurrentLift == mTargetLift
                && mCurrentSheen == mTargetSheen
                && !mIsTouchActive;

        invalidate();

        if (!settled) {
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        } else {
            mIsAnimating = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFrameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(mFrameCallback);
        }
        mIsAnimating = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing & Visual Rendering
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            super.dispatchDraw(canvas);
            return;
        }

        // 1. Draw dynamic 3D drop shadow underneath
        if (mShadowEnabled) {
            float facing = Math.abs((float) Math.cos(Math.toRadians(mCurrentTurn)));
            float spread = 0.08f + 0.92f * facing;
            float shade = 0.10f + 0.90f * facing * facing;
            int shadowAlpha = Math.round(255f * mShadowOpacity * shade);

            mShadowPaint.setColor((shadowAlpha << 24) | (mShadowColor & 0x00FFFFFF));
            if (mAxis == Axis.Y) {
                float shadowInsetX = (getWidth() * (1f - spread)) / 2f;
                mShadowBounds.set(shadowInsetX, 4f * mDensity, getWidth() - shadowInsetX, getHeight() + 8f * mDensity);
            } else {
                float shadowInsetY = (getHeight() * (1f - spread)) / 2f;
                mShadowBounds.set(4f * mDensity, shadowInsetY, getWidth() - 4f * mDensity, getHeight() - shadowInsetY + 8f * mDensity);
            }
            canvas.drawRoundRect(mShadowBounds, mCornerRadius, mCornerRadius, mShadowPaint);
        }

        // 2. Draw card background clipped to rounded corners
        int save = canvas.save();
        mClipPath.reset();
        mClipPath.addRoundRect(mBounds, mCornerRadius, mCornerRadius, Path.Direction.CW);
        canvas.clipPath(mClipPath);

        canvas.drawRoundRect(mBounds, mCornerRadius, mCornerRadius, mBackgroundPaint);

        // 3. Draw Spotlight surface illumination (15-stop Gaussian falloff)
        if (mSpotlightHelper.isVisible()) {
            mSpotlightHelper.drawSurface(canvas, mBounds, mCornerRadius);
        }

        // 4. Backface culling and child rendering
        float normalizedTurn = ((mCurrentTurn % 360f) + 360f) % 360f;
        boolean isBack = normalizedTurn > 90f && normalizedTurn < 270f;

        int childCount = getChildCount();
        if (childCount >= 2) {
            View frontView = getChildAt(0);
            View backView = getChildAt(1);
            drawChild(canvas, isBack ? backView : frontView, getDrawingTime());
        } else {
            super.dispatchDraw(canvas);
        }

        // 5. Draw dynamic specular glare (opacity 0.22 modulated by sheen spring)
        if (mGlareEnabled && mCurrentSheen > 0.005f) {
            float glareRadius = Math.max(getWidth(), getHeight()) * 0.75f;
            int centerAlpha = Math.round(255f * mGlareOpacity * mCurrentSheen * 0.80f);
            int midAlpha = Math.round(255f * mGlareOpacity * mCurrentSheen * 0.20f);
            int[] colors = new int[] {(centerAlpha << 24) | 0x00FFFFFF, (midAlpha << 24) | 0x00FFFFFF, 0x00FFFFFF};
            float[] positions = new float[] {0.0f, 0.30f, 0.65f};
            RadialGradient glareGrad = new RadialGradient(mGlareX, mGlareY, glareRadius, colors, positions, Shader.TileMode.CLAMP);
            mGlarePaint.setShader(glareGrad);
            canvas.drawRoundRect(mBounds, mCornerRadius, mCornerRadius, mGlarePaint);
        }

        // 6. Draw Spotlight distinct edge tracing
        if (mSpotlightHelper.isVisible()) {
            mSpotlightHelper.drawEdge(canvas, mBounds, mCornerRadius);
        }

        canvas.restoreToCount(save);
    }
}
