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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.tv.R;

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
    private float mIntensity = 0.06f; // Globally subtle surface fill intensity: 0.06
    private float mBorderGlow = 0.0f; // UNIVERSAL RULE: ZERO EXTRA BORDERS ANYWHERE (no border stroke)
    private float mSoftness = 0.70f; // Smoothness / Softness: 0.70
    private float mSmoothing = 0.30f; // Movement Smoothing: 0.30
    private float mCustomSizeDp = -1f; // -1 means dynamically adaptive
    private boolean mFlare = false;
    private boolean mEnabled = false; // Globally disabled across whole app
    private boolean mDrawEdge = false;
    private boolean mDrawSurface = false;
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
        mEnabled = false;
        return this;
    }

    public boolean isEnabled() {
        return false;
    }

    public SpotlightTouchHelper setDrawEdge(boolean drawEdge) {
        mDrawEdge = false;
        return this;
    }

    public boolean isDrawEdge() {
        return false;
    }

    public SpotlightTouchHelper setDrawSurface(boolean drawSurface) {
        mDrawSurface = false;
        return this;
    }

    public boolean isDrawSurface() {
        return false;
    }

    public boolean isVisible() {
        return false;
    }

    public boolean isActive() {
        return false;
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
     * Guarantees full coverage of compound buttons (e.g. Timer button with text and icon)
     * so the light reaches all corners and icons without dead zones.
     */
    public float getAdaptiveSize(@NonNull RectF bounds) {
        if (mCustomSizeDp > 0f) {
            return Math.max(30f * mDensity, mCustomSizeDp * mDensity);
        }
        float w = bounds.width();
        float h = bounds.height();
        float minDim = Math.min(w, h);
        float maxDim = Math.max(w, h);

        if (minDim <= 0f) {
            return 80f * mDensity;
        }

        float diagonal = (float) Math.hypot(w, h);
        float baseSize;
        if (maxDim <= 56f * mDensity) {
            // Icon buttons (e.g. 40-48dp icon buttons, top bar buttons):
            // Ensure glow covers full button bounds and extends beyond corners
            baseSize = Math.max(88f * mDensity, diagonal * 1.35f);
        } else if (minDim <= 72f * mDensity && maxDim > 56f * mDensity) {
            // Elongated pills / chips / buttons (like Timer button 230x52dp, greeting shortcuts):
            // Radius MUST exceed the full diagonal so touching any end illuminates the opposite end & icon
            baseSize = Math.max(180f * mDensity, diagonal * 1.15f);
        } else {
            // Cards, sheets, dialog surfaces
            baseSize = Math.max(260f * mDensity, Math.max(maxDim * 0.85f, diagonal * 0.75f));
        }

        return baseSize;
    }

    /**
     * Wakes up the animation loop and requests redraws until settled.
     */
    public void wake(@NonNull View host) {
        // Disabled across whole app
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
        // Disabled across whole app
    }

    public boolean onTouchEvent(@NonNull View view, @NonNull MotionEvent event) {
        return false;
    }

    public void release(@NonNull View view) {
    }

    public void drawSurface(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
    }

    public void drawEdge(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
    }

    public void draw(@NonNull Canvas canvas, @NonNull RectF bounds, float cornerRadius) {
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
        float density = view.getResources() != null ? view.getResources().getDisplayMetrics().density : 2.5f;
        SpotlightTouchHelper helper = new SpotlightTouchHelper(density);
        helper.setEnabled(false);
        return helper;
    }

    public static void detach(@NonNull View view) {
        SpotlightTouchHelper helper = sAttachedHelpers.remove(view);
        if (helper != null) {
            helper.setEnabled(false);
            helper.setDrawSurface(false);
            helper.setDrawEdge(false);
        }
        Drawable bg = view.getBackground();
        if (bg instanceof SpotlightBackgroundDrawable) {
            view.setBackground(((SpotlightBackgroundDrawable) bg).getOriginalBackground());
        }
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
