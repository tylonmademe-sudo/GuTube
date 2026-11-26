package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE: Master horizontal pill/tab/chip navigation bar and segmented selector powered by
 * CategoryPillNavView's elastic gliding pill spring physics (independent leading/trailing edge
 * springs k_lead = 754, k_trail = 390, zeta = 0.835, vertical travel squash up to 14%, tactile
 * press scale 0.95f, soft drop shadow, and dual-pass clipPath text inversion).
 */
public class UnifiedPillNavView extends View {

    public static final int MODE_SCROLLABLE = 0;
    public static final int MODE_FIXED = 1;

    public interface OnTabSelectedListener {
        void onTabSelected(int index, boolean reselected);
    }

    private static final class TabLayout {
        final String label;
        float left;
        float right;
        float centerX;

        TabLayout(String label) {
            this.label = label;
        }
    }

    private final List<String> mLabels = new ArrayList<>();
    private final List<TabLayout> mTabLayouts = new ArrayList<>();

    @Nullable private OnTabSelectedListener mListener;
    @Nullable private HorizontalScrollView mScrollView;

    // Paints & drawing objects
    private TextPaint mTextPaint;
    private Paint mPillPaint;
    private Paint mShadowPaint;
    private Paint mTrackFillPaint;
    private Paint mTrackStrokePaint;
    private final RectF mPillRect = new RectF();
    private final RectF mShadowRect = new RectF();
    private final RectF mTrackRect = new RectF();
    private final Path mPillPath = new Path();

    // Cached pill gradient (avoid per-frame allocations in onDraw)
    @Nullable private LinearGradient mCachedGradient;
    private float mCachedPillTop = Float.NaN;
    private float mCachedPillBottom = Float.NaN;
    private boolean mCachedDarkTheme = true;

    // Configuration & dimensions
    private int mMode = MODE_SCROLLABLE;
    private boolean mShowTrack = false;
    private float mPillHeight;
    private float mDefaultViewHeight;
    private float mPadX;
    private float mGap;
    private float mTrackPad;
    private float mTotalWidth = 0f;
    private float mTouchSlop;

    // Selection & interaction state
    private int mSelectedIndex = -1;
    private int mPressedIndex = -1;
    private float mDownX;
    private float mDownY;
    private boolean mScrubbing = false;

    // Elastic edge spring states
    private float mLeft = 0f;
    private float mRight = 0f;
    private float mTargetLeft = 0f;
    private float mTargetRight = 0f;
    private float mVelocityLeft = 0f;
    private float mVelocityRight = 0f;

    // Tactile press scale state
    private float mPressScale = 1.0f;
    private float mTargetPressScale = 1.0f;
    private float mVelocityPress = 0f;

