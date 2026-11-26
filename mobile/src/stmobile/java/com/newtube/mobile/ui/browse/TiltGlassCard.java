package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassView;
import com.newtube.mobile.ui.common.Haptics;

/**
 * GUTUBE(greeting): High-performance 3D perspective tilt glass card with buttery smooth
 * physics-based interpolation, continuous finger-tracking, and dynamic specular glare.
 */
public class TiltGlassCard extends GlassView {
    private static final float MAX_TILT = 11f; // Responsive 11-degree tilt angle matching code.html maxTilt
    private static final float RESTING_GLARE_ALPHA = 0.12f; // Elegant resting liquid glass specular sheen

    private final Paint mGlarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final Path mClipPath = new Path();

    // Physics targets (where finger is)
    private float mTargetRotX = 0f;
    private float mTargetRotY = 0f;
    private float mTargetScale = 1f;
    private float mTargetGlareX = 0.5f;
    private float mTargetGlareY = 0.5f;
    private float mTargetGlareAlpha = RESTING_GLARE_ALPHA;

    // Current smooth interpolated state
    private float mCurrentRotX = 0f;
    private float mCurrentRotY = 0f;
    private float mCurrentScale = 1f;
    private float mCurrentGlareX = 0.5f;
    private float mCurrentGlareY = 0.5f;
    private float mCurrentGlareAlpha = RESTING_GLARE_ALPHA;

    private boolean mIsTouching = false;
    private boolean mIsPhysicsRunning = false;
    private StarfieldView mStarfield;

