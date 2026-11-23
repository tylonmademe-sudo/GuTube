package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
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

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE: Native Android PillNav featuring the React Bits &lt;PillNav /&gt; elastic gliding
 * white pill animation with dual-pass text clipping, independent edge spring physics,
 * vertical squash, and tactile press scaling.
 */
public class CategoryPillNavView extends View {

    public interface OnChipClickListener {
        void onChipClick(FeedStrip.Item item, int index);
    }

    private static final class ChipLayout {
        final FeedStrip.Item item;
        final String label;
        float left;
        float right;
        float centerX;

        ChipLayout(FeedStrip.Item item, String label) {
            this.item = item;
            this.label = label;
        }
    }

    private final List<FeedStrip.Item> mItems = new ArrayList<>();
    private final List<ChipLayout> mChipLayouts = new ArrayList<>();

    @Nullable private OnChipClickListener mListener;
    @Nullable private HorizontalScrollView mScrollView;

    // Paints & Drawing objects
    private TextPaint mTextPaint;
    private Paint mPillPaint;
    private Paint mShadowPaint;
    private final RectF mPillRect = new RectF();
    private final RectF mShadowRect = new RectF();
    private final Path mPillPath = new Path();

    // Dimensions (computed from density)
    private float mChipHeight;
    private float mPadX;
    private float mGap;
    private float mTotalWidth = 0f;
    private float mTouchSlop;

    // Selection & Navigation State
    private int mSelectedIndex = -1;
    private int mPressedIndex = -1;
    private float mDownX;
    private float mDownY;

    // Elastic Edge Spring States
    private float mLeft = 0f;
    private float mRight = 0f;
    private float mTargetLeft = 0f;
    private float mTargetRight = 0f;
    private float mVelocityLeft = 0f;
    private float mVelocityRight = 0f;

    // Tactile Press Scale State
    private float mPressScale = 1.0f;
    private float mTargetPressScale = 1.0f;
    private float mVelocityPress = 0f;

    // Animation & Choreographer Loop
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

    public CategoryPillNavView(Context context) {
        super(context);
        init(context);
    }

    public CategoryPillNavView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CategoryPillNavView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;
        mChipHeight = 34f * density;
        mPadX = 16f * density;
        mGap = 8f * density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPillPaint.setStyle(Paint.Style.FILL);

        mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mShadowPaint.setStyle(Paint.Style.FILL);
        mShadowPaint.setColor(0x22000000); // 13% black soft drop shadow
    }

    public void setOnChipClickListener(@Nullable OnChipClickListener listener) {
        mListener = listener;
    }

    public void setHorizontalScrollView(@Nullable HorizontalScrollView scrollView) {
        mScrollView = scrollView;
    }

    public void setItems(@Nullable List<FeedStrip.Item> items, long selectedId) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        computeChipLayouts();
        requestLayout();

        int targetIndex = findIndexById(selectedId);
        if (targetIndex < 0 && !mChipLayouts.isEmpty()) {
            targetIndex = 0;
        }

        boolean initial = (mLeft == 0f && mRight == 0f) || !isAttachedToWindow();
        if (targetIndex >= 0 && targetIndex < mChipLayouts.size()) {
            mSelectedIndex = targetIndex;
            ChipLayout target = mChipLayouts.get(targetIndex);
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

    public void setSelectedId(long selectedId) {
        int index = findIndexById(selectedId);
        if (index >= 0) {
            selectIndex(index, isAttachedToWindow() && isShown());
        }
    }

    public void selectIndex(int index, boolean animate) {
        if (index < 0 || index >= mChipLayouts.size()) {
            return;
        }
        mSelectedIndex = index;
        ChipLayout target = mChipLayouts.get(index);
        mTargetLeft = target.left;
        mTargetRight = target.right;
        if (animate) {
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

    private int findIndexById(long id) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    private void computeChipLayouts() {
        mChipLayouts.clear();
        if (mItems.isEmpty()) {
            mTotalWidth = 0f;
            return;
        }
        float x = 0f;
        for (int i = 0; i < mItems.size(); i++) {
            FeedStrip.Item item = mItems.get(i);
            String label = item.label != null ? item.label.toString() : "";
            ChipLayout layout = new ChipLayout(item, label);
            float textW = mTextPaint.measureText(label);
            float chipW = textW + 2f * mPadX;
            layout.left = x;
            layout.right = x + chipW;
            layout.centerX = x + chipW * 0.5f;
            mChipLayouts.add(layout);
            x += chipW + mGap;
        }
        mTotalWidth = x > 0f ? (x - mGap) : 0f;
    }

    public void scrollToSelected(boolean smooth) {
        if (mScrollView == null || mSelectedIndex < 0 || mSelectedIndex >= mChipLayouts.size()) {
            return;
        }
        ChipLayout selected = mChipLayouts.get(mSelectedIndex);
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
        // zeta = 0.835
        // c_lead = 2 * 0.835 * sqrt(754) = 45.856
        // c_trail = 2 * 0.835 * sqrt(390) = 32.979
        float kLeft, cLeft, kRight, cRight;
        if (targetCenter > curCenter + 0.5f) {
            // Moving RIGHT: right edge is leading, left edge is trailing
            kRight = 754f;
            cRight = 45.856f;
            kLeft = 390f;
            cLeft = 32.979f;
        } else if (targetCenter < curCenter - 0.5f) {
            // Moving LEFT: left edge is leading, right edge is trailing
            kLeft = 754f;
            cLeft = 45.856f;
            kRight = 390f;
            cRight = 32.979f;
        } else {
            // Settling near target
            kLeft = 520f;
            cLeft = 38.08f;
            kRight = 520f;
            cRight = 38.08f;
        }

        // Left edge spring
        float forceLeft = -kLeft * (mLeft - mTargetLeft) - cLeft * mVelocityLeft;
        mVelocityLeft += forceLeft * dt;
        mLeft += mVelocityLeft * dt;

        // Right edge spring
        float forceRight = -kRight * (mRight - mTargetRight) - cRight * mVelocityRight;
        mVelocityRight += forceRight * dt;
        mRight += mVelocityRight * dt;

        // Enforce minimal width
        if (mRight < mLeft + mChipHeight * 0.5f) {
            mRight = mLeft + mChipHeight * 0.5f;
        }

        // Tactile press scale spring (k = 600, damping = 38)
        float forcePress = -600f * (mPressScale - mTargetPressScale) - 38f * mVelocityPress;
        mVelocityPress += forcePress * dt;
        mPressScale += mVelocityPress * dt;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int height = Math.round(52f * density);
        int width = Math.round(mTotalWidth);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mChipLayouts.isEmpty()) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float viewH = getHeight();
        float pillHeight = 34f * density;

        // Vertical travel squash: 1 - clamp((w / w_target - 1) * 0.3, 0, 0.14)
        float curW = Math.max(1f, mRight - mLeft);
        float targetW = Math.max(1f, mTargetRight - mTargetLeft);
        float stretch = Math.max(0f, (curW / targetW) - 1.0f);
        float squash = Math.min(0.14f, stretch * 0.3f);
        float sy = 1.0f - squash;

        // Strictly centered pill: top = (viewH - effectivePillH) / 2f, leaving 9dp safety buffer above & below
        float effectivePillH = pillHeight * sy * mPressScale;
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

        // --- Pass 1: Inactive Soft Muted White Text (#A3F4F4F5, 64% white) ---
        mTextPaint.setColor(0xA3F4F4F5);
        for (int i = 0; i < mChipLayouts.size(); i++) {
            ChipLayout chip = mChipLayouts.get(i);
            canvas.drawText(chip.label, chip.centerX, textY, mTextPaint);
        }

        // --- Pass 2: Active Gliding Pill Capsule (#FFFFFF -> #EBEBF0) & Drop Shadow ---
        if (mSelectedIndex >= 0 && (pillRight - pillLeft) > 2f) {
            // Subtle shadow rect
            mShadowRect.set(pillLeft - 0.5f * density, pillTop + 1.5f * density,
                    pillRight + 0.5f * density, pillBottom + 2.5f * density);
            canvas.drawRoundRect(mShadowRect, pillRadius, pillRadius, mShadowPaint);

            // Vertical gradient fill
            LinearGradient gradient = new LinearGradient(
                    0, pillTop, 0, pillBottom,
                    0xFFFFFFFF, 0xFFEBEBF0,
                    Shader.TileMode.CLAMP
            );
            mPillPaint.setShader(gradient);
            canvas.drawRoundRect(mPillRect, pillRadius, pillRadius, mPillPaint);

            // --- Pass 3: Clipped High-Contrast Dark Ink Text (#121016) ---
            mPillPath.reset();
            mPillPath.addRoundRect(mPillRect, pillRadius, pillRadius, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(mPillPath);
            mTextPaint.setColor(0xFF121016);
            for (int i = 0; i < mChipLayouts.size(); i++) {
                ChipLayout chip = mChipLayouts.get(i);
                if (chip.right >= pillLeft - 10f && chip.left <= pillRight + 10f) {
                    canvas.drawText(chip.label, chip.centerX, textY, mTextPaint);
                }
            }
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || mChipLayouts.isEmpty()) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mPressedIndex = findChipIndexAt(x);
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
                    mPressedIndex = -1;
                    mTargetPressScale = 1.0f;
                    startAnimation();
                    selectIndex(clickedIndex, true);
                    if (mListener != null && clickedIndex < mItems.size()) {
                        mListener.onChipClick(mItems.get(clickedIndex), clickedIndex);
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

    private int findChipIndexAt(float x) {
        if (mChipLayouts.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < mChipLayouts.size(); i++) {
            ChipLayout chip = mChipLayouts.get(i);
            float hitLeft = chip.left - mGap * 0.5f;
            float hitRight = chip.right + mGap * 0.5f;
            if (x >= hitLeft && x <= hitRight) {
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
