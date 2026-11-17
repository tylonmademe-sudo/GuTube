package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * GUTUBE(spotlight): Adaptive container view that wraps any child view (cards, buttons, rows)
 * and equips it with interactive MagicBento Spotlight effect and 3D tilt spring physics.
 */
public class SpotlightView extends FrameLayout {
    private final Spotlight mSpotlight;
    private final SpotlightTilt mTilt;
    private final RectF mBounds = new RectF();
    private float mCornerRadius = 16f;
    private boolean mTrackChildren = false;
    private boolean mEnableTilt = false;

    @Nullable
    private View mActiveChild;
    private final Rect mHitRect = new Rect();

    public SpotlightView(@NonNull Context context) {
        this(context, null);
    }

    public SpotlightView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpotlightView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        mSpotlight = new Spotlight(density);
        mTilt = new SpotlightTilt(density);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SpotlightView);
        int color = a.getColor(R.styleable.SpotlightView_spotlightColor, 0xFFFFFFFF);
        float intensity = a.getFloat(R.styleable.SpotlightView_spotlightIntensity, 1.0f);
        float sizeDp = a.getDimension(R.styleable.SpotlightView_spotlightSize, 240f * density) / density;
        float softness = a.getFloat(R.styleable.SpotlightView_spotlightSoftness, 0.7f);
        float borderGlow = a.getFloat(R.styleable.SpotlightView_spotlightBorderGlow, 0.75f);
        float smoothing = a.getFloat(R.styleable.SpotlightView_spotlightSmoothing, 0.28f);
        boolean flare = a.getBoolean(R.styleable.SpotlightView_spotlightFlare, true);
        boolean ambient = a.getBoolean(R.styleable.SpotlightView_spotlightAmbient, false);
        mCornerRadius = a.getDimension(R.styleable.SpotlightView_spotlightCornerRadius, 16f * density);
        mTrackChildren = a.getBoolean(R.styleable.SpotlightView_spotlightTrackChildren, false);
        mEnableTilt = a.getBoolean(R.styleable.SpotlightView_spotlightTilt, false);
        boolean drawEdge = a.getBoolean(R.styleable.SpotlightView_spotlightDrawEdge, true);
        a.recycle();

        mSpotlight.setColor(color)
                .setIntensity(intensity)
                .setSizeDp(sizeDp)
                .setSoftness(softness)
                .setBorderGlow(borderGlow)
                .setSmoothing(smoothing)
                .setFlare(flare)
                .setAmbient(ambient)
                .setDrawEdge(drawEdge);

        mTilt.setEnabled(mEnableTilt);

        setWillNotDraw(false);
    }

    @NonNull
    public Spotlight getSpotlight() {
        return mSpotlight;
    }

    @NonNull
    public SpotlightTilt getTilt() {
        return mTilt;
    }

    public void setCornerRadius(float cornerRadiusPx) {
        mCornerRadius = cornerRadiusPx;
        invalidate();
    }

    public float getCornerRadius() {
        return mCornerRadius;
    }

    public void setTrackChildren(boolean trackChildren) {
        mTrackChildren = trackChildren;
    }

    public boolean isTrackChildren() {
        return mTrackChildren;
    }

    public void setEnableTilt(boolean enableTilt) {
        mEnableTilt = enableTilt;
        mTilt.setEnabled(enableTilt);
        if (!enableTilt) {
            mTilt.reset(this);
        }
    }

    public boolean isEnableTilt() {
        return mEnableTilt;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        mSpotlight.onTouchEvent(this, ev);

        if (mEnableTilt) {
            mTilt.updateTouch(this, ev);
        }

        if (mTrackChildren) {
            handleChildTracking(ev);
        }

        boolean handled = super.dispatchTouchEvent(ev);

        int action = ev.getActionMasked();
        if (!handled && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            return true;
        }

        return handled;
    }

    private void handleChildTracking(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        boolean inside = x >= 0f && y >= 0f && x <= getWidth() && y <= getHeight();

        if (!inside || action == MotionEvent.ACTION_CANCEL) {
            if (mActiveChild != null) {
                mActiveChild.setPressed(false);
                mActiveChild = null;
            }
            return;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            View hit = SpotlightHelper.findChildUnder(this, x, y);
            if (hit != mActiveChild) {
                if (mActiveChild != null) {
                    mActiveChild.setPressed(false);
                }
                if (hit != null && (hit.isClickable() || hit.isFocusable())) {
                    hit.setPressed(true);
                    Haptics.tick(hit);
                }
                mActiveChild = hit;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (mActiveChild != null) {
                View target = mActiveChild;
                target.getHitRect(mHitRect);
                mActiveChild.setPressed(false);
                mActiveChild = null;
                if (mHitRect.contains((int) x, (int) y) && target.isClickable()) {
                    target.performClick();
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        // Correct Z-layering: Spotlight surface light is drawn in onDraw directly above the native background,
        // strictly BEHIND all child views in dispatchDraw.
        if (mSpotlight.isVisible()) {
            float r = Math.min(mCornerRadius, Math.min(getWidth(), getHeight()) / 2f);
            mSpotlight.drawSurface(canvas, mBounds, r);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mSpotlight.isVisible() && mSpotlight.isDrawEdge()) {
            float r = Math.min(mCornerRadius, Math.min(getWidth(), getHeight()) / 2f);
            mSpotlight.drawEdge(canvas, mBounds, r);
        }
    }
}
