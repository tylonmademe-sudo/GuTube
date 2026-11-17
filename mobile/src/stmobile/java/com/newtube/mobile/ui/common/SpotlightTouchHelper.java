package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.WeakHashMap;

/**
 * GUTUBE(spotlight-touch-helper): Highly interactive, premium 'Spotlight' touch effect
 * across all interactive elements (buttons, cards, menus), inspired by ColorOS 17 and Linear UI.
 *
 * <p>Specifications:
 * <ul>
 *   <li><b>Universal Dynamic Origin:</b> The spotlight originates exactly at the user's touch
 *       coordinates and smoothly follows their finger during drag gestures;</li>
 *   <li><b>15/16-Stop Gaussian Falloff:</b> Hermite smoothstep combined with a 6th-power Gaussian
 *       envelope (glow = exp(-4.6 * g^2) * (1 - g^6)), ported directly from the reference CSS component;</li>
 *   <li><b>ColorOS 17 Theme Support:</b>
 *     <ul>
 *       <li>Dark Theme: Surface #111111, pure white light (#FFFFFF), fill: 1.0, edge: 1.0, SCREEN blend;</li>
 *       <li>Light Theme: Surface #FFFFFF, charcoal light (#18181B), fill: 0.28, edge: 0.70, SRC_OVER blend.</li>
 *     </ul>
 *   </li>
 *   <li><b>Dynamic Flare & Sizing:</b> Scales radius by 1.2x and brightness by 1.7x on press contact;</li>
 *   <li><b>Fluid Physics:</b> Spring-like lag interpolation (smoothing: 0.3) with natural decay on release.</li>
 * </ul>
 */
public class SpotlightTouchHelper {
    public static final int STOPS_COUNT = 15;
    public static final float[] STOPS = new float[STOPS_COUNT];
    public static final float[] MULTIPLIERS = new float[STOPS_COUNT];

    public static final int FILL_STOPS_COUNT = 16;
    public static final int EDGE_STOPS_COUNT = 15;
    public static final float[] EDGE_POSITIONS = new float[EDGE_STOPS_COUNT];
    public static final float[] EDGE_MULTIPLIERS = new float[EDGE_STOPS_COUNT];

    static {
        // Precompute standard 15-stop Gaussian falloff table (softness = 1.0, core = 0.0)
        for (int i = 0; i < EDGE_STOPS_COUNT; i++) {
            float t = i / 14.0f;
            float g = t;
            double glow = Math.exp(-4.6 * g * g) * (1.0 - Math.pow(g, 6.0));
            EDGE_POSITIONS[i] = t;
            EDGE_MULTIPLIERS[i] = (float) Math.max(0.0, Math.min(1.0, glow));

            // Legacy array compatibility
            STOPS[i] = t;
            MULTIPLIERS[i] = EDGE_MULTIPLIERS[i];
        }
    }

    private static final WeakHashMap<View, SpotlightTouchHelper> sAttachedHelpers = new WeakHashMap<>();

    // Base properties (per ColorOS 17 / CSS specification)
    private int mColor = 0xFFFFFFFF; // Spotlight Color: #FFFFFF (Dark) or #18181B (Light)
    private boolean mCustomColor = false;
    private float mIntensity = 0.15f; // Baseline surface fill intensity: 0.15
    private float mBorderGlow = 0.60f; // Distinct edge perimeter glow: 0.60
    private float mSoftness = 0.70f; // Smoothness / Softness: 0.70
    private float mSmoothing = 0.30f; // Movement Smoothing: 0.30
    private float mCustomSizeDp = -1f; // -1 means dynamically adaptive
    private boolean mFlare = true;
    private boolean mEnabled = true;
    private boolean mDrawEdge = true;
    private boolean mDrawSurface = true;
    private float mDensity = 2.5f;

    // Theme multipliers (Dark: fill=1.0, edge=1.0; Light: fill=0.28, edge=0.70)
    private float mThemeFillMultiplier = 1.0f;
    private float mThemeEdgeMultiplier = 1.0f;

    // Animation & touch state
    private float mCurrentX = 0f;
    private float mCurrentY = 0f;
    private float mTargetX = 0f;
    private float mTargetY = 0f;
    private float mPresence = 0f;
    private float mFlareAmount = 0f;
    private float mTargetPressure = 0.5f;
    private float mCurrentPressure = 0.5f;
    private boolean mPressed = false;
    private boolean mActive = false;
    private long mLastTimeMs = 0L;
    private boolean mIsAnimating = false;

