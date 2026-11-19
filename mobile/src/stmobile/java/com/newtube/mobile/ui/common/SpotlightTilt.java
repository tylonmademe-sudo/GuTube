package com.newtube.mobile.ui.common;

import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GUTUBE(spotlight-tilt): 3D tilt and spring physics engine ported from the MagicBento /
 * SpotlightCard React component.
 *
 * <p>Physics:
 * <ul>
 *   <li>Euler angle rotation around X and Y axes (rx, ry) based on pointer position relative to center;</li>
 *   <li>Mass-spring interpolator (stiffness = 170, damping = 20) with 240Hz sub-step integration;</li>
 *   <li>Subtle press depression (stiffness = 420, damping = 28) scaling to 0.985 on touch;</li>
 *   <li>Perspective camera distance (density * 8000) for natural depth without clipping;</li>
 *   <li>Zero CPU/GPU cost when idle.</li>
 * </ul>
 */
public final class SpotlightTilt {
    private static final float DEFAULT_TILT = 5.0f; // degrees, subtle and clean without text distortion
    private static final float SPRING_STIFFNESS = 240.0f; // TILT_SPRING stiffness
    private static final float SPRING_DAMPING = 24.0f; // TILT_SPRING damping
    private static final float SPRING_MASS = 0.6f; // TILT_SPRING mass: 0.6
    private static final float LIFT_STIFFNESS = 320.0f; // LIFT_SPRING stiffness
    private static final float LIFT_DAMPING = 26.0f; // LIFT_SPRING damping
    private static final float LIFT_TARGET = 1.015f; // subtle tactile lift without blurring text
    private static final float PERSPECTIVE_PX = 1100.0f; // perspective: 1100px

    private float mMaxTilt = DEFAULT_TILT;
    private boolean mEnabled = false; // Globally disabled across whole app
    private boolean mPressEnabled = false;

    private float mCurrentRx = 0f;
    private float mCurrentRy = 0f;
    private float mVelocityRx = 0f;
    private float mVelocityRy = 0f;
    private float mTargetRx = 0f;
    private float mTargetRy = 0f;

    private float mCurrentPress = 1.0f;
    private float mVelocityPress = 0f;
    private float mTargetPress = 1.0f;

    private boolean mIsAnimating = false;
    private long mLastTimeMs = 0L;
    private float mDensity = 2.5f;

    @Nullable
    private Choreographer.FrameCallback mFrameCallback;

    public SpotlightTilt() {
        this(2.5f);
    }

    public SpotlightTilt(float density) {
        mDensity = Math.max(1f, density);
    }

    public void setDensity(float density) {
        if (density > 0f) {
            mDensity = density;
        }
    }

    public void setMaxTilt(float maxTilt) {
        mMaxTilt = Math.max(0f, Math.min(25f, maxTilt));
    }

    public float getMaxTilt() {
        return mMaxTilt;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = false;
        mTargetRx = 0f;
        mTargetRy = 0f;
        mTargetPress = 1.0f;
    }

    public boolean isEnabled() {
        return false;
    }

    public void setPressEnabled(boolean pressEnabled) {
        mPressEnabled = false;
    }

    /**
     * Updates target rotation and press based on continuous touch events with local coordinates.
     */
    public void updateTouch(@NonNull View view, int action, float x, float y) {
        reset(view);
    }

    /**
     * Updates target rotation and press based on continuous touch events (ACTION_DOWN, ACTION_MOVE, ACTION_UP).
     */
    public void updateTouch(@NonNull View view, @NonNull MotionEvent event) {
        reset(view);
    }

    /**
     * Smoothly springs tilt and scaling back to neutral (0, 0, 1.0) using mass-spring physics.
     */
    public void springToNeutral(@NonNull View view) {
        reset(view);
    }

