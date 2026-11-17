package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GUTUBE: Native Android implementation of the React Bits <GooeyNav /> component.
 *
 * Architecture:
 * 1. Root Container (Stage): FrameLayout hosting underlying shader canvas and foreground chip row.
 * 2. Shader Stage (Behind): Translates WebGL Signed Distance Fields (sdPill, sdCapsule) to Android
 *    AGSL RuntimeShader (API 33+), with RenderEffect (blur + alpha threshold color filter) fallback
 *    (API 31+), and high-performance Canvas geometry fallback (API < 31).
 * 3. Foreground Text Chips: HorizontalScrollView containing chip labels/icons with transparent
 *    backgrounds and adaptive touch spotlights.
 * 4. Physics Engine: Choreographer frame loop running travelEase, cubic Bézier trajectories,
 *    and exact mass-spring dynamics (omega = 26, zeta = 1 - 0.72 * wobble).
 * 5. Glitch-Free Coordinate Tracking: Path calculations between old and new chip coordinates
 *    never snap to 0, and dynamically track horizontal scroll and feed swipe progress.
 */
public class GooeyNavLayout extends FrameLayout implements Choreographer.FrameCallback {

    public interface OnChangeListener {
        void onChange(int index, Item item);
    }

    public interface Picked {
        void onPicked(int index);
    }

    public static class Item {
        public final int id;
        public final CharSequence label;
        @Nullable public final Drawable icon;

        public Item(int id, @NonNull CharSequence label) {
            this(id, label, null);
        }

