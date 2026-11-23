package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * GUTUBE(ambient): YouTube's ambient mode.
 * Downscaled to a tiny buffer (32x18) on a background thread with triple buffering, per-pixel temporal EMA smoothing,
 * synced 220ms sampling / 200ms crossfade, and zero-dip layering.
 * Automatically pauses sampling when playback is paused or inactive to save 100% battery.
 */
public final class AmbientGlow implements Runnable {
    private static final int FRAME_W = 32;
    private static final int FRAME_H = 18;
    private static final int BLUR_RADIUS = 2;
    private static final long INTERVAL_MS = 220; // 220ms sampling interval
    private static final long FADE_MS = 200; // 200ms crossfade finishes before next sample
    private static final float GLOW_BRIGHTNESS = 0.82f; // ~210/255 brightness baked into ColorMatrix

    private static final Interpolator INTERPOLATOR = new PathInterpolator(0f, 0f, 0.2f, 1f); // LinearOutSlowIn

    private static final String PREFS = "gutube_player";
    private static final String KEY_ON = "ambient_mode";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final View mHost;
    private final TextureView mVideo;
    private final GlowDrawable mDrawable;
    private final FillDrawable mFill = new FillDrawable();
    private final BarDrawable mBar = new BarDrawable();
    @Nullable
    private View mBarHost;
    @Nullable
    private View mFillHost;
    private boolean mRunning;
    private boolean mPlaying;
    @Nullable
    private BooleanSupplier mPlayingSupplier;

