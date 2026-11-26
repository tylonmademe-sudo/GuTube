package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Random;

/**
 * GUTUBE(glass): iOS-style liquid glass over a {@link GlassSource} - BitChord's floating bar,
 * ported from Compose to plain RenderNodes, with its numbers:
 *
 * <ol>
 *   <li>API 31+: the source's live RenderNode, saturated 1.5x and lightly blurred (8dp), so what
 *       is under the glass stays readable and the lens below visibly bends it;</li>
 *   <li>API 33+: Kyant's backdrop lens - a circle-profile refraction 24dp deep along the rim with
 *       a depth bulge and seven-band chromatic dispersion;</li>
 *   <li>a #121212 tint at 40%, fine grain, and the specular rim: white at 50%, additive, lit along
 *       a 45 degree normal.</li>
 * </ol>
 *
 * Below API 31 there is nothing to sample, so the glass falls back to a near-opaque tint.
 * Children draw on top, unrefracted. Wire it with {@link #setSource}.
 */
public class GlassView extends FrameLayout {
    /** How far past an edge {@link #setBlurReach} looks: the blur's whole kernel. */
    public static final float BLUR_REACH_DP = 2 * LiquidGlassEngine.BLUR_CHROME_DP;

    private final float mCorner;
    private int mTint;
    /** Liquid: lens and specular rim (floating pills). Off: a flat frosted bar (top bar). */
    private boolean mLiquid;
    private final float mDensity;
    private final Paint mTintPaint = new Paint();
    private final Paint mNoisePaint = new Paint();
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mStrokeColor;
    private float mStrokeWidth;
    private final RectF mRimRect = new RectF();
    private final Path mClipPath = new Path();
    private final Spotlight mSpotlight;
    private final SpotlightTilt mTilt;
    private final int[] mLoc = new int[2];
    private final int[] mSourceLoc = new int[2];
    private int mRecordedX = Integer.MIN_VALUE;
    private int mRecordedY = Integer.MIN_VALUE;
    private int mEffectW;
    private int mEffectH;
    private int mReachTop;
    private int mReachBottom;

    @Nullable private GlassSource mSource;
    /** Sits on glass already (glassOnGlass): its tint is drawn as given, see-through. */
    private boolean mOnGlass;
    private float mBlurDp = LiquidGlassEngine.BLUR_CHROME_DP;
    /** The widest it grows (glassMaxWidth), 0: no limit. */
    private final int mMaxWidth;
    /** The frost fades out over this much of the bottom (glassFadeBottom), 0: a hard edge. */
    private final int mFadeBottom;
    private final Paint mFadePaint = new Paint();
    @Nullable private RenderNode mNode;

    private final ViewTreeObserver.OnPreDrawListener mTrackPosition = () -> {
        if (mSource != null && hasMoved()) {
            invalidate(); // re-record at the new offset; content changes need no help
        }
        return true;
    };

    public GlassView(@NonNull Context context) {
        this(context, null);
    }

