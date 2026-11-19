package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GUTUBE(greeting): Starry particle canvas effect matching code.html starfieldCanvas.
 * Draws 60 twinkling, gently drifting colored stars and an ambient red nebular dust field.
 */
public class StarfieldView extends View {
    private static final int NUM_STARS = 60;
    private static final int[] STAR_COLORS = {
            0xFFFFFF, // Pure white
            0xFFC3B9, // Soft warm rose
            0xFFDC82, // Golden stardust
            0xFF6E64, // GuTube red
            0xB4EBF5  // Cool starlight cyan
    };

    private static class Star {
        float x;
        float y;
        float radius;
        float baseAlpha;
        float twinkleSpeed;
        float twinklePhase;
        float vx;
        float vy;
        int rgb;
        boolean isSpecial;
    }

    private final List<Star> mStars = new ArrayList<>(NUM_STARS);
    private final Random mRandom = new Random();
    private final float mDensity;
    private final Paint mStarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNebulaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader mNebulaShader;
    private boolean mRunning = false;
    private float mParallaxX = 0f;
    private float mParallaxY = 0f;

    public StarfieldView(@NonNull Context context) {
        this(context, null);
    }

    public StarfieldView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mSparklePaint.setStyle(Paint.Style.STROKE);
        mSparklePaint.setStrokeWidth(0.8f * mDensity);
    }

    public void setParallax(float px, float py) {
        mParallaxX = px;
        mParallaxY = py;
        if (!mRunning && isShown()) {
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        // Subtle ambient nebular dust inside the card
        mNebulaShader = new RadialGradient(
                w * 0.5f, h * 0.35f, w * 0.75f,
                new int[] {0x29D22D2D, 0x106E191E, 0x00000000},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mNebulaPaint.setShader(mNebulaShader);

        initStars(w, h);
    }

    private void initStars(int w, int h) {
        mStars.clear();
        for (int i = 0; i < NUM_STARS; i++) {
            mStars.add(createStar(w, h, true));
        }
    }

    private Star createStar(int w, int h, boolean randomY) {
        Star s = new Star();
        s.x = mRandom.nextFloat() * w;
        s.y = randomY ? mRandom.nextFloat() * h : h + 5 * mDensity;
        s.radius = (mRandom.nextFloat() * 1.5f + 0.6f) * mDensity;
        s.baseAlpha = mRandom.nextFloat() * 0.45f + 0.2f;
        s.twinkleSpeed = mRandom.nextFloat() * 0.03f + 0.015f;
        s.twinklePhase = mRandom.nextFloat() * ((float) Math.PI * 2f);
        s.vx = (mRandom.nextFloat() - 0.5f) * 0.15f * mDensity;
        s.vy = (-mRandom.nextFloat() * 0.28f - 0.08f) * mDensity;
        s.rgb = STAR_COLORS[mRandom.nextInt(STAR_COLORS.length)];
        s.isSpecial = mRandom.nextFloat() < 0.25f;
        return s;
    }

    public void start() {
        if (!mRunning) {
            mRunning = true;
            postInvalidateOnAnimation();
        }
    }

    public void stop() {
        mRunning = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Draw nebular glow
        if (mNebulaShader != null) {
            canvas.drawRect(0, 0, w, h, mNebulaPaint);
        }

        // Draw and update stars
        for (int i = 0; i < mStars.size(); i++) {
            Star s = mStars.get(i);
            s.twinklePhase += s.twinkleSpeed;
            s.x += s.vx;
            s.y += s.vy;

            // Wrap around bounds
            if (s.y < -5 * mDensity) s.y = h + 5 * mDensity;
            if (s.x < -5 * mDensity) s.x = w + 5 * mDensity;
            if (s.x > w + 5 * mDensity) s.x = -5 * mDensity;

            float alpha = Math.max(0.1f, Math.min(0.98f, s.baseAlpha + (float) Math.sin(s.twinklePhase) * 0.38f));
            int a = Math.round(alpha * 255);
            mStarPaint.setColor((a << 24) | s.rgb);
            float drawX = s.x + mParallaxX * (s.radius / mDensity);
            float drawY = s.y + mParallaxY * (s.radius / mDensity);
            canvas.drawCircle(drawX, drawY, s.radius, mStarPaint);

            // Cross sparkle for prominent stars
            if (s.isSpecial && alpha > 0.55f) {
                int sparkleAlpha = Math.round((alpha - 0.2f) * 0.5f * 255);
                mSparklePaint.setColor((sparkleAlpha << 24) | s.rgb);
                float span = s.radius * 2.6f;
                canvas.drawLine(drawX - span, drawY, drawX + span, drawY, mSparklePaint);
                canvas.drawLine(drawX, drawY - span, drawX, drawY + span, mSparklePaint);
            }
        }

        if (mRunning && isShown()) {
            postInvalidateOnAnimation();
        }
    }
}
