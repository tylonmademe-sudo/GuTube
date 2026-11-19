package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * GUTUBE(spotlight-card-layout): Premium Spotlight Glow ViewGroup layout,
 * similar to Vercel and Linear UI. Wraps any interactive elements (buttons, cards, menus)
 * with a continuous touch/drag spotlight glow and distinct edge tracing.
 */
public class SpotlightCardLayout extends FrameLayout {
    private final SpotlightTouchHelper mHelper;
    private final RectF mBounds = new RectF();
    private float mCornerRadius = 16f;

    public SpotlightCardLayout(@NonNull Context context) {
        this(context, null);
    }

    public SpotlightCardLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpotlightCardLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        mHelper = new SpotlightTouchHelper(density);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SpotlightCardLayout);
            int color = a.getColor(R.styleable.SpotlightCardLayout_spotlightColor, 0xFFFFFFFF);
            float intensity = a.getFloat(R.styleable.SpotlightCardLayout_spotlightIntensity, 0.06f);
            float edgeIntensity = a.getFloat(R.styleable.SpotlightCardLayout_spotlightBorderGlow, 0.70f);
            float sizeDp = a.getDimension(R.styleable.SpotlightCardLayout_spotlightSize, 240f * density) / density;
            float softness = a.getFloat(R.styleable.SpotlightCardLayout_spotlightSoftness, 1.0f);
            float smoothing = a.getFloat(R.styleable.SpotlightCardLayout_spotlightSmoothing, 0.3f);
            mCornerRadius = a.getDimension(R.styleable.SpotlightCardLayout_spotlightCornerRadius, 16f * density);
            boolean drawEdge = a.getBoolean(R.styleable.SpotlightCardLayout_spotlightDrawEdge, true);
            a.recycle();

            mHelper.setColor(color)
                    .setIntensity(intensity)
                    .setBorderGlow(0.0f)
                    .setSizeDp(sizeDp)
                    .setSoftness(softness)
                    .setSmoothing(smoothing)
                    .setDrawEdge(false);
        } else {
            mCornerRadius = 16f * density;
        }

        setWillNotDraw(false);
    }

    @NonNull
    public SpotlightTouchHelper getHelper() {
        return mHelper;
    }

    public void setCornerRadius(float radiusPx) {
        mCornerRadius = radiusPx;
        invalidate();
    }

    public float getCornerRadius() {
        return mCornerRadius;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        mHelper.onTouchEvent(this, ev);
        boolean handled = super.dispatchTouchEvent(ev);
        int action = ev.getActionMasked();
        if (!handled && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            return true;
        }
        return handled;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0, 0, w, h);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mHelper.isVisible() && !mBounds.isEmpty()) {
            mHelper.draw(canvas, mBounds, mCornerRadius);
        }
    }
}