    private final Runnable mPhysicsLoop = new Runnable() {
        @Override
        public void run() {
            if (!mIsPhysicsRunning) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                mIsPhysicsRunning = false;
                return;
            }

            // Silky LERP smoothing factors
            float rotFactor = mIsTouching ? 0.30f : 0.12f;
            float scaleFactor = mIsTouching ? 0.22f : 0.12f;
            float glareFactor = mIsTouching ? 0.30f : 0.14f;

            mCurrentRotX += (mTargetRotX - mCurrentRotX) * rotFactor;
            mCurrentRotY += (mTargetRotY - mCurrentRotY) * rotFactor;
            mCurrentScale += (mTargetScale - mCurrentScale) * scaleFactor;
            mCurrentGlareX += (mTargetGlareX - mCurrentGlareX) * glareFactor;
            mCurrentGlareY += (mTargetGlareY - mCurrentGlareY) * glareFactor;
            mCurrentGlareAlpha += (mTargetGlareAlpha - mCurrentGlareAlpha) * glareFactor;

            setRotationX(mCurrentRotX);
            setRotationY(mCurrentRotY);
            setScaleX(mCurrentScale);
            setScaleY(mCurrentScale);

            if (mStarfield == null) {
                mStarfield = findViewById(R.id.starfieldCanvas);
            }
            if (mStarfield != null) {
                mStarfield.setParallax(mCurrentRotY * 1.5f, -mCurrentRotX * 1.5f);
            }

            updateGlareShader(w, h);
            invalidate();

            boolean stillMoving = Math.abs(mTargetRotX - mCurrentRotX) > 0.04f
                    || Math.abs(mTargetRotY - mCurrentRotY) > 0.04f
                    || Math.abs(mTargetScale - mCurrentScale) > 0.001f
                    || Math.abs(mTargetGlareAlpha - mCurrentGlareAlpha) > 0.005f
                    || Math.abs(mTargetGlareX - mCurrentGlareX) > 0.5f
                    || Math.abs(mTargetGlareY - mCurrentGlareY) > 0.5f;

            if (mIsTouching || stillMoving) {
                postOnAnimation(this);
            } else {
                // Settle cleanly to exact resting values
                mCurrentRotX = 0f;
                mCurrentRotY = 0f;
                mCurrentScale = 1f;
                mCurrentGlareAlpha = RESTING_GLARE_ALPHA;
                mCurrentGlareX = w * 0.35f;
                mCurrentGlareY = h * 0.25f;
                setRotationX(0f);
                setRotationY(0f);
                setScaleX(1f);
                setScaleY(1f);
                if (mStarfield != null) {
                    mStarfield.setParallax(0f, 0f);
                }
                updateGlareShader(w, h);
                mIsPhysicsRunning = false;
                invalidate();
            }
        }
    };

    public TiltGlassCard(@NonNull Context context) {
        this(context, null);
    }

    public TiltGlassCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        // Deep 3D perspective distance for natural distortion
        setCameraDistance(density * 9000f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            setOutlineAmbientShadowColor(0x4DFFFFFF);
            setOutlineSpotShadowColor(0x4DFFFFFF);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();
        int maxWidth = 0;
        int maxHeight = 0;
        int childState = 0;
        View starfield = null;

        // Measure foreground content first so StarfieldView never inflates wrap_content height
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (child.getId() == R.id.starfieldCanvas || child instanceof StarfieldView) {
                starfield = child;
                continue;
            }
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            maxWidth = Math.max(maxWidth, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
            maxHeight = Math.max(maxHeight, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
        }

        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());

        int measuredW = resolveSizeAndState(maxWidth, widthMeasureSpec, childState);
        int measuredH = resolveSizeAndState(maxHeight, heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT);
        setMeasuredDimension(measuredW, measuredH);

        if (starfield != null) {
            int starW = Math.max(0, measuredW - getPaddingLeft() - getPaddingRight());
            int starH = Math.max(0, measuredH - getPaddingTop() - getPaddingBottom());
            starfield.measure(
                    MeasureSpec.makeMeasureSpec(starW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(starH, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0, 0, w, h);
        setPivotX(w * 0.5f);
        setPivotY(h * 0.5f);
        mTargetGlareX = w * 0.35f;
        mTargetGlareY = h * 0.25f;
        mCurrentGlareX = w * 0.35f;
        mCurrentGlareY = h * 0.25f;
        mTargetGlareAlpha = RESTING_GLARE_ALPHA;
        mCurrentGlareAlpha = RESTING_GLARE_ALPHA;
        updateGlareShader(w, h);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        float w = getWidth();
        float h = getHeight();

        if (w > 0 && h > 0) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mIsTouching = true;
                    Haptics.tick(this);
                    updateTouchTargets(x, y, w, h);
                    startPhysics();
                    break;

                case MotionEvent.ACTION_MOVE:
                    mIsTouching = true;
                    updateTouchTargets(x, y, w, h);
                    startPhysics();
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mIsTouching = false;
                    mTargetRotX = 0f;
                    mTargetRotY = 0f;
                    mTargetScale = 1f;
                    mTargetGlareX = w * 0.35f;
                    mTargetGlareY = h * 0.25f;
                    mTargetGlareAlpha = RESTING_GLARE_ALPHA;
                    startPhysics();
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void updateTouchTargets(float x, float y, float w, float h) {
        float clampedX = Math.max(0, Math.min(x, w));
        float clampedY = Math.max(0, Math.min(y, h));

        // Normalized offsets from center (-1.0 to 1.0)
        float offsetX = ((clampedX / w) * 2f) - 1f;
        float offsetY = ((clampedY / h) * 2f) - 1f;

        mTargetRotX = -offsetY * MAX_TILT;
        mTargetRotY = offsetX * MAX_TILT;
        mTargetScale = 1.02f;

        mTargetGlareX = clampedX;
        mTargetGlareY = clampedY;
        mTargetGlareAlpha = Math.min(0.42f, 0.14f + (Math.abs(offsetX) + Math.abs(offsetY)) * 0.18f);
    }

    private void startPhysics() {
        if (!mIsPhysicsRunning) {
            mIsPhysicsRunning = true;
            removeCallbacks(mPhysicsLoop);
            postOnAnimation(mPhysicsLoop);
        }
    }

    private void updateGlareShader(float w, float h) {
        float radius = Math.max(w, h) * 0.70f;
        int a1 = Math.round(mCurrentGlareAlpha * 255);
        int a2 = Math.round(mCurrentGlareAlpha * 0.22f * 255);
        mGlarePaint.setShader(new RadialGradient(
                mCurrentGlareX, mCurrentGlareY, radius,
                new int[] {(a1 << 24) | 0xFFFFFF, (a2 << 24) | 0xFFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.45f, 0.85f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);

        // Draw dynamic specular glare light over children
        if (mCurrentGlareAlpha > 0.005f && mGlarePaint.getShader() != null) {
            float r = Math.min(getCornerRadius(), Math.min(getWidth(), getHeight()) / 2f);
            mClipPath.reset();
            mClipPath.addRoundRect(mBounds, r, r, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(mClipPath);
            canvas.drawRect(mBounds, mGlarePaint);
            canvas.restoreToCount(save);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mIsTouching = false;
        mIsPhysicsRunning = false;
        removeCallbacks(mPhysicsLoop);
        super.onDetachedFromWindow();
    }
}