    // Choreographer animation loop
    private boolean mRunning = false;
    private long mLastFrameTimeNanos = 0;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) {
                return;
            }
            if (mLastFrameTimeNanos > 0) {
                float dt = (frameTimeNanos - mLastFrameTimeNanos) * 1e-9f;
                if (dt > 0.05f) dt = 0.05f;
                if (dt < 0.001f) dt = 0.001f;
                stepAnimation(dt);
            }
            mLastFrameTimeNanos = frameTimeNanos;
            invalidate();
            if (mRunning) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public UnifiedPillNavView(@NonNull Context context) {
        this(context, null);
    }

    public UnifiedPillNavView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UnifiedPillNavView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;
        mPillHeight = 34f * density;
        mDefaultViewHeight = 52f * density;
        mPadX = 16f * density;
        mGap = 8f * density;
        mTrackPad = 4f * density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        float textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics());

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.UnifiedPillNavView);
            mMode = a.getInt(R.styleable.UnifiedPillNavView_pillMode, MODE_SCROLLABLE);
            mShowTrack = a.getBoolean(R.styleable.UnifiedPillNavView_pillShowTrack, false);
            mPillHeight = a.getDimension(R.styleable.UnifiedPillNavView_pillHeight, mPillHeight);
            textSize = a.getDimension(R.styleable.UnifiedPillNavView_pillTextSize, textSize);
            a.recycle();
        }

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTypeface(AppFonts.medium(context));
        mTextPaint.setTextSize(textSize);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPillPaint.setStyle(Paint.Style.FILL);

        mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mShadowPaint.setStyle(Paint.Style.FILL);

        mTrackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTrackFillPaint.setStyle(Paint.Style.FILL);

        mTrackStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTrackStrokePaint.setStyle(Paint.Style.STROKE);
        mTrackStrokePaint.setStrokeWidth(1f * density);
    }

    public void setOnTabSelectedListener(@Nullable OnTabSelectedListener listener) {
        mListener = listener;
    }

    public void setHorizontalScrollView(@Nullable HorizontalScrollView scrollView) {
        mScrollView = scrollView;
    }

    public void setFixedMode(boolean fixed) {
        int newMode = fixed ? MODE_FIXED : MODE_SCROLLABLE;
        if (mMode != newMode) {
            mMode = newMode;
            recomputeAndSnap(false);
            requestLayout();
        }
    }

    public void setShowTrack(boolean showTrack) {
        if (mShowTrack != showTrack) {
            mShowTrack = showTrack;
            invalidate();
        }
    }

    public void setPillHeightPx(float pillHeightPx) {
        if (pillHeightPx > 0f && mPillHeight != pillHeightPx) {
            mPillHeight = pillHeightPx;
            invalidate();
        }
    }

    public void setPillTextSizePx(float textSizePx) {
        if (textSizePx > 0f && mTextPaint.getTextSize() != textSizePx) {
            mTextPaint.setTextSize(textSizePx);
            recomputeAndSnap(false);
            requestLayout();
        }
    }

    public void setLabels(@Nullable List<? extends CharSequence> labels, int selectedIndex) {
        mLabels.clear();
        if (labels != null) {
            for (CharSequence label : labels) {
                mLabels.add(label != null ? label.toString() : "");
            }
        }

        int clampedIndex = mLabels.isEmpty()
                ? -1
                : Math.max(0, Math.min(selectedIndex, mLabels.size() - 1));
        boolean initial = (mLeft == 0f && mRight == 0f) || !isAttachedToWindow();

        computeTabLayouts(getWidth());
        requestLayout();

        mScrubbing = false;
        if (clampedIndex >= 0 && clampedIndex < mTabLayouts.size()) {
            mSelectedIndex = clampedIndex;
            TabLayout target = mTabLayouts.get(clampedIndex);
            mTargetLeft = target.left;
            mTargetRight = target.right;
            if (initial) {
                mLeft = mTargetLeft;
                mRight = mTargetRight;
                mVelocityLeft = 0f;
                mVelocityRight = 0f;
                stopAnimation();
                invalidate();
                post(() -> scrollToSelected(false));
            } else {
                startAnimation();
                post(() -> scrollToSelected(true));
            }
        } else {
            mSelectedIndex = -1;
            stopAnimation();
            invalidate();
        }
    }

    public void setSelectedIndex(int index, boolean animate) {
        if (index < 0 || index >= mTabLayouts.size()) {
            return;
        }
        mScrubbing = false;
        mSelectedIndex = index;
        TabLayout target = mTabLayouts.get(index);
        mTargetLeft = target.left;
        mTargetRight = target.right;
        if (animate && isAttachedToWindow() && isShown()) {
            startAnimation();
            scrollToSelected(true);
        } else {
            mLeft = mTargetLeft;
            mRight = mTargetRight;
            mVelocityLeft = 0f;
            mVelocityRight = 0f;
            stopAnimation();
            invalidate();
            scrollToSelected(false);
        }
    }

    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    public int getTabCount() {
        return mTabLayouts.size();
    }

    /**
     * Live swipe-progress scrubbing between {@code position} and {@code position + 1}.
     * Stops the free spring while dragging; call {@link #setSelectedIndex(int, boolean)} on settle.
     */
    public void setScrollPosition(int position, float positionOffset) {
        if (mTabLayouts.isEmpty()) {
            return;
        }
        int count = mTabLayouts.size();
        int clampedPos = Math.max(0, Math.min(position, count - 1));
        float clampedOffset = Math.max(0f, Math.min(1f, positionOffset));

        TabLayout a = mTabLayouts.get(clampedPos);
        float interpLeft = a.left;
        float interpRight = a.right;
        if (clampedOffset > 0f && clampedPos + 1 < count) {
            TabLayout b = mTabLayouts.get(clampedPos + 1);
            interpLeft = a.left + (b.left - a.left) * clampedOffset;
            interpRight = a.right + (b.right - a.right) * clampedOffset;
        }

        stopAnimation();
        mScrubbing = true;
        mLeft = interpLeft;
        mRight = interpRight;
        mTargetLeft = interpLeft;
        mTargetRight = interpRight;
        mVelocityLeft = 0f;
        mVelocityRight = 0f;
        invalidate();

        if (mScrollView != null) {
            int scrollWidth = mScrollView.getWidth();
            if (scrollWidth > 0) {
                float center = (interpLeft + interpRight) * 0.5f;
                int targetX = Math.round(center - scrollWidth * 0.5f);
                int maxScroll = Math.max(0, getWidth() - scrollWidth);
                mScrollView.scrollTo(Math.max(0, Math.min(targetX, maxScroll)), 0);
            }
        }
    }

    public void scrollToSelected(boolean smooth) {
        if (mScrollView == null || mSelectedIndex < 0 || mSelectedIndex >= mTabLayouts.size()) {
            return;
        }
        TabLayout selected = mTabLayouts.get(mSelectedIndex);
        float chipCenter = selected.centerX;
        int scrollWidth = mScrollView.getWidth();
        if (scrollWidth <= 0) {
            mScrollView.post(() -> scrollToSelected(smooth));
            return;
        }
        int targetX = Math.round(chipCenter - scrollWidth * 0.5f);
        int maxScroll = Math.max(0, getWidth() - scrollWidth);
        targetX = Math.max(0, Math.min(targetX, maxScroll));
        if (smooth) {
            mScrollView.smoothScrollTo(targetX, 0);
        } else {
            mScrollView.scrollTo(targetX, 0);
        }
    }

    private void recomputeAndSnap(boolean animate) {
        computeTabLayouts(getWidth());
        if (mSelectedIndex >= 0 && mSelectedIndex < mTabLayouts.size()) {
            TabLayout target = mTabLayouts.get(mSelectedIndex);
            mTargetLeft = target.left;
            mTargetRight = target.right;
            if (!animate) {
                mLeft = mTargetLeft;
                mRight = mTargetRight;
                mVelocityLeft = 0f;
                mVelocityRight = 0f;
                stopAnimation();
            } else {
                startAnimation();
            }
        }
        invalidate();
    }

    private void computeTabLayouts(int viewWidth) {
        mTabLayouts.clear();
        if (mLabels.isEmpty()) {
            mTotalWidth = 0f;
            return;
        }

        int padLeft = getPaddingLeft();
        int padRight = getPaddingRight();

        if (mMode == MODE_FIXED) {
            int count = mLabels.size();
            float trackInset = mShowTrack ? mTrackPad : 0f;
            float availW = Math.max(0f, viewWidth - padLeft - padRight - trackInset * 2f);
            float chipW = availW > 0f ? (availW / count) : 0f;
            float x = padLeft + trackInset;
            for (int i = 0; i < count; i++) {
                TabLayout layout = new TabLayout(mLabels.get(i));
                layout.left = x;
                layout.right = x + chipW;
                layout.centerX = x + chipW * 0.5f;
                mTabLayouts.add(layout);
                x += chipW;
            }
            mTotalWidth = Math.max(0f, viewWidth);
        } else {
            float x = padLeft;
            for (int i = 0; i < mLabels.size(); i++) {
                String label = mLabels.get(i);
                TabLayout layout = new TabLayout(label);
                float textW = mTextPaint.measureText(label);
                float chipW = textW + 2f * mPadX;
                layout.left = x;
                layout.right = x + chipW;
                layout.centerX = x + chipW * 0.5f;
                mTabLayouts.add(layout);
                x += chipW + mGap;
            }
            mTotalWidth = (x > padLeft ? (x - mGap) : padLeft) + padRight;
        }
    }

    private void startAnimation() {
        if (!mRunning) {
            mRunning = true;
            mLastFrameTimeNanos = 0;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    private void stopAnimation() {
        if (mRunning) {
            mRunning = false;
            mLastFrameTimeNanos = 0;
            Choreographer.getInstance().removeFrameCallback(mFrameCallback);
        }
    }

    private void stepAnimation(float dt) {
        float remaining = dt;
        while (remaining > 0f) {
            float step = Math.min(remaining, 0.002f);
            integrate(step);
            remaining -= step;
        }

        boolean settledLeft = Math.abs(mLeft - mTargetLeft) < 0.15f && Math.abs(mVelocityLeft) < 0.8f;
        boolean settledRight = Math.abs(mRight - mTargetRight) < 0.15f && Math.abs(mVelocityRight) < 0.8f;
        boolean settledPress = Math.abs(mPressScale - mTargetPressScale) < 0.003f && Math.abs(mVelocityPress) < 0.03f;

        if (settledLeft && settledRight && settledPress) {
            mLeft = mTargetLeft;
            mRight = mTargetRight;
            mVelocityLeft = 0f;
            mVelocityRight = 0f;
            mPressScale = mTargetPressScale;
            mVelocityPress = 0f;
            stopAnimation();
        }
    }

    private void integrate(float dt) {
        float curCenter = (mLeft + mRight) * 0.5f;
        float targetCenter = (mTargetLeft + mTargetRight) * 0.5f;

        // Independent edge spring constants:
        // k_lead = 520 * (1 + 0.9 * 0.5) = 754
        // k_trail = 520 * (1 - 0.5 * 0.5) = 390
        // zeta = 0.835 -> c_lead = 45.856, c_trail = 32.979
        float kLeft, cLeft, kRight, cRight;
        if (targetCenter > curCenter + 0.5f) {
            kRight = 754f;
            cRight = 45.856f;
            kLeft = 390f;
            cLeft = 32.979f;
        } else if (targetCenter < curCenter - 0.5f) {
            kLeft = 754f;
            cLeft = 45.856f;
            kRight = 390f;
            cRight = 32.979f;
        } else {
            kLeft = 520f;
            cLeft = 38.08f;
            kRight = 520f;
            cRight = 38.08f;
        }

        float forceLeft = -kLeft * (mLeft - mTargetLeft) - cLeft * mVelocityLeft;
        mVelocityLeft += forceLeft * dt;
        mLeft += mVelocityLeft * dt;

        float forceRight = -kRight * (mRight - mTargetRight) - cRight * mVelocityRight;
        mVelocityRight += forceRight * dt;
        mRight += mVelocityRight * dt;

        if (mRight < mLeft + mPillHeight * 0.5f) {
            mRight = mLeft + mPillHeight * 0.5f;
        }

        // Tactile press scale spring (k = 600, damping = 38)
        float forcePress = -600f * (mPressScale - mTargetPressScale) - 38f * mVelocityPress;
        mVelocityPress += forcePress * dt;
        mPressScale += mVelocityPress * dt;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int desiredHeight = Math.round(mDefaultViewHeight) + getPaddingTop() + getPaddingBottom();
        int measuredHeight;
        if (heightMode == MeasureSpec.EXACTLY) {
            measuredHeight = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            measuredHeight = Math.min(desiredHeight, heightSize);
        } else {
            measuredHeight = desiredHeight;
        }

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int measuredWidth;
        if (mMode == MODE_FIXED) {
            if (widthMode == MeasureSpec.EXACTLY || widthMode == MeasureSpec.AT_MOST) {
                measuredWidth = widthSize;
            } else {
                measuredWidth = Math.round(computeMinFixedWidth());
            }
        } else {
            measuredWidth = Math.round(mTotalWidth);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    private float computeMinFixedWidth() {
        float maxTabW = 0f;
        for (int i = 0; i < mLabels.size(); i++) {
            maxTabW = Math.max(maxTabW, mTextPaint.measureText(mLabels.get(i)) + 2f * mPadX);
        }
        return maxTabW * mLabels.size() + getPaddingLeft() + getPaddingRight() + (mShowTrack ? mTrackPad * 2f : 0f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mMode == MODE_FIXED && w > 0) {
            computeTabLayouts(w);
            if (!mScrubbing && mSelectedIndex >= 0 && mSelectedIndex < mTabLayouts.size()) {
                TabLayout target = mTabLayouts.get(mSelectedIndex);
                mTargetLeft = target.left;
                mTargetRight = target.right;
                if (!mRunning) {
                    mLeft = mTargetLeft;
                    mRight = mTargetRight;
                }
            }
        }
    }

    private boolean isDarkTheme() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode != Configuration.UI_MODE_NIGHT_NO;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mTabLayouts.isEmpty()) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float viewH = getHeight();
        float viewW = getWidth();
        boolean dark = isDarkTheme();

        float pillH = Math.min(mPillHeight, Math.max(16f * density, viewH - (mShowTrack ? mTrackPad * 2f : 4f * density)));

        // Optional subtle translucent pill track behind all tabs (MODE_FIXED segmented bars)
        if (mShowTrack && viewW > 0f) {
            float trackH = Math.min(viewH, pillH + mTrackPad * 2f);
            float trackTop = (viewH - trackH) * 0.5f;
            float strokeHalf = mTrackStrokePaint.getStrokeWidth() * 0.5f;
            mTrackRect.set(
                    getPaddingLeft() + strokeHalf,
                    trackTop + strokeHalf,
                    viewW - getPaddingRight() - strokeHalf,
                    trackTop + trackH - strokeHalf
            );
            float trackRadius = trackH * 0.5f;
            mTrackFillPaint.setColor(dark ? 0x1AFFFFFF : 0x14000000);
            mTrackStrokePaint.setColor(dark ? 0x1FFFFFFF : 0x1A000000);
            canvas.drawRoundRect(mTrackRect, trackRadius, trackRadius, mTrackFillPaint);
            canvas.drawRoundRect(mTrackRect, trackRadius, trackRadius, mTrackStrokePaint);
        }

        // Vertical travel squash: 1 - clamp((w / w_target - 1) * 0.3, 0, 0.14)
        float curW = Math.max(1f, mRight - mLeft);
        float targetW = Math.max(1f, mTargetRight - mTargetLeft);
        float stretch = Math.max(0f, (curW / targetW) - 1.0f);
        float squash = Math.min(0.14f, stretch * 0.3f);
        float sy = 1.0f - squash;

        float effectivePillH = pillH * sy * mPressScale;
        float pillTop = (viewH - effectivePillH) * 0.5f;
        float pillBottom = pillTop + effectivePillH;
        float cy = (pillTop + pillBottom) * 0.5f;

        float midX = (mLeft + mRight) * 0.5f;
        float halfW = (curW * 0.5f) * mPressScale;
        float pillLeft = midX - halfW;
        float pillRight = midX + halfW;
        float pillRadius = effectivePillH * 0.5f;

        mPillRect.set(pillLeft, pillTop, pillRight, pillBottom);

        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) * 0.5f;

        // --- Pass 1: Inactive muted text ---
        mTextPaint.setColor(dark ? 0xA3F4F4F5 : 0x99121016);
        for (int i = 0; i < mTabLayouts.size(); i++) {
            TabLayout tab = mTabLayouts.get(i);
            canvas.drawText(tab.label, tab.centerX, textY, mTextPaint);
        }

        // --- Pass 2: Active gliding pill capsule & drop shadow ---
        if (mSelectedIndex >= 0 && (pillRight - pillLeft) > 2f) {
            mShadowPaint.setColor(dark ? 0x22000000 : 0x1F000000);
            mShadowRect.set(pillLeft - 0.5f * density, pillTop + 1.5f * density,
                    pillRight + 0.5f * density, pillBottom + 2.5f * density);
            canvas.drawRoundRect(mShadowRect, pillRadius, pillRadius, mShadowPaint);

            if (mCachedGradient == null
                    || Math.abs(pillTop - mCachedPillTop) > 0.25f
                    || Math.abs(pillBottom - mCachedPillBottom) > 0.25f
                    || dark != mCachedDarkTheme) {
                int topColor = dark ? 0xFFFFFFFF : 0xFF18181B;
                int bottomColor = dark ? 0xFFEBEBF0 : 0xFF27272A;
                mCachedGradient = new LinearGradient(
                        0, pillTop, 0, pillBottom,
                        topColor, bottomColor,
                        Shader.TileMode.CLAMP
                );
                mCachedPillTop = pillTop;
                mCachedPillBottom = pillBottom;
                mCachedDarkTheme = dark;
            }
            mPillPaint.setShader(mCachedGradient);
            canvas.drawRoundRect(mPillRect, pillRadius, pillRadius, mPillPaint);

            // --- Pass 3: Clipped high-contrast active text ---
            mPillPath.reset();
            mPillPath.addRoundRect(mPillRect, pillRadius, pillRadius, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(mPillPath);
            mTextPaint.setColor(dark ? 0xFF121016 : 0xFFFFFFFF);
            for (int i = 0; i < mTabLayouts.size(); i++) {
                TabLayout tab = mTabLayouts.get(i);
                if (tab.right >= pillLeft - 10f && tab.left <= pillRight + 10f) {
                    canvas.drawText(tab.label, tab.centerX, textY, mTextPaint);
                }
            }
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || mTabLayouts.isEmpty()) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mPressedIndex = findTabIndexAt(x);
                if (mPressedIndex >= 0) {
                    mTargetPressScale = 0.95f;
                    startAnimation();
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mPressedIndex >= 0) {
                    float dist = (float) Math.hypot(x - mDownX, y - mDownY);
                    if (dist > mTouchSlop) {
                        mPressedIndex = -1;
                        mTargetPressScale = 1.0f;
                        startAnimation();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mPressedIndex >= 0) {
                    int clickedIndex = mPressedIndex;
                    boolean reselected = clickedIndex == mSelectedIndex;
                    mPressedIndex = -1;
                    mTargetPressScale = 1.0f;
                    startAnimation();
                    Haptics.tick(this);
                    setSelectedIndex(clickedIndex, true);
                    if (mListener != null && clickedIndex < mLabels.size()) {
                        mListener.onTabSelected(clickedIndex, reselected);
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (mPressedIndex >= 0 || mTargetPressScale != 1.0f) {
                    mPressedIndex = -1;
                    mTargetPressScale = 1.0f;
                    startAnimation();
                }
                break;
        }
        return super.onTouchEvent(event);
    }

    private int findTabIndexAt(float x) {
        if (mTabLayouts.isEmpty()) {
            return -1;
        }
        float halfGap = mMode == MODE_FIXED ? 0f : (mGap * 0.5f);
        for (int i = 0; i < mTabLayouts.size(); i++) {
            TabLayout tab = mTabLayouts.get(i);
            if (x >= tab.left - halfGap && x <= tab.right + halfGap) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