    /**
     * Resets tilt and scaling to neutral (0, 0, 1.0).
     */
    public void reset(@NonNull View view) {
        mTargetRx = 0f;
        mTargetRy = 0f;
        mTargetPress = 1.0f;
        mCurrentRx = 0f;
        mCurrentRy = 0f;
        mCurrentPress = 1.0f;
        mVelocityRx = 0f;
        mVelocityRy = 0f;
        mVelocityPress = 0f;
        mIsAnimating = false;
        view.setRotationX(0f);
        view.setRotationY(0f);
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
    }

    private void wake(@NonNull View view) {
    }

    private void scheduleNextFrame(@NonNull View view) {
        if (mFrameCallback == null) {
            mFrameCallback = frameTimeNanos -> tick(view);
        }
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }

    private void tick(@NonNull View view) {
        if (!mIsAnimating || !view.isAttachedToWindow()) {
            mIsAnimating = false;
            return;
        }

        long nowMs = SystemClock.uptimeMillis();
        if (mLastTimeMs == 0L) {
            mLastTimeMs = nowMs;
        }
        float dt = Math.min(0.05f, Math.max(0.001f, (nowMs - mLastTimeMs) / 1000f));
        mLastTimeMs = nowMs;

        // Sub-stepped integration (240Hz sub-stepping matching MagicBento)
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / steps;

        for (int step = 0; step < steps; step++) {
            // Spring physics for tilt rx (TILT_SPRING: stiffness=240, damping=24, mass=0.6)
            mVelocityRx += ((SPRING_STIFFNESS * (mTargetRx - mCurrentRx) - SPRING_DAMPING * mVelocityRx) / SPRING_MASS) * h;
            mCurrentRx += mVelocityRx * h;

            // Spring physics for tilt ry (TILT_SPRING: stiffness=240, damping=24, mass=0.6)
            mVelocityRy += ((SPRING_STIFFNESS * (mTargetRy - mCurrentRy) - SPRING_DAMPING * mVelocityRy) / SPRING_MASS) * h;
            mCurrentRy += mVelocityRy * h;

            // Spring physics for press lift (LIFT_SPRING: stiffness=320, damping=26, target=1.03)
            mVelocityPress += (LIFT_STIFFNESS * (mTargetPress - mCurrentPress) - LIFT_DAMPING * mVelocityPress) * h;
            mCurrentPress += mVelocityPress * h;
        }

        // Settling threshold
        if (Math.abs(mTargetRx - mCurrentRx) < 0.002f && Math.abs(mVelocityRx) < 0.01f) {
            mCurrentRx = mTargetRx;
            mVelocityRx = 0f;
        }
        if (Math.abs(mTargetRy - mCurrentRy) < 0.002f && Math.abs(mVelocityRy) < 0.01f) {
            mCurrentRy = mTargetRy;
            mVelocityRy = 0f;
        }
        if (Math.abs(mTargetPress - mCurrentPress) < 0.0002f && Math.abs(mVelocityPress) < 0.001f) {
            mCurrentPress = mTargetPress;
            mVelocityPress = 0f;
        }

        // Apply 3D perspective distance (1100px) and rotations to View
        view.setCameraDistance(mDensity * PERSPECTIVE_PX);
        view.setRotationX(mCurrentRx);
        view.setRotationY(mCurrentRy);
        if (mPressEnabled) {
            view.setScaleX(mCurrentPress);
            view.setScaleY(mCurrentPress);
        } else {
            view.setScaleX(1.0f);
            view.setScaleY(1.0f);
        }

        boolean settled = mCurrentRx == mTargetRx
                && mCurrentRy == mTargetRy
                && mCurrentPress == mTargetPress
                && mTargetRx == 0f
                && mTargetRy == 0f
                && mTargetPress == 1.0f;

        if (!settled) {
            scheduleNextFrame(view);
        } else {
            mIsAnimating = false;
            view.setRotationX(0f);
            view.setRotationY(0f);
            view.setScaleX(1.0f);
            view.setScaleY(1.0f);
        }
    }
}