    // Pre-allocated capture and triple-buffered display Bitmaps (zero GC churn, zero drawing overwrite)
    private final Bitmap mCaptureBitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888);
    private final Bitmap[] mBuffers = new Bitmap[] {
            Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
    };
    private int mBufferIndex = 0;

    private final int[] mRawPixels = new int[FRAME_W * FRAME_H];
    private final int[] mBlurredPixels = new int[FRAME_W * FRAME_H];
    private final int[] mBlurTemp = new int[FRAME_W * FRAME_H];
    private final int[] mSmoothedPixels = new int[FRAME_W * FRAME_H];
    private boolean mHasSmoothed = false;
    private final AtomicBoolean mProcessing = new AtomicBoolean(false);

    /** Until the user sets it: on in the dark theme, off in the light one (a glow on a white page). */
    public static boolean isOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON,
                !com.newtube.mobile.ui.common.ThemeMode.isLight(context));
    }

    public static void setOn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    AmbientGlow(@NonNull View host, @NonNull TextureView video, int pageColor) {
        this(host, video, pageColor, null);
    }

    AmbientGlow(@NonNull View host, @NonNull TextureView video, int pageColor, @Nullable BooleanSupplier isPlayingSupplier) {
        mHost = host;
        mVideo = video;
        mPlayingSupplier = isPlayingSupplier;
        mDrawable = new GlowDrawable(pageColor, host.getResources().getDisplayMetrics().density);
        host.setBackground(mDrawable);
    }

    public void setPlayingSupplier(@Nullable BooleanSupplier supplier) {
        mPlayingSupplier = supplier;
    }

    public void setPlaying(boolean playing) {
        if (mPlaying == playing) {
            return;
        }
        mPlaying = playing;
        if (playing) {
            if (mRunning) {
                mHost.removeCallbacks(this);
                mHost.post(this);
            }
        } else {
            mHost.removeCallbacks(this);
        }
    }

    private boolean isPlaybackActive() {
        if (mPlayingSupplier != null) {
            return mPlayingSupplier.getAsBoolean();
        }
        return mPlaying;
    }

    void start() {
        if (!mRunning) {
            mRunning = true;
            mHost.post(this);
        }
    }

    void stop() {
        mRunning = false;
        mHasSmoothed = false;
        mHost.removeCallbacks(this);
    }

    /** {@code area} (the video box) gets the ambient fill as its background; null gives it back black. */
    void setFill(@Nullable View area) {
        if (area == mFillHost) {
            return;
        }
        if (mFillHost != null) {
            mFillHost.setBackgroundColor(Color.BLACK);
        }
        mFillHost = area;
        if (area != null) {
            area.setBackground(mFill);
            if (mRunning && isPlaybackActive()) {
                mHost.removeCallbacks(this);
                mHost.post(this); // a frame now
            }
        }
    }

    /**
     * {@code content} (the page under the status bar, padded clear of it) paints its top padding
     * with the video's top edge; null takes it off. Nothing shows where the page has no top padding
     * (sideways, vertical fullscreen, Shorts: the video runs under the bar there).
     */
    void setBar(@Nullable View content) {
        if (mBarHost != null) {
            mBarHost.setBackground(null);
        }
        mBarHost = content;
        if (content != null) {
            content.setBackground(mBar);
        }
    }

    @Override
    public void run() {
        if (!mRunning) {
            return;
        }
        if (!isPlaybackActive()) {
            // Video is paused, in background, or stopped: pause sampling to save 100% battery
            return;
        }
        boolean fill = mFillHost != null && mFillHost.isShown();
        boolean hostShown = mHost.isShown();
        if ((hostShown || fill) && mVideo.isAvailable()) {
            sampleFrame();
        }
        mHost.postDelayed(this, INTERVAL_MS);
    }

    private void sampleFrame() {
        if (!mVideo.isAvailable()) {
            return;
        }

        Bitmap captured;
        try {
            captured = mVideo.getBitmap(mCaptureBitmap);
        } catch (Throwable t) {
            captured = null;
        }

        if (captured == null) {
            return;
        }

        if (!mProcessing.compareAndSet(false, true)) {
            return; // Previous background computation still running, drop this frame without lag
        }

        captured.getPixels(mRawPixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H);

        EXECUTOR.execute(() -> {
            try {
                // Two-pass box blur in pre-allocated arrays
                System.arraycopy(mRawPixels, 0, mBlurredPixels, 0, mRawPixels.length);
                boxPass(mBlurredPixels, mBlurTemp, FRAME_W, FRAME_H, 1, FRAME_W, BLUR_RADIUS);
                boxPass(mBlurTemp, mBlurredPixels, FRAME_H, FRAME_W, FRAME_W, 1, BLUR_RADIUS);
                boxPass(mBlurredPixels, mBlurTemp, FRAME_W, FRAME_H, 1, FRAME_W, BLUR_RADIUS);
                boxPass(mBlurTemp, mBlurredPixels, FRAME_H, FRAME_W, FRAME_W, 1, BLUR_RADIUS);

                // Per-pixel temporal EMA smoothing: out = 0.40f * new + 0.60f * prev
                if (!mHasSmoothed) {
                    System.arraycopy(mBlurredPixels, 0, mSmoothedPixels, 0, mBlurredPixels.length);
                    mHasSmoothed = true;
                } else {
                    for (int i = 0; i < mBlurredPixels.length; i++) {
                        int newPix = mBlurredPixels[i];
                        int prevPix = mSmoothedPixels[i];

                        int newR = (newPix >> 16) & 0xFF;
                        int newG = (newPix >> 8) & 0xFF;
                        int newB = newPix & 0xFF;

                        int prevR = (prevPix >> 16) & 0xFF;
                        int prevG = (prevPix >> 8) & 0xFF;
                        int prevB = prevPix & 0xFF;

                        int outR = Math.round(0.40f * newR + 0.60f * prevR);
                        int outG = Math.round(0.40f * newG + 0.60f * prevG);
                        int outB = Math.round(0.40f * newB + 0.60f * prevB);

                        mSmoothedPixels[i] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
                    }
                }

                // Write into triple-buffered ring target (never conflicts with currently drawn frame)
                Bitmap targetBuffer = mBuffers[mBufferIndex];
                mBufferIndex = (mBufferIndex + 1) % 3;
                targetBuffer.setPixels(mSmoothedPixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H);

                mHost.post(() -> {
                    if (mRunning) {
                        mDrawable.push(targetBuffer);
                        mFill.push(targetBuffer);
                        mBar.push(targetBuffer);
                    }
                });
            } catch (Throwable ignored) {
            } finally {
                mProcessing.set(false);
            }
        });
    }

    /** GUTUBE(playlist page): shared tiny-bitmap box blur for ambient video frames and playlist covers. */
    public static void blur(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boxPass(px, tmp, w, h, 1, w, 3); // horizontal
            boxPass(tmp, px, h, w, w, 1, 3); // vertical
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Blurs `lines` lines of `len` pixels; `step` walks a line, `stride` jumps between lines. */
    private static void boxPass(int[] src, int[] dst, int len, int lines, int step, int stride, int radius) {
        for (int line = 0; line < lines; line++) {
            int base = line * stride;
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - radius); k <= Math.min(len - 1, i + radius); k++) {
                    int c = src[base + k * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                dst[base + i * step] = 0xFF000000 | (r / n << 16) | (g / n << 8) | (b / n);
            }
        }
    }

    /** The fill: the frame stretched over the whole video box, dimmed, under the picture. */
    private static final class FillDrawable extends Drawable {
        private final Paint mFrame = new Paint(Paint.FILTER_BITMAP_FLAG);
        @Nullable private Bitmap mCurrent;
        @Nullable private Bitmap mPrevious;
        private float mMix = 1f;
        private ValueAnimator mFadeIn;

        FillDrawable() {
            ColorMatrix look = new ColorMatrix();
            look.setSaturation(1.5f);
            ColorMatrix dim = new ColorMatrix();
            dim.setScale(0.6f, 0.6f, 0.6f, 1f);
            look.postConcat(dim);
            mFrame.setColorFilter(new ColorMatrixColorFilter(look));
        }

        void push(Bitmap frame) {
            mPrevious = mCurrent;
            mCurrent = frame;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mFadeIn = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
            mFadeIn.setInterpolator(INTERPOLATOR);
            mFadeIn.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mFadeIn.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int save = canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawColor(Color.BLACK);
            if (mCurrent != null) {
                if (mPrevious != null && mMix < 1f) {
                    mFrame.setAlpha(255);
                    canvas.drawBitmap(mPrevious, null, getBounds(), mFrame);
                }
                mFrame.setAlpha(mMix >= 1f ? 255 : Math.round(255 * mMix));
                canvas.drawBitmap(mCurrent, null, getBounds(), mFrame);
            }
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** The status bar band: the frame's top rows stretched across the host's top padding. */
    private final class BarDrawable extends Drawable {
        private final Paint mFrame = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect mSource = new Rect();
        private final Rect mBand = new Rect();
        @Nullable private Bitmap mCurrent;
        @Nullable private Bitmap mPrevious;
        private float mMix = 1f;
        private ValueAnimator mFadeIn;
        private int mAlpha = 255; // the swipe down to the mini player fades it with the page

        BarDrawable() {
            ColorMatrix look = new ColorMatrix();
            look.setSaturation(1.6f); // the glow under the video's look, so the two read as one
            ColorMatrix dim = new ColorMatrix();
            dim.setScale(GLOW_BRIGHTNESS, GLOW_BRIGHTNESS, GLOW_BRIGHTNESS, 1f);
            look.postConcat(dim);
            mFrame.setColorFilter(new ColorMatrixColorFilter(look));
        }

        void push(Bitmap frame) {
            mPrevious = mCurrent;
            mCurrent = frame;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mFadeIn = ValueAnimator.ofFloat(0f, 1f).setDuration(mPrevious == null ? FADE_MS / 2 : FADE_MS);
            mFadeIn.setInterpolator(INTERPOLATOR);
            mFadeIn.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mFadeIn.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mAlpha == 0) {
                return;
            }
            canvas.drawColor(mAlpha << 24);
            int top = mBarHost != null ? mBarHost.getPaddingTop() : 0;
            if (mCurrent == null || top <= 0) {
                return;
            }
            Rect bounds = getBounds();
            mBand.set(bounds.left, bounds.top, bounds.right, bounds.top + top);
            mSource.set(0, 0, FRAME_W, FRAME_H / 4); // the top quarter: what the bar sits on
            if (mPrevious != null && mMix < 1f) {
                mFrame.setAlpha(mAlpha);
                canvas.drawBitmap(mPrevious, mSource, mBand, mFrame);
            }
            mFrame.setAlpha(mMix >= 1f ? mAlpha : Math.round(mAlpha * mMix));
            canvas.drawBitmap(mCurrent, mSource, mBand, mFrame);
        }

        @Override
        public void setAlpha(int alpha) {
            if (mAlpha != alpha) {
                mAlpha = alpha;
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The glow: the frame scaled to the page's width, faded out into the page colour below. */
    private static final class GlowDrawable extends Drawable {
        private final Paint mFrame = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mFade = new Paint();
        private final Rect mFrameRect = new Rect();
        private final int mPageColor;
        private final float mDensity;
        @Nullable private Bitmap mCurrent;
        @Nullable private Bitmap mPrevious;
        private float mMix = 1f;
        private ValueAnimator mFadeIn;

        GlowDrawable(int pageColor, float density) {
            mPageColor = pageColor;
            mDensity = density;
            ColorMatrix look = new ColorMatrix();
            look.setSaturation(1.6f);
            ColorMatrix dim = new ColorMatrix();
            dim.setScale(GLOW_BRIGHTNESS, GLOW_BRIGHTNESS, GLOW_BRIGHTNESS, 1f);
            look.postConcat(dim);
            mFrame.setColorFilter(new ColorMatrixColorFilter(look));
        }

        void push(Bitmap frame) {
            mPrevious = mCurrent;
            mCurrent = frame;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mFadeIn = ValueAnimator.ofFloat(0f, 1f).setDuration(mPrevious == null ? FADE_MS / 2 : FADE_MS);
            mFadeIn.setInterpolator(INTERPOLATOR);
            mFadeIn.addUpdateListener(a -> {
                mMix = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            mFadeIn.start();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            // The glow reaches ~420dp down (the design's 620px at 390 wide is ~1.6 widths, capped).
            int height = Math.min(Math.round(bounds.width() * 1.1f), Math.round(420 * mDensity));
            mFrameRect.set(bounds.left - Math.round(30 * mDensity), bounds.top,
                    bounds.right + Math.round(30 * mDensity), bounds.top + height);
            int page = mPageColor & 0x00FFFFFF;
            mFade.setShader(new LinearGradient(0, mFrameRect.top, 0, mFrameRect.bottom,
                    new int[] {page, page | 0x59000000, mPageColor},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawColor(mPageColor);
            if (mCurrent == null) {
                return;
            }
            canvas.save();
            canvas.clipRect(getBounds());
            if (mPrevious != null && mMix < 1f) {
                mFrame.setAlpha(255);
                canvas.drawBitmap(mPrevious, null, mFrameRect, mFrame);
            }
            mFrame.setAlpha(mMix >= 1f ? 255 : Math.round(255 * mMix));
            canvas.drawBitmap(mCurrent, null, mFrameRect, mFrame);
            canvas.drawRect(mFrameRect, mFade);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