        public Item(int id, @NonNull CharSequence label, @Nullable Drawable icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final int MAX_DROPS = 16;
    private static final int MAX_PILLS = 4;
    private static final int DEFAULT_PARTICLE_COUNT = 15;
    private static final float DEFAULT_SPREAD_DP = 56f;
    private static final int DEFAULT_ANIMATION_TIME_MS = 600;
    private static final int DEFAULT_TIME_VARIANCE_MS = 300;
    private static final float DEFAULT_GOOEYNESS = 0.5f;
    private static final float DEFAULT_WOBBLE = 0.5f;
    private static final float DEFAULT_OMEGA = 26f;

    private static final String AGSL_SRC =
            "uniform float2 uResolution;\n"
            + "uniform float uRatio;\n"
            + "uniform float4 uPills[4];\n"
            + "uniform float4 uHover;\n"
            + "uniform float4 uDrops[16];\n"
            + "uniform float2 uTails[16];\n"
            + "uniform float3 uTints[16];\n"
            + "uniform int uCount;\n"
            + "uniform float uSmooth;\n"
            + "uniform float3 uPill;\n"
            + "uniform float4 uHoverColor;\n"
            + "uniform float uShadow;\n"
            + "\n"
            + "float sdPill(float2 p, float4 box) {\n"
            + "    float2 extent = max(box.zw, float2(0.0));\n"
            + "    float r = min(extent.x, extent.y);\n"
            + "    float2 q = abs(p - box.xy) - extent + r;\n"
            + "    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "float sdCapsule(float2 p, float2 a, float2 b, float r) {\n"
            + "    float2 pa = p - a;\n"
            + "    float2 ba = b - a;\n"
            + "    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);\n"
            + "    return length(pa - ba * h) - r;\n"
            + "}\n"
            + "\n"
            + "float4 blendField(float4 a, float4 b, float k) {\n"
            + "    float h = clamp(0.5 + 0.5 * (b.w - a.w) / k, 0.0, 1.0);\n"
            + "    return float4(mix(b.rgb, a.rgb, h), mix(b.w, a.w, h) - k * h * (1.0 - h));\n"
            + "}\n"
            + "\n"
            + "float4 field(float2 p) {\n"
            + "    float4 shape = float4(uPill, 100000.0);\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "        float4 pill = uPills[i];\n"
            + "        if (pill.z <= 0.0) continue;\n"
            + "        shape = blendField(shape, float4(uPill, sdPill(p, pill)), uSmooth);\n"
            + "    }\n"
            + "    for (int i = 0; i < 16; i++) {\n"
            + "        if (i >= uCount) break;\n"
            + "        float4 drop = uDrops[i];\n"
            + "        if (drop.z <= 0.0) continue;\n"
            + "        float d = sdCapsule(p, drop.xy, drop.xy - uTails[i], drop.z);\n"
            + "        if (d > shape.w + uSmooth) continue;\n"
            + "        shape = blendField(shape, float4(uTints[i], d), uSmooth);\n"
            + "    }\n"
            + "    return shape;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 p = fragCoord;\n"
            + "    float4 shape = field(p);\n"
            + "    float d = shape.w;\n"
            + "    float aa = 0.75;\n"
            + "    float body = clamp(0.5 - d / aa, 0.0, 1.0);\n"
            + "    float dX = field(p + float2(1.0, 0.0)).w - field(p - float2(1.0, 0.0)).w;\n"
            + "    float dY = field(p + float2(0.0, 1.0)).w - field(p - float2(0.0, 1.0)).w;\n"
            + "    float2 grad = float2(dX, -dY);\n"
            + "    float2 n = length(grad) > 0.0001 ? normalize(grad) : float2(0.0, 1.0);\n"
            + "    float rim = (1.0 - smoothstep(0.0, 1.6, -d)) * max(0.0, -n.y) * 0.3;\n"
            + "    float3 fill = mix(shape.rgb, float3(1.0), rim) * (1.0 - 0.06 * max(0.0, n.y) * (1.0 - smoothstep(0.0, 6.0, -d)));\n"
            + "    float lifted = field(p - float2(0.0, 3.0)).w;\n"
            + "    float shadow = (1.0 - smoothstep(-3.0, 9.0, lifted)) * uShadow * (1.0 - body);\n"
            + "    float hover = clamp(0.5 - sdPill(p, uHover) / aa, 0.0, 1.0) * uHoverColor.a;\n"
            + "    float4 color = float4(0.0, 0.0, 0.0, shadow);\n"
            + "    color = float4(uHoverColor.rgb * hover, hover) + color * (1.0 - hover);\n"
            + "    color = float4(fill * body, body) + color * (1.0 - body);\n"
            + "    return half4(color);\n"
            + "}\n";

    // --- State and Config ---
    protected final float mDp;
    protected int mParticleCount = DEFAULT_PARTICLE_COUNT;
    protected float mSpread;
    protected int mAnimationTimeMs = DEFAULT_ANIMATION_TIME_MS;
    protected int mTimeVarianceMs = DEFAULT_TIME_VARIANCE_MS;
    protected float mGooeyness = DEFAULT_GOOEYNESS;
    protected float mWobble = DEFAULT_WOBBLE;
    protected boolean mFrame = false;
    protected boolean mHoverEffect = false;
    protected int mPillColor = 0xFFF4F4F5;
    protected int mActiveTextColor = 0xFF18161D;
    protected int mMutedTextColor = 0x9EFFFFFF;
    protected float mShadow = 0.4f;

    protected final List<Item> mItems = new ArrayList<>();
    protected int mTargetIndex = 0;
    protected OnChangeListener mChangeListener;
    protected Picked mPickedListener;

    // View components
    protected final GooStageView mStageView;
    protected final HorizontalScrollView mScrollView;
    protected final LinearLayout mRow;

    // Physics Engine State
    private static final class Drop {
        static final int STATE_WAITING = 0;
        static final int STATE_FLYING = 1;
        static final int STATE_ABSORBING = 2;

        float x, y;
        float vx, vy;
        float mass;
        float size;
        float given;
        int source;
        int index;
        long leaveTime;
        long startTime;
        float duration;
        float emerged;
        float absorbed;
        int state;
        int tint;

        float x0, y0, x1, y1, x2, y2, x3, y3;
    }

    private static final class PillState {
        float mass;
        float scale;
        float velocity;
        float outgoing;

        PillState(float mass, float scale, float velocity) {
            this.mass = mass;
            this.scale = scale;
            this.velocity = velocity;
            this.outgoing = 0f;
        }
    }

    private final Drop[] mDrops = new Drop[MAX_DROPS];
    private int mActiveDropCount = 0;
    private final SparseArray<PillState> mPillStates = new SparseArray<>(MAX_PILLS);
    private RectF[] mRects = new RectF[0];
    private final Random mRandom = new Random();

    // Shader buffers
    private final float[] mPillData = new float[MAX_PILLS * 4];
    private final float[] mDropData = new float[MAX_DROPS * 4];
    private final float[] mTailData = new float[MAX_DROPS * 2];
    private final float[] mTintData = new float[MAX_DROPS * 3];

    private boolean mRunning = false;
    private long mLastFrameTime = 0;

    // Interactive Swipe Transition Tracking
    private int mSwipeFrom = -1;
    private int mSwipeTo = -1;
    private float mSwipeProgress = 0f;

    // Shader & Fallback Fields
    private RuntimeShader mRuntimeShader;
    private Paint mShaderPaint;
    private boolean mUseShader = false;
    private boolean mUseRenderEffect = false;

    public GooeyNavLayout(@NonNull Context context) {
        this(context, null);
    }

    public GooeyNavLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSpread = DEFAULT_SPREAD_DP * mDp;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GooeyNav);
            mParticleCount = a.getInt(R.styleable.GooeyNav_gnParticleCount, DEFAULT_PARTICLE_COUNT);
            mSpread = a.getDimension(R.styleable.GooeyNav_gnSpread, DEFAULT_SPREAD_DP * mDp);
            mAnimationTimeMs = a.getInt(R.styleable.GooeyNav_gnAnimationTime, DEFAULT_ANIMATION_TIME_MS);
            mTimeVarianceMs = a.getInt(R.styleable.GooeyNav_gnTimeVariance, DEFAULT_TIME_VARIANCE_MS);
            mGooeyness = a.getFloat(R.styleable.GooeyNav_gnGooeyness, DEFAULT_GOOEYNESS);
            mWobble = a.getFloat(R.styleable.GooeyNav_gnWobble, DEFAULT_WOBBLE);
            mFrame = a.getBoolean(R.styleable.GooeyNav_gnFrame, false);
            if (a.hasValue(R.styleable.GooeyNav_gnPillColor)) {
                mPillColor = a.getColor(R.styleable.GooeyNav_gnPillColor, mPillColor);
            }
            if (a.hasValue(R.styleable.GooeyNav_gnActiveTextColor)) {
                mActiveTextColor = a.getColor(R.styleable.GooeyNav_gnActiveTextColor, mActiveTextColor);
            }
            a.recycle();
        }

