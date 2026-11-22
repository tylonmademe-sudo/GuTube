package com.newtube.mobile.ui.search;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;

/**
 * GUTUBE(search glow): opening Search lights the screen's edges in Siri's colours - a glow that
 * turns once around the display and fades, the way Apple Intelligence's Siri wakes. Drawn over the
 * whole window (the decor, not the inset content) and removed when it is done.
 */
public final class SiriGlow extends View {
    private static final String PREFS = "gutube_app";
    private static final String KEY_ON = "search_glow";
    private static final int[] SIRI = {0xFFFF6A88, 0xFFFFB86B, 0xFFB45CFF, 0xFF5E9BFF, 0xFF4FD8FF, 0xFFFF6A88};
    private static final long DURATION_MS = 1400;
    private static int sOn = -1;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mRotation = new Matrix();
    private final RectF mBounds = new RectF();
    private final float mDensity;
    private SweepGradient mSweep;
    private float mCorner;
    private float mTurn;

    public static boolean enabled(Context context) {
        return false;
    }

    public static void setEnabled(Context context, boolean on) {
    }

    /** Play once over {@code activity}'s window. */
    static void play(Activity activity) {
        // Completely removed / disabled: zero screen edge glow animation
    }

    private SiriGlow(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(3f * mDensity);
        mHalo.setStyle(Paint.Style.STROKE);
        mHalo.setStrokeWidth(14f * mDensity);
        mHalo.setAlpha(170);
        mHalo.setMaskFilter(new BlurMaskFilter(12f * mDensity, BlurMaskFilter.Blur.NORMAL));
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mSweep = new SweepGradient(w / 2f, h / 2f, SIRI, null);
        mLine.setShader(mSweep);
        mHalo.setShader(mSweep);
        mCorner = 28f * mDensity; // the display's own corner radius where Android reports it (12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowInsets insets = getRootWindowInsets();
            RoundedCorner corner = insets != null ? insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) : null;
            if (corner != null && corner.getRadius() > 0) {
                mCorner = corner.getRadius();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSweep == null) {
            return;
        }
        mRotation.setRotate(mTurn, getWidth() / 2f, getHeight() / 2f);
        mSweep.setLocalMatrix(mRotation);
        float inset = mLine.getStrokeWidth() / 2f;
        mBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(mBounds, mCorner, mCorner, mHalo);
        canvas.drawRoundRect(mBounds, mCorner, mCorner, mLine);
    }
}