    public GlassView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassView);
        mCorner = a.getDimension(R.styleable.GlassView_glassCornerRadius, 24 * mDensity);
        mTint = a.getColor(R.styleable.GlassView_glassTint, 0x66121212);
        mLiquid = a.getBoolean(R.styleable.GlassView_glassLiquid, true) && Glass.isLiquid(context);
        mOnGlass = a.getBoolean(R.styleable.GlassView_glassOnGlass, false);
        mMaxWidth = a.getDimensionPixelSize(R.styleable.GlassView_glassMaxWidth, 0);
        mFadeBottom = a.getDimensionPixelSize(R.styleable.GlassView_glassFadeBottom, 0);
        mStrokeColor = a.getColor(R.styleable.GlassView_glassStrokeColor, 0);
        mStrokeWidth = a.getDimension(R.styleable.GlassView_glassStrokeWidth, 1f * mDensity);
        a.recycle();
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(mStrokeWidth);
        mBorderPaint.setColor(mStrokeColor);

        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius(view.getWidth(), view.getHeight()));
            }
        });
        setClipToOutline(true);

        mNoisePaint.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoisePaint.setAlpha(LiquidGlassEngine.NOISE_ALPHA_VIEW);
        LiquidGlassEngine.configureRimPaint(mRimPaint, mDensity);
        mSpotlight = new Spotlight(mDensity);
        mSpotlight.setEnabled(false);
        mSpotlight.setDrawSurface(false);
        mSpotlight.setDrawEdge(false);
        mTilt = new SpotlightTilt(mDensity);
        mTilt.setEnabled(false);

        // Liquid glass shadow: rgba(255, 255, 255, 0.3) outer specular glow with natural elevation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setOutlineAmbientShadowColor(0x4DFFFFFF);
            setOutlineSpotShadowColor(0x4DFFFFFF);
        }
    }

    /**
     * GUTUBE(live chat, issue #32): this glass's specular rim alone, as a foreground for a rounded
     * card that is not a GlassView (the landscape chat and comments cards).
     */
    public static Drawable rim(float cornerPx, float density) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LiquidGlassEngine.configureRimPaint(paint, density);
        RectF rect = new RectF();
        return new Drawable() {
            @Override
            protected void onBoundsChange(@NonNull Rect bounds) {
                rect.set(bounds);
                paint.setShader(bounds.isEmpty() ? null
                        : LiquidGlassEngine.createRimShader(bounds.width(), bounds.height(), true));
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                if (paint.getShader() != null) {
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint);
                }
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(@Nullable ColorFilter filter) {
                paint.setColorFilter(filter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /**
     * Flat glass only (the lens is laid out on the view's own size): also blur this much of the page
     * past the top / bottom edge. A blur clamps at its edge, so two bars that touch (the channel
     * page's top bar and pinned tabs) met in a line; reaching into each other they blur alike.
     */
    /**
     * GUTUBE(youtube mode): no glass of its own - its content alone, on whatever is under it (the
     * top bar's brand and buttons sit straight on the bar's frost). Call before {@link #setSource}.
     */
    public void setPlain() {
        mTint = 0;
        mLiquid = false;
        mOnGlass = true; // the tint as given, transparent, never the opaque no-backdrop fill
        setElevation(0f);
        setClipToOutline(false);
        invalidate();
    }

    public void setStroke(int color, float widthDp) {
        mStrokeColor = color;
        mStrokeWidth = widthDp * mDensity;
        mBorderPaint.setColor(color);
        mBorderPaint.setStrokeWidth(mStrokeWidth);
        invalidate();
    }

    public void setTint(int tint) {
        if (mTint != tint) {
            mTint = tint;
            invalidate();
        }
    }

    public void setLiquid(boolean liquid) {
        if (mLiquid != liquid) {
            mLiquid = liquid;
            mEffectW = -1; // rebuild the effect on the next draw
            invalidate();
        }
    }

    public boolean isLiquid() {
        return mLiquid;
    }

    /** GUTUBE(stats): a heavier frost than the default, for a card that sits on glass already. */
    public void setBlur(float dp) {
        mBlurDp = dp;
        mEffectW = -1; // rebuild the effect on the next draw
        invalidate();
    }

    public void setBlurReach(int topPx, int bottomPx) {
        if (topPx != mReachTop || bottomPx != mReachBottom) {
            mReachTop = topPx;
            mReachBottom = bottomPx;
            invalidate();
        }
    }

    /** The page this glass frosts; it must not contain this view. */
    public void setSource(@Nullable GlassSource source) {
        mSource = source;
        if (source != null && source.node != null && mNode == null) {
            mNode = new RenderNode("GuTube glass");
        }
        mRecordedX = Integer.MIN_VALUE;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxWidth > 0 && MeasureSpec.getSize(widthMeasureSpec) > mMaxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(mMaxWidth, MeasureSpec.getMode(widthMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mTrackPosition);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mTrackPosition);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean dark = LiquidGlassEngine.isDarkTheme(getContext());
        mRimRect.set(0, 0, w, h);
        mRimPaint.setShader(LiquidGlassEngine.createRimShader(w, h, dark));
        if (w > 0 && h > 0) {
            mInnerGlowPaint.setShader(LiquidGlassEngine.createInnerGlowShader(w, h, mDensity, dark));
        }
        if (mFadeBottom > 0) {
            mFadePaint.setShader(new LinearGradient(0, h - mFadeBottom, 0, h,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return super.dispatchGenericMotionEvent(ev);
    }

    @NonNull
    public Spotlight getSpotlight() {
        return mSpotlight;
    }

    public float getCornerRadius() {
        return mCorner;
    }

    @NonNull
    public SpotlightTilt getTilt() {
        return mTilt;
    }

    public void setEnableTilt(boolean enable) {
        mTilt.setEnabled(enable);
        if (!enable) {
            mTilt.reset(this);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float r = radius(getWidth(), getHeight());
        int save = -1;
        if (r > 0f) {
            save = canvas.save();
            mClipPath.reset();
            mClipPath.addRoundRect(mRimRect, r, r, Path.Direction.CW);
            canvas.clipPath(mClipPath);
        }

        boolean live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mNode != null && mSource != null
                && !BatterySaver.lite() // GUTUBE(issue #93): the tint alone
                && mSource.node != null && canvas.isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0;
        // The frost (not the bar's children) in a layer, so its bottom can be faded out below.
        int faded = mFadeBottom > 0 ? canvas.saveLayer(0, 0, getWidth(), getHeight(), null) : -1;
        if (live) {
            drawBackdrop(canvas);
            mTintPaint.setColor(mTint);
        } else if (mOnGlass) {
            mTintPaint.setColor(mTint);
        } else {
            mTintPaint.setColor((mTint & 0x00FFFFFF) | (LiquidGlassEngine.FALLBACK_ALPHA << 24));
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), mTintPaint);
        if (mLiquid || r > 0f) {
            // Glassmorphism soft specular inner rim & top highlight:
            // inset 0px 0px 4px 2px rgba(255, 255, 255, 0.18)
            canvas.drawRoundRect(mRimRect, r, r, mInnerGlowPaint);
            if (mLiquid) {
                // GUTUBE(amoled): only the floating glass is grainy - the flat top bar stays true black.
                canvas.drawRect(0, 0, getWidth(), getHeight(), mNoisePaint);
            }
            canvas.drawRoundRect(mRimRect, r, r, mRimPaint);
        }
        if (mStrokeColor != 0 && mStrokeWidth > 0) {
            float inset = mStrokeWidth / 2f;
            mRimRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(mRimRect, Math.max(0, r - inset), Math.max(0, r - inset), mBorderPaint);
            mRimRect.set(0, 0, getWidth(), getHeight());
        }
        if (faded >= 0) {
            canvas.drawRect(0, getHeight() - mFadeBottom, getWidth(), getHeight(), mFadePaint);
            canvas.restoreToCount(faded);
        }
        super.onDraw(canvas);
        if (save >= 0) {
            canvas.restoreToCount(save);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        float r = radius(getWidth(), getHeight());
        int save = -1;
        if (r > 0f) {
            save = canvas.save();
            mClipPath.reset();
            mClipPath.addRoundRect(mRimRect, r, r, Path.Direction.CW);
            canvas.clipPath(mClipPath);
        }
        super.dispatchDraw(canvas);
        if (save >= 0) {
            canvas.restoreToCount(save);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void drawBackdrop(Canvas canvas) {
        RenderNode node = mNode;
        hasMoved();
        mRecordedX = mLoc[0] - mSourceLoc[0];
        mRecordedY = mLoc[1] - mSourceLoc[1];
        int w = getWidth();
        int h = getHeight();
        node.setPosition(0, -mReachTop, w, h + mReachBottom);
        if (w != mEffectW || h != mEffectH) {
            mEffectW = w;
            mEffectH = h;
            node.setRenderEffect(effect(w, h));
        }
        Canvas recording = node.beginRecording();
        recording.translate(-mRecordedX, -mRecordedY + mReachTop);
        // The source's own (opaque) background first: its node holds only the children, and text
        // on a bare background would blur to near-transparent and let the sharp page show through.
        Drawable ground = mSource.getBackground();
        if (ground != null) {
            ground.draw(recording);
        }
        recording.drawRenderNode(mSource.node);
        node.endRecording();
        canvas.drawRenderNode(node);
    }

    /** Saturate, blur, then (API 33+) refract the blurred backdrop via {@link LiquidGlassEngine}. */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private RenderEffect effect(int w, int h) {
        return LiquidGlassEngine.createBackdropEffect(
                w, h, radius(w, h), mBlurDp, LiquidGlassEngine.SATURATION_CHROME, mLiquid, mDensity);
    }

    private float radius(int w, int h) {
        return Math.min(mCorner, Math.min(w, h) / 2f);
    }

    /** The corner as drawn, at the current size (NavLens clips the bar it re-draws to it). */
    float corner() {
        return radius(getWidth(), getHeight());
    }

    /** Refreshes the on-screen offsets; true when they differ from the last recording. */
    private boolean hasMoved() {
        if (mSource == null) {
            return false;
        }
        getLocationInWindow(mLoc);
        mSource.getLocationInWindow(mSourceLoc);
        return mLoc[0] - mSourceLoc[0] != mRecordedX || mLoc[1] - mSourceLoc[1] != mRecordedY;
    }

    static Bitmap noise() {
        return LiquidGlassEngine.noiseBitmap();
    }
}