        setClipChildren(false);
        setClipToPadding(false);

        // Preallocate drops pool
        for (int i = 0; i < MAX_DROPS; i++) {
            mDrops[i] = new Drop();
        }

        // 1. Stage View (Underlying Canvas/Shader Layer)
        mStageView = new GooStageView(context);
        LayoutParams stageLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(mStageView, stageLp);

        // 2. Foreground Chips (HorizontalScrollView + LinearLayout)
        mScrollView = new HorizontalScrollView(context);
        mScrollView.setHorizontalScrollBarEnabled(false);
        mScrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        mScrollView.setClipChildren(false);
        mScrollView.setClipToPadding(false);

        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.setClipChildren(false);
        mRow.setClipToPadding(false);
        int padH = Math.round(8 * mDp);
        mRow.setPadding(padH, 0, padH, 0);

        mScrollView.addView(mRow, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(mScrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Sync stage with horizontal scrolling
        mScrollView.getViewTreeObserver().addOnScrollChangedListener(mStageView::invalidate);

        // Setup Shaders and RenderEffect pipeline
        initShaders();
    }

    private void initShaders() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                mRuntimeShader = new RuntimeShader(AGSL_SRC);
                mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                mShaderPaint.setShader(mRuntimeShader);
                mUseShader = true;
            } catch (Throwable t) {
                mUseShader = false;
                mRuntimeShader = null;
            }
        }