    // Preallocated rendering buffers to prevent GC churn during continuous drag
    private final float[] mFillPositions = new float[FILL_STOPS_COUNT];
    private final float[] mFillMultipliers = new float[FILL_STOPS_COUNT];
    private final int[] mFillColors = new int[FILL_STOPS_COUNT];
    private final int[] mEdgeColors = new int[EDGE_STOPS_COUNT];
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mEdgeBounds = new RectF();

    @Nullable
    private Choreographer.FrameCallback mFrameCallback;

    public SpotlightTouchHelper() {
        this(2.5f);
    }

    public SpotlightTouchHelper(float density) {
        mDensity = Math.max(1f, density);
        mEdgePaint.setStyle(Paint.Style.STROKE);
        mEdgePaint.setStrokeWidth(1.5f * mDensity);

        recomputeFillStops();

        // Default to dark theme screen blend mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mFillPaint.setBlendMode(BlendMode.SCREEN);
            mEdgePaint.setBlendMode(BlendMode.SCREEN);
        } else {
            mFillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            mEdgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        }
    }

    /**
     * Recomputes the 16-stop Hermite + Gaussian falloff table based on current softness.
     */
    private void recomputeFillStops() {
        float soft = Math.max(0f, Math.min(1.0f, mSoftness));
        float core = (float) (Math.pow(1.0f - soft, 1.5) * 0.85);

        mFillPositions[0] = 0.0f;
        mFillMultipliers[0] = 1.0f;

        for (int i = 0; i <= 14; i++) {
            float step = i / 14.0f;
            float t = core + (1.0f - core) * step;
            float g = step;
            float smooth = 1.0f - g * g * (3.0f - 2.0f * g);
            float glow = (float) (Math.exp(-4.6 * g * g) * (1.0 - Math.pow(g, 6.0)));
            float alpha = smooth + (glow - smooth) * soft;

            mFillPositions[i + 1] = t;
            mFillMultipliers[i + 1] = Math.max(0f, Math.min(1.0f, alpha));
        }
    }

    /**
     * Adapts color and blending modes to active Dark/Light theme per specification.
     */
    public void updateTheme(@Nullable Context context) {
        if (context == null) {
            return;
        }
        boolean isLight = ThemeMode.isLight(context);
        if (!mCustomColor) {
            mColor = isLight ? 0xFF18181B : 0xFFFFFFFF;
        }
        mThemeFillMultiplier = isLight ? 0.28f : 1.0f;
        mThemeEdgeMultiplier = isLight ? 0.70f : 1.0f;

        if (isLight) {
            mFillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            mEdgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mFillPaint.setBlendMode(BlendMode.SCREEN);
                mEdgePaint.setBlendMode(BlendMode.SCREEN);
            } else {
                mFillPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
                mEdgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            }
        }
    }

    public SpotlightTouchHelper setColor(int color) {
        mColor = color;
        mCustomColor = true;
        return this;
    }

    public int getColor() {
        return mColor;
    }

    public SpotlightTouchHelper setIntensity(float intensity) {
        mIntensity = Math.max(0f, Math.min(1.0f, intensity));
        return this;
    }

    public float getIntensity() {
        return mIntensity;
    }

    public SpotlightTouchHelper setBorderGlow(float borderGlow) {
        mBorderGlow = Math.max(0f, Math.min(2.0f, borderGlow));
        return this;
    }

    public float getBorderGlow() {
        return mBorderGlow;
    }

    public SpotlightTouchHelper setSoftness(float softness) {
        mSoftness = Math.max(0f, Math.min(1.0f, softness));
        recomputeFillStops();
        return this;
    }

    public float getSoftness() {
        return mSoftness;
    }

    public SpotlightTouchHelper setSmoothing(float smoothing) {
        mSmoothing = Math.max(0f, Math.min(1.0f, smoothing));
        return this;
    }

    public float getSmoothing() {
        return mSmoothing;
    }

    public float getPressure() {
        return mCurrentPressure;
    }

    public SpotlightTouchHelper setPressure(float pressure) {
        mTargetPressure = Math.max(0.1f, Math.min(1.2f, pressure));
        return this;
    }

    public SpotlightTouchHelper setSizeDp(float sizeDp) {
        mCustomSizeDp = sizeDp;
        return this;
    }

    public float getSizeDp() {
        return mCustomSizeDp;
    }

    public SpotlightTouchHelper setFlare(boolean flare) {
        mFlare = flare;
        return this;
    }

    public boolean isFlare() {
        return mFlare;
    }

    public SpotlightTouchHelper setEnabled(boolean enabled) {
        mEnabled = enabled;
        return this;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public SpotlightTouchHelper setDrawEdge(boolean drawEdge) {
        mDrawEdge = drawEdge;
        return this;
    }

    public boolean isDrawEdge() {
        return mDrawEdge;
    }

    public SpotlightTouchHelper setDrawSurface(boolean drawSurface) {
        mDrawSurface = drawSurface;
        return this;
    }

    public boolean isDrawSurface() {
        return mDrawSurface;
    }

    public boolean isVisible() {
        return mEnabled && (mPresence > 0.001f || mActive);
    }

    public boolean isActive() {
        return mActive;
    }

    public float getPresence() {
        return mPresence;
    }

    public float getCurrentX() {
        return mCurrentX;
    }

    public float getCurrentY() {
        return mCurrentY;
    }

    public void setCoordinates(float x, float y) {
        mTargetX = x;
        mTargetY = y;
        if (mPresence < 0.02f) {
            mCurrentX = x;
            mCurrentY = y;
        }
    }

    /**
     * Adaptively computes the optimal spotlight radius based on element dimensions.
     */
    public float getAdaptiveSize(@NonNull RectF bounds) {
        if (mCustomSizeDp > 0f) {
            return Math.max(20f * mDensity, mCustomSizeDp * mDensity);
        }
        float w = bounds.width();
        float h = bounds.height();
        float minDim = Math.min(w, h);
        float maxDim = Math.max(w, h);

        if (minDim <= 0f) {
            return 60f * mDensity;
        }

        float baseSize;
        if (maxDim <= 56f * mDensity) {
            // Icon buttons (e.g. 40-48dp icon buttons, top bar buttons)
            baseSize = maxDim * 1.25f;
        } else if (minDim <= 64f * mDensity && maxDim > 64f * mDensity) {
            // Elongated pills / chips / rows
            baseSize = Math.max(minDim * 1.6f, Math.min(maxDim * 0.75f, 180f * mDensity));
        } else {
            // Cards, sheets, full bottom nav (default 240dp reference size)
            baseSize = Math.max(160f * mDensity, Math.min(maxDim * 0.70f, 260f * mDensity));
        }

        return baseSize;
    }

    /**
     * Wakes up the animation loop and requests redraws until settled.
     */
    public void wake(@NonNull View host) {
        if (!mIsAnimating) {
            mIsAnimating = true;
            mLastTimeMs = SystemClock.uptimeMillis();
            scheduleNextFrame(host);
        }
        host.invalidate();
    }

    private void scheduleNextFrame(@NonNull View host) {
        if (mFrameCallback == null) {
            mFrameCallback = frameTimeNanos -> tick(host);
        }
        Choreographer.getInstance().postFrameCallback(mFrameCallback);
    }

    /**
     * Physics and animation step executed on display refresh (60/120Hz).
     */
    private void tick(@NonNull View host) {
        if (!mIsAnimating || !host.isAttachedToWindow()) {
            mIsAnimating = false;
            return;
        }

        long nowMs = SystemClock.uptimeMillis();
        if (mLastTimeMs == 0L) {
            mLastTimeMs = nowMs;
        }
        float dt = Math.min(0.05f, Math.max(0.001f, (nowMs - mLastTimeMs) / 1000f));
        mLastTimeMs = nowMs;

        float targetX = mTargetX;
        float targetY = mTargetY;
        float targetPresence = mActive ? 1.0f : 0.0f;

        // Snap on fresh touch if light was off
        if (mPresence < 0.02f && targetPresence > 0f) {
            mCurrentX = targetX;
            mCurrentY = targetY;
        }

        // Movement Smoothing: follow = 1 - exp(-dt / (0.015 + smoothing * 0.22))
        float follow = mSmoothing == 0f ? 1f : (1f - (float) Math.exp(-dt / (0.015f + mSmoothing * 0.22f)));
        mCurrentX += (targetX - mCurrentX) * follow;
        mCurrentY += (targetY - mCurrentY) * follow;

        // Presence ease: responsive attack (0.10s), smooth natural decay (0.32s)
        float fade = 1f - (float) Math.exp(-dt / (targetPresence > mPresence ? 0.10f : 0.32f));
        mPresence += (targetPresence - mPresence) * fade;
        if (Math.abs(targetPresence - mPresence) < 0.001f) {
            mPresence = targetPresence;
        }

        // Flare on press: quick flare attack (0.05s), gentle release (0.28s)
        float targetFlare = (mPressed && mFlare) ? 1.0f : 0.0f;
        float flareFade = 1f - (float) Math.exp(-dt / (targetFlare > mFlareAmount ? 0.05f : 0.28f));
        mFlareAmount += (targetFlare - mFlareAmount) * flareFade;
        if (Math.abs(targetFlare - mFlareAmount) < 0.002f) {
            mFlareAmount = targetFlare;
        }

        // Pressure smoothing: smoothly interpolate pressure to prevent touch jitter
        float pressureFollow = 1f - (float) Math.exp(-dt / 0.08f);
        mCurrentPressure += (mTargetPressure - mCurrentPressure) * pressureFollow;

        host.invalidate();

        boolean moving = Math.abs(targetX - mCurrentX) > 0.15f || Math.abs(targetY - mCurrentY) > 0.15f;
        boolean pressureSettling = Math.abs(mTargetPressure - mCurrentPressure) > 0.02f;
        boolean settled = !moving
                && !pressureSettling
                && Math.abs(mPresence - targetPresence) < 0.001f
                && Math.abs(mFlareAmount - targetFlare) < 0.002f
                && !mActive;

        if (!settled) {
            scheduleNextFrame(host);
        } else {
            mIsAnimating = false;
        }
    }

    /**
     * Direct touch processing entry point for high-performance touch routing.
     */
    public void onTouch(@NonNull View view, int action, float x, float y, float pressure) {
        if (!mEnabled || BatterySaver.lite()) {
            return;
        }

        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        updateTheme(view.getContext());

        float clampedX = Math.max(0f, Math.min(width, x));
        float clampedY = Math.max(0f, Math.min(height, y));
        float p = (pressure > 0.01f) ? Math.max(0.1f, Math.min(1.2f, pressure)) : 0.5f;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTargetX = clampedX;
                mTargetY = clampedY;
                mTargetPressure = p;
                if (mPresence < 0.02f) {
                    mCurrentX = clampedX;
                    mCurrentY = clampedY;
                    mCurrentPressure = p;
                }
                mPressed = true;
                mActive = true;
                wake(view);
                break;

            case MotionEvent.ACTION_MOVE:
                mTargetX = clampedX;
                mTargetY = clampedY;
                mTargetPressure = p;
                mActive = true;
                wake(view);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mPressed = false;
                mActive = false;
                mTargetPressure = 0.5f;
                wake(view);
                break;
        }
    }

    /**
     * Interactive Touch & Drag Listener:
     * Tracks MotionEvent.ACTION_DOWN and ACTION_MOVE. The center of the spotlight precisely
     * and smoothly follows the user's finger as they drag across the view.
     */
    public boolean onTouchEvent(@NonNull View view, @NonNull MotionEvent event) {
        onTouch(view, event.getActionMasked(), event.getX(), event.getY(), event.getPressure());
        return false;
    }

    public void release(@NonNull View view) {
        onTouch(view, MotionEvent.ACTION_UP, mCurrentX, mCurrentY, 0.5f);
    }

    /**
     * Draws the soft radial surface light onto the given canvas area, strictly clipped to bounds.
     */
    public void drawSurface(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
        if (!mEnabled || !mDrawSurface || mPresence <= 0.001f || mIntensity <= 0f || bounds.isEmpty()) {
            return;
        }

        float clampedPressure = Math.max(0.1f, Math.min(1.0f, mCurrentPressure));
        float pressureFactor = 0.70f + 0.58f * clampedPressure;
        float pressureSizeFactor = 0.94f + 0.12f * clampedPressure;

        // Size scaling on press flare (s.size * (1 + 0.2 * flare))
        float size = getAdaptiveSize(bounds) * (1.0f + 0.20f * mFlareAmount) * pressureSizeFactor;
        // Boost scaling on press flare (1 + 0.7 * flare)
        float boost = 1.0f + 0.70f * mFlareAmount;
        float peakAlpha = Math.min(1.0f, mPresence * mIntensity * boost * mThemeFillMultiplier * pressureFactor);
        if (peakAlpha <= 0.001f) {
            return;
        }

        int baseRgb = mColor & 0x00FFFFFF;
        for (int i = 0; i < FILL_STOPS_COUNT; i++) {
            int a = Math.round(255f * peakAlpha * mFillMultipliers[i]);
            mFillColors[i] = (a << 24) | baseRgb;
        }

        mFillPaint.setShader(new RadialGradient(mCurrentX, mCurrentY, size, mFillColors, mFillPositions, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, mFillPaint);
    }

    /**
     * Draws the distinct edge tracing the border of the view (softness = 1.0, rimSize = size * 0.6).
     */
    public void drawEdge(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
        if (!mEnabled || !mDrawEdge || mPresence <= 0.001f || mBorderGlow <= 0f || bounds.isEmpty()) {
            return;
        }

        float clampedPressure = Math.max(0.1f, Math.min(1.0f, mCurrentPressure));
        float pressureFactor = 0.70f + 0.58f * clampedPressure;
        float pressureSizeFactor = 0.94f + 0.12f * clampedPressure;

        // Edge rim radius is size * 0.6 per CSS specification
        float size = getAdaptiveSize(bounds) * 0.60f * (1.0f + 0.20f * mFlareAmount) * pressureSizeFactor;
        float boost = 1.0f + 0.70f * mFlareAmount;
        float edgePeak = Math.min(1.0f, mPresence * mBorderGlow * boost * mThemeEdgeMultiplier * pressureFactor);
        if (edgePeak <= 0.001f) {
            return;
        }

        int baseRgb = mColor & 0x00FFFFFF;
        for (int i = 0; i < EDGE_STOPS_COUNT; i++) {
            int a = Math.round(255f * edgePeak * EDGE_MULTIPLIERS[i]);
            mEdgeColors[i] = (a << 24) | baseRgb;
        }

        mEdgePaint.setShader(new RadialGradient(mCurrentX, mCurrentY, size, mEdgeColors, EDGE_POSITIONS, Shader.TileMode.CLAMP));

        float halfStroke = mEdgePaint.getStrokeWidth() / 2f;
        mEdgeBounds.set(bounds.left + halfStroke, bounds.top + halfStroke, bounds.right - halfStroke, bounds.bottom - halfStroke);
        float edgeRadius = Math.max(0f, cornerRadius - halfStroke);

        canvas.drawRoundRect(mEdgeBounds, edgeRadius, edgeRadius, mEdgePaint);
    }

    /**
     * Draws both the surface light and the edge rim glow with strict boundary clipping.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
        drawSurface(canvas, bounds, cornerRadius);
        drawEdge(canvas, bounds, cornerRadius);
    }

    // =========================================================================
    // Global Attachment Helper Engine
    // =========================================================================

    /**
     * Attaches SpotlightTouchHelper to ANY View (buttons, cards, menus) with ColorOS 17 defaults.
     */
    @NonNull
    public static SpotlightTouchHelper attach(@NonNull View view) {
        return attach(view, 0xFFFFFFFF, -1f, -1f);
    }

    /**
     * Attaches SpotlightTouchHelper with explicit corner radius in dp.
     */
    @NonNull
    public static SpotlightTouchHelper attach(@NonNull View view, float cornerRadiusDp) {
        return attach(view, 0xFFFFFFFF, -1f, cornerRadiusDp);
    }

    /**
     * Attaches SpotlightTouchHelper with custom color, size and corner radius.
     */
    @NonNull
    public static SpotlightTouchHelper attach(@NonNull View view, int color, float sizeDp, float cornerRadiusDp) {
        SpotlightTouchHelper existing = sAttachedHelpers.get(view);
        if (existing != null) {
            existing.setColor(color);
            if (sizeDp > 0f) existing.setSizeDp(sizeDp);
            return existing;
        }

        float density = view.getResources().getDisplayMetrics().density;
        SpotlightTouchHelper helper = new SpotlightTouchHelper(density);
        helper.setColor(color);
        if (sizeDp > 0f) helper.setSizeDp(sizeDp);
        helper.updateTheme(view.getContext());

        sAttachedHelpers.put(view, helper);

        // Add overlay drawable
        SpotlightOverlayDrawable overlay = new SpotlightOverlayDrawable(view, helper);
        if (cornerRadiusDp >= 0f) {
            overlay.setCornerRadius(cornerRadiusDp * density);
        }
        view.getOverlay().add(overlay);

        return helper;
    }

    public static void register(@NonNull View view, @NonNull SpotlightTouchHelper helper) {
        sAttachedHelpers.put(view, helper);
    }

    @Nullable
    public static SpotlightTouchHelper get(@NonNull View view) {
        SpotlightTouchHelper helper = sAttachedHelpers.get(view);
        if (helper != null) {
            return helper;
        }
        return SpotlightHelper.getSpotlight(view);
    }

    /**
     * Computes adaptive corner radius based on view geometry.
     */
    public static float getAdaptiveCornerRadius(@NonNull View view, @NonNull RectF bounds) {
        float minDim = Math.min(bounds.width(), bounds.height());
        if (minDim <= 0f) {
            return 12f * view.getResources().getDisplayMetrics().density;
        }
        if (Math.abs(bounds.width() - bounds.height()) <= 6f * view.getResources().getDisplayMetrics().density
                || bounds.height() <= 46f * view.getResources().getDisplayMetrics().density) {
            return bounds.height() / 2f;
        }
        return Math.min(16f * view.getResources().getDisplayMetrics().density, minDim / 2f);
    }
}