        if (!mUseShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                float blurRadius = 14f * mDp;
                RenderEffect blur = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP);
                ColorMatrix cm = new ColorMatrix(new float[] {
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, 0f,
                        0f, 0f, 0f, 22f, -11f * 255f
                });
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(cm);
                RenderEffect alphaThreshold = RenderEffect.createColorFilterEffect(filter);
                RenderEffect chain = RenderEffect.createChainEffect(alphaThreshold, blur);
                mStageView.setRenderEffect(chain);
                mUseRenderEffect = true;
            } catch (Throwable t) {
                mUseRenderEffect = false;
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewParent parent = getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) parent;
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
            parent = vg.getParent();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateRects();
        if (mPillStates.size() == 0 && mTargetIndex >= 0 && mTargetIndex < mItems.size()) {
            settle(mTargetIndex);
        }
    }

    private void updateRects() {
        int count = mRow.getChildCount();
        if (count == 0) {
            return;
        }
        if (mRects.length != count) {
            mRects = new RectF[count];
        }
        for (int i = 0; i < count; i++) {
            View child = mRow.getChildAt(i);
            if (child.getWidth() > 0) {
                mRects[i] = new RectF(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            } else if (mRects[i] == null) {
                mRects[i] = new RectF(0, 0, 0, 0);
            }
        }
    }

    // --- Public API ---

    public void setOnChangeListener(@Nullable OnChangeListener listener) {
        mChangeListener = listener;
    }

    public void setOnPicked(@Nullable Picked picked) {
        mPickedListener = picked;
    }

    public void setChips(@NonNull List<? extends CharSequence> labels) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            items.add(new Item(i, labels.get(i)));
        }
        setItems(items);
    }

    public void setItems(@NonNull List<Item> items) {
        if (sameItems(items)) {
            return;
        }
        mItems.clear();
        mItems.addAll(items);
        mRow.removeAllViews();
        mRects = new RectF[mItems.size()];

        for (int i = 0; i < mItems.size(); i++) {
            Item item = mItems.get(i);
            View itemView = createItemView(i, item);
            mRow.addView(itemView);
        }

        if (mTargetIndex >= mItems.size()) {
            mTargetIndex = Math.max(0, mItems.size() - 1);
        }
        settle(mTargetIndex);
    }

    private boolean sameItems(List<Item> items) {
        if (items.size() != mItems.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id != mItems.get(i).id || !items.get(i).label.equals(mItems.get(i).label)) {
                return false;
            }
        }
        return true;
    }

    private View createItemView(int index, Item item) {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setClickable(true);
        layout.setFocusable(true);
        layout.setBackground(null); // Transparent background: shader handles highlight

        int padH = Math.round(12 * mDp);
        int padV = Math.round(6 * mDp);
        layout.setPadding(padH, padV, padH, padV);

        if (item.icon != null) {
            ImageView iconView = new ImageView(getContext());
            iconView.setImageDrawable(item.icon);
            int iconSize = Math.round(18 * mDp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(iconSize, iconSize);
            lp.setMarginEnd(Math.round(6 * mDp));
            layout.addView(iconView, lp);
        }

        TextView tv = new TextView(getContext());
        tv.setText(item.label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(index == mTargetIndex ? mActiveTextColor : mMutedTextColor);
        layout.addView(tv);

        // Adaptive spotlight interaction
        Spotlight.attach(layout);

        layout.setOnClickListener(v -> pick(index));
        return layout;
    }

    public void setSelected(int index) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        if (index == mTargetIndex && !mRunning) {
            return;
        }
        select(index, true);
    }

    public void pick(int index) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        if (index != mTargetIndex) {
            Haptics.tick(this);
            select(index, true);
            if (mPickedListener != null) {
                mPickedListener.onPicked(index);
            }
            if (mChangeListener != null) {
                mChangeListener.onChange(index, mItems.get(index));
            }
        }
    }

    /**
     * Accurately transitions highlight to the target index without snapping to 0.
     */
    public void select(int index, boolean animate) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        if (!animate || mRects == null || index >= mRects.length || mRects[index] == null || mRects[index].width() <= 0) {
            settle(index);
            return;
        }

        int from = mTargetIndex;
        mTargetIndex = index;
        long now = SystemClock.uptimeMillis();

        // 1. Retarget any existing in-flight drops toward the new destination
        for (int i = 0; i < mActiveDropCount; i++) {
            Drop drop = mDrops[i];
            drop.index = index;
            if (drop.state == Drop.STATE_FLYING) {
                plan(drop, index, now, false);
            } else if (drop.state == Drop.STATE_ABSORBING) {
                drop.state = Drop.STATE_FLYING;
                drop.mass *= (1f - drop.absorbed);
                drop.absorbed = 0f;
                plan(drop, index, now, false);
            }
        }

        // 2. Emit fresh droplet bursts from all active source pills
        for (int i = 0; i < mPillStates.size(); i++) {
            int pillIdx = mPillStates.keyAt(i);
            if (pillIdx != index) {
                emit(pillIdx, index, now);
            }
        }

        // 3. Ensure target pill state exists
        getPillState(index);

        recolor();
        wake();
        smoothScrollToItem(index);
    }

    /**
     * Dynamically updates target coordinates during horizontal scroll / swipe events on the feed.
     */
    public void setScrollProgress(int fromPosition, int toPosition, float positionOffset) {
        mSwipeFrom = fromPosition;
        mSwipeTo = toPosition;
        mSwipeProgress = positionOffset;
        mStageView.invalidate();
    }

    public void setGlassSource(@Nullable View source) {
        if (source instanceof GlassSource) {
            GlassDrawable.applyPage(this, (GlassSource) source, 0);
        }
    }

    private void settle(int index) {
        mActiveDropCount = 0;
        mPillStates.clear();
        mTargetIndex = index;
        PillState state = getPillState(index);
        state.mass = 1f;
        state.scale = 1f;
        state.velocity = 0f;
        mRunning = false;
        recolor();
        mStageView.invalidate();
        smoothScrollToItem(index);
    }

    private PillState getPillState(int index) {
        PillState state = mPillStates.get(index);
        if (state == null) {
            state = new PillState(0f, 0f, 0f);
            mPillStates.put(index, state);
        }
        return state;
    }

    private void recolor() {
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                for (int c = 0; c < vg.getChildCount(); c++) {
                    View inner = vg.getChildAt(c);
                    if (inner instanceof TextView) {
                        ((TextView) inner).setTextColor(i == mTargetIndex ? mActiveTextColor : mMutedTextColor);
                    }
                }
            }
        }
    }

    private void smoothScrollToItem(int index) {
        if (index < 0 || mRects == null || index >= mRects.length || mRects[index] == null) {
            return;
        }
        RectF rect = mRects[index];
        if (rect.width() <= 0) {
            return;
        }
        int left = Math.round(rect.left - 24 * mDp);
        int right = Math.round(rect.right + 24 * mDp);
        int scrollX = mScrollView.getScrollX();
        int width = getWidth();
        if (left < scrollX) {
            mScrollView.smoothScrollTo(Math.max(0, left), 0);
        } else if (right > scrollX + width) {
            mScrollView.smoothScrollTo(right - width, 0);
        }
    }

    // --- Physics Engine (React Bits Parity) ---

    private void emit(int from, int to, long now) {
        PillState source = mPillStates.get(from);
        if (source == null || mRects == null || from >= mRects.length || to >= mRects.length) {
            return;
        }
        RectF fromRect = mRects[from];
        RectF toRect = mRects[to];
        if (fromRect == null || toRect == null || fromRect.width() <= 0 || toRect.width() <= 0) {
            return;
        }

        float available = source.mass - source.outgoing;
        if (available <= 0.002f) {
            return;
        }
        int room = MAX_DROPS - mActiveDropCount;
        int count = Math.min(room, Math.max(1, Math.round(mParticleCount * available)));
        if (count <= 0) {
            return;
        }

        float window = Math.max(0.15f, mAnimationTimeMs / 1000f) * 0.16f;
        for (int i = 0; i < count; i++) {
            float mass = available / count;
            source.outgoing += mass;
            Drop drop = mDrops[mActiveDropCount++];
            drop.x = fromRect.centerX() + (mRandom.nextFloat() - 0.5f) * fromRect.width() * 0.5f;
            drop.y = fromRect.centerY() + (mRandom.nextFloat() - 0.5f) * fromRect.height() * 0.25f;
            drop.vx = 0f;
            drop.vy = 0f;
            drop.mass = mass;
            drop.size = 0.8f + mRandom.nextFloat() * 0.4f;
            drop.given = 0f;
            drop.source = from;
            drop.index = to;
            drop.leaveTime = now + (long) (mRandom.nextFloat() * window * 1000f);
            drop.emerged = 0f;
            drop.absorbed = 0f;
            drop.state = Drop.STATE_WAITING;
            drop.tint = mPillColor;

            plan(drop, to, now, true);
        }
    }

    private void plan(Drop drop, int targetIndex, long now, boolean fresh) {
        if (mRects == null || targetIndex >= mRects.length) {
            return;
        }
        RectF targetRect = mRects[targetIndex];
        if (targetRect == null || targetRect.width() <= 0) {
            return;
        }

        float reach = Math.max(0f, mSpread);
        float ex = targetRect.centerX() + (mRandom.nextFloat() - 0.5f) * targetRect.width() * 0.45f;
        float ey = targetRect.centerY() + (mRandom.nextFloat() - 0.5f) * targetRect.height() * 0.2f;

        float base = Math.max(0.15f, mAnimationTimeMs / 1000f);
        float variance = (mRandom.nextFloat() - 0.5f) * (Math.max(0f, mTimeVarianceMs) / 1000f);
        float duration = Math.max(0.15f, base * (0.62f + mRandom.nextFloat() * 0.32f) + variance * 0.6f);

        float out = mRandom.nextFloat() * (float) (Math.PI * 2);
        float into = mRandom.nextFloat() * (float) (Math.PI * 2);
        float burst = reach * (0.7f + mRandom.nextFloat() * 0.6f);
        float swing = reach * (0.55f + mRandom.nextFloat() * 0.55f);
        float carry = Math.min(duration / 3f, 0.12f);

        drop.index = targetIndex;
        drop.startTime = now;
        drop.duration = duration;

        drop.x0 = drop.x;
        drop.y0 = drop.y;
        drop.x1 = fresh ? drop.x + (float) Math.cos(out) * burst * 1.6f : drop.x + drop.vx * carry;
        drop.y1 = fresh ? drop.y + (float) Math.sin(out) * burst * 1.6f : drop.y + drop.vy * carry;
        drop.x2 = ex + (float) Math.cos(into) * swing * 1.5f;
        drop.y2 = ey + (float) Math.sin(into) * swing * 1.5f;
        drop.x3 = ex;
        drop.y3 = ey;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static float travelEase(float t) {
        float x = clamp01(t);
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    private static float bezier(float a, float b, float c, float d, float t) {
        float u = 1f - t;
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d;
    }

    private void wake() {
        if (!mRunning) {
            mRunning = true;
            mLastFrameTime = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - mLastFrameTime) / 1000f);
        mLastFrameTime = now;

        float emergeTime = 0.09f;
        float absorbTime = 0.12f;

        // 1. Process Droplets
        for (int i = mActiveDropCount - 1; i >= 0; i--) {
            Drop drop = mDrops[i];
            if (drop.state == Drop.STATE_WAITING) {
                if (now < drop.leaveTime) {
                    continue;
                }
                drop.state = Drop.STATE_FLYING;
                plan(drop, drop.index, now, true);
            }
            if (drop.state == Drop.STATE_FLYING) {
                drop.emerged = Math.min(1f, drop.emerged + dt / emergeTime);
                PillState source = mPillStates.get(drop.source);
                if (source != null && drop.given < drop.mass) {
                    float step = Math.min(drop.mass * (dt / emergeTime), drop.mass - drop.given);
                    source.mass = Math.max(0f, source.mass - step);
                    source.outgoing = Math.max(0f, source.outgoing - step);
                    drop.given += step;
                }
                float t = clamp01((now - drop.startTime) / 1000f / drop.duration);
                float eased = travelEase(t);
                float x = bezier(drop.x0, drop.x1, drop.x2, drop.x3, eased);
                float y = bezier(drop.y0, drop.y1, drop.y2, drop.y3, eased);
                if (dt > 0f) {
                    drop.vx = (x - drop.x) / dt;
                    drop.vy = (y - drop.y) / dt;
                }
                drop.x = x;
                drop.y = y;
                if (t >= 1f) {
                    drop.state = Drop.STATE_ABSORBING;
                }
            }
            if (drop.state == Drop.STATE_ABSORBING) {
                float step = Math.min(1f - drop.absorbed, dt / absorbTime);
                drop.absorbed += step;
                drop.vx *= 0.8f;
                drop.vy *= 0.8f;
                PillState host = getPillState(drop.index);
                host.mass = Math.min(1f, host.mass + drop.mass * step);
                if (drop.absorbed >= 1f) {
                    // Remove drop by swapping with the last active drop
                    int last = mActiveDropCount - 1;
                    if (i != last) {
                        Drop temp = mDrops[i];
                        mDrops[i] = mDrops[last];
                        mDrops[last] = temp;
                    }
                    mActiveDropCount--;
                }
            }
        }

        // 2. Mass-Spring Wobble for Pills (omega = 26, zeta)
        float zeta = 1f - 0.72f * clamp01(mWobble);
        float omega = DEFAULT_OMEGA;
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / steps;

        for (int i = mPillStates.size() - 1; i >= 0; i--) {
            int index = mPillStates.keyAt(i);
            PillState state = mPillStates.valueAt(i);
            for (int n = 0; n < steps; n++) {
                state.velocity += (omega * omega * (state.mass - state.scale) - 2f * zeta * omega * state.velocity) * h;
                state.scale += state.velocity * h;
            }
            if (index != mTargetIndex && state.mass <= 0.002f && state.outgoing <= 0.002f && Math.abs(state.scale) < 0.004f) {
                mPillStates.removeAt(i);
            }
        }

        // 3. Render Stage
        mStageView.invalidate();

        // 4. Check Rest Condition
        PillState host = mPillStates.get(mTargetIndex);
        boolean resting = mActiveDropCount == 0
                && mPillStates.size() == 1
                && host != null
                && Math.abs(host.mass - host.scale) < 0.0008f
                && Math.abs(host.velocity) < 0.004f;

        if (!resting) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            mRunning = false;
            if (host != null) {
                host.mass = 1f;
                host.scale = 1f;
                host.velocity = 0f;
            }
            mStageView.invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    // --- Underlying Stage View (AGSL / RenderEffect / Canvas) ---

    private final class GooStageView extends View {
        private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mPillRect = new RectF();

        GooStageView(Context context) {
            super(context);
            setWillNotDraw(false);
            mPillPaint.setColor(mPillColor);
            mPillPaint.setStyle(Paint.Style.FILL);
            mDropPaint.setStyle(Paint.Style.FILL);
            mShadowPaint.setColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float scrollX = mScrollView.getScrollX();
            canvas.save();
            canvas.translate(-scrollX, 0);

            if (mUseShader && mRuntimeShader != null) {
                drawAGSL(canvas, scrollX);
            } else if (mUseRenderEffect) {
                drawMetaballs(canvas);
            } else {
                drawCanvasFallback(canvas);
            }

            canvas.restore();
        }

        private void drawAGSL(Canvas canvas, float scrollX) {
            int slot = 0;
            for (int i = 0; i < mPillStates.size() && slot < MAX_PILLS; i++) {
                int index = mPillStates.keyAt(i);
                PillState state = mPillStates.valueAt(i);
                if (state.scale <= 0.002f || mRects == null || index >= mRects.length) {
                    continue;
                }
                RectF r = mRects[index];
                if (r == null || r.width() <= 0) {
                    continue;
                }
                float scaleFactor = Math.min(1.0f, (float) Math.sqrt(Math.max(0f, state.scale)));
                float h = r.height() * scaleFactor;
                float w = Math.max(h, r.width() * scaleFactor);
                mPillData[slot * 4] = r.centerX();
                mPillData[slot * 4 + 1] = r.centerY();
                mPillData[slot * 4 + 2] = w / 2f;
                mPillData[slot * 4 + 3] = h / 2f;
                slot++;
            }
            for (int i = slot * 4; i < MAX_PILLS * 4; i++) {
                mPillData[i] = 0f;
            }

            // Interactive feed swipe interpolation
            if (mSwipeProgress > 0f && mSwipeFrom >= 0 && mSwipeTo >= 0
                    && mRects != null && mSwipeFrom < mRects.length && mSwipeTo < mRects.length) {
                RectF rf = mRects[mSwipeFrom];
                RectF rt = mRects[mSwipeTo];
                if (rf != null && rt != null && rf.width() > 0 && rt.width() > 0) {
                    float cx = rf.centerX() + (rt.centerX() - rf.centerX()) * mSwipeProgress;
                    float cy = rf.centerY() + (rt.centerY() - rf.centerY()) * mSwipeProgress;
                    float w = rf.width() + (rt.width() - rf.width()) * mSwipeProgress;
                    float h = rf.height() + (rt.height() - rf.height()) * mSwipeProgress;
                    mPillData[0] = cx;
                    mPillData[1] = cy;
                    mPillData[2] = w / 2f;
                    mPillData[3] = h / 2f;
                }
            }

            int count = 0;
            float chipHeight = getHeight();
            for (int i = 0; i < mActiveDropCount && count < MAX_DROPS; i++) {
                Drop drop = mDrops[i];
                if (drop.state == Drop.STATE_WAITING) {
                    continue;
                }
                float radius = 0.26f * chipHeight * drop.size * Math.min(drop.emerged, 1f - drop.absorbed);
                if (radius <= 0.05f) {
                    continue;
                }
                float speed = (float) Math.hypot(drop.vx, drop.vy);
                float length = Math.min(radius * 1.1f, speed * 0.018f);

                mDropData[count * 4] = drop.x;
                mDropData[count * 4 + 1] = drop.y;
                mDropData[count * 4 + 2] = radius;
                mDropData[count * 4 + 3] = 0f;

                mTailData[count * 2] = speed > 0.01f ? (drop.vx / speed) * length : 0f;
                mTailData[count * 2 + 1] = speed > 0.01f ? (drop.vy / speed) * length : 0f;

                mTintData[count * 3] = Color.red(drop.tint) / 255f;
                mTintData[count * 3 + 1] = Color.green(drop.tint) / 255f;
                mTintData[count * 3 + 2] = Color.blue(drop.tint) / 255f;
                count++;
            }

            float smooth = chipHeight * (0.18f + 0.5f * clamp01(mGooeyness));
            mRuntimeShader.setFloatUniform("uResolution", getWidth(), getHeight());
            mRuntimeShader.setFloatUniform("uRatio", 1.0f);
            mRuntimeShader.setFloatUniform("uPills", mPillData);
            mRuntimeShader.setFloatUniform("uHover", 0f, 0f, 0f, 0f);
            mRuntimeShader.setFloatUniform("uDrops", mDropData);
            mRuntimeShader.setFloatUniform("uTails", mTailData);
            mRuntimeShader.setFloatUniform("uTints", mTintData);
            mRuntimeShader.setIntUniform("uCount", count);
            mRuntimeShader.setFloatUniform("uSmooth", smooth);
            mRuntimeShader.setFloatUniform("uPill", Color.red(mPillColor) / 255f, Color.green(mPillColor) / 255f, Color.blue(mPillColor) / 255f);
            mRuntimeShader.setFloatUniform("uHoverColor", 0f, 0f, 0f, 0f);
            mRuntimeShader.setFloatUniform("uShadow", mShadow);

            float margin = 48f * mDp;
            canvas.drawRect(scrollX - margin, -margin, scrollX + getWidth() + margin, getHeight() + margin, mShaderPaint);
        }

        private void drawMetaballs(Canvas canvas) {
            // RenderEffect blur + alpha threshold creates real-time GPU metaballs
            drawActivePills(canvas, mPillPaint, false);
            drawDroplets(canvas, mPillPaint, false);
        }

        private void drawCanvasFallback(Canvas canvas) {
            // Soft shadow pass
            mShadowPaint.setShadowLayer(6 * mDp, 0, 2 * mDp, Color.argb(Math.round(65 * mShadow), 0, 0, 0));
            drawActivePills(canvas, mShadowPaint, true);

            // Solid body pass
            drawActivePills(canvas, mPillPaint, false);
            drawDroplets(canvas, mDropPaint, true);
        }

        private void drawActivePills(Canvas canvas, Paint paint, boolean shadowOnly) {
            for (int i = 0; i < mPillStates.size(); i++) {
                int index = mPillStates.keyAt(i);
                PillState state = mPillStates.valueAt(i);
                if (state.scale <= 0.002f || mRects == null || index >= mRects.length) {
                    continue;
                }
                RectF r = mRects[index];
                if (r == null || r.width() <= 0) {
                    continue;
                }
                float scaleFactor = Math.min(1.0f, (float) Math.sqrt(Math.max(0f, state.scale)));
                float h = r.height() * scaleFactor;
                float w = Math.max(h, r.width() * scaleFactor);
                float cx = r.centerX();
                float cy = r.centerY();
                mPillRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                float radius = mPillRect.height() / 2f;
                if (!shadowOnly) {
                    paint.setAlpha(Math.round(255 * clamp01(state.scale)));
                }
                canvas.drawRoundRect(mPillRect, radius, radius, paint);
            }
        }

        private void drawDroplets(Canvas canvas, Paint paint, boolean speedCapsules) {
            float chipHeight = getHeight();
            for (int i = 0; i < mActiveDropCount; i++) {
                Drop drop = mDrops[i];
                if (drop.state == Drop.STATE_WAITING) {
                    continue;
                }
                float dropRadius = 0.26f * chipHeight * drop.size * Math.min(drop.emerged, 1f - drop.absorbed);
                if (dropRadius <= 0.5f) {
                    continue;
                }
                float speed = (float) Math.hypot(drop.vx, drop.vy);
                float length = Math.min(dropRadius * 1.2f, speed * 0.018f);
                float tx = speed > 0.01f ? (drop.vx / speed) * length : 0f;
                float ty = speed > 0.01f ? (drop.vy / speed) * length : 0f;

                paint.setColor(drop.tint);
                paint.setAlpha(Math.round(255 * Math.min(drop.emerged, 1f - drop.absorbed)));

                if (speedCapsules && length > 1f) {
                    canvas.drawCircle(drop.x, drop.y, dropRadius, paint);
                    canvas.drawCircle(drop.x - tx, drop.y - ty, dropRadius * 0.85f, paint);
                    mPillRect.set(Math.min(drop.x, drop.x - tx) - dropRadius,
                            Math.min(drop.y, drop.y - ty) - dropRadius,
                            Math.max(drop.x, drop.x - tx) + dropRadius,
                            Math.max(drop.y, drop.y - ty) + dropRadius);
                    canvas.drawRoundRect(mPillRect, dropRadius, dropRadius, paint);
                } else {
                    canvas.drawCircle(drop.x, drop.y, dropRadius, paint);
                }
            }
        }
    }
}
