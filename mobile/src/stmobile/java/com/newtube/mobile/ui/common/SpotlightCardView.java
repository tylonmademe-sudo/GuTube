package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
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
 * GUTUBE(spotlight-card): First-class card container equipped with MagicBento spotlight
 * illumination (.magic-bento__light), continuous drag tracking, and 3D spring tilt physics.
 *
 * <p>Key Architecture & Fixes:
 * <ul>
 *   <li><b>Z-Layer Hierarchy:</b> The spotlight surface canvas is rendered in {@link #onDraw(Canvas)}
 *       between the card's native background and its child content. Child views (text, icons, images)
 *       render cleanly above the light canvas without milkiness or double boxes;</li>
 *   <li><b>No Duplicate Backgrounds:</b> Renders exclusively on the card's existing background drawable
 *       without synthesizing secondary border outlines;</li>
 *   <li><b>Continuous Drag Tracking:</b> {@link MotionEvent#ACTION_MOVE} streams continuous coordinates
 *       into the physics engine to maintain a fluid, living glow across the card;</li>
 *   <li><b>3D Tilt Spring Physics:</b> Real-time rx/ry perspective tilting (max 6° by default) driven
 *       by a mass-spring interpolator (stiffness = 170, damping = 20) with 240Hz sub-stepping.</li>
 * </ul>
 */
public class SpotlightCardView extends FrameLayout {
    private final Spotlight mSpotlight;
    private final SpotlightTilt mTilt;
    private final RectF mBounds = new RectF();
    private final Path mClipPath = new Path();
    private float mCornerRadius = 16f;
    private boolean mTrackChildren = false;
    private boolean mEnableTilt = true;

    @Nullable
    private View mActiveChild;
    private final Rect mHitRect = new Rect();

    public SpotlightCardView(@NonNull Context context) {
        this(context, null);
    }

    public SpotlightCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpotlightCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        mSpotlight = new Spotlight(density);
        mTilt = new SpotlightTilt(density);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SpotlightCardView);
        int color = a.getColor(R.styleable.SpotlightCardView_spotlightColor, 0xFFFFFFFF);
        float intensity = a.getFloat(R.styleable.SpotlightCardView_spotlightIntensity, 0.06f);
        float sizeDp = a.getDimension(R.styleable.SpotlightCardView_spotlightSize, 260f * density) / density;
        float softness = a.getFloat(R.styleable.SpotlightCardView_spotlightSoftness, 0.7f);
        float borderGlow = a.getFloat(R.styleable.SpotlightCardView_spotlightBorderGlow, 0.0f);
        float smoothing = a.getFloat(R.styleable.SpotlightCardView_spotlightSmoothing, 0.20f);
        boolean flare = a.getBoolean(R.styleable.SpotlightCardView_spotlightFlare, true);
        boolean ambient = a.getBoolean(R.styleable.SpotlightCardView_spotlightAmbient, false);
        mCornerRadius = a.getDimension(R.styleable.SpotlightCardView_spotlightCornerRadius, 16f * density);
        mTrackChildren = a.getBoolean(R.styleable.SpotlightCardView_spotlightTrackChildren, false);
        mEnableTilt = a.getBoolean(R.styleable.SpotlightCardView_spotlightTilt, true);
        boolean drawEdge = a.getBoolean(R.styleable.SpotlightCardView_spotlightDrawEdge, true);
        a.recycle();

        mSpotlight.setColor(color)
                .setIntensity(intensity)
                .setSizeDp(sizeDp)
                .setSoftness(softness)
                .setBorderGlow(0.0f)
                .setSmoothing(smoothing)
                .setFlare(flare)
                .setAmbient(ambient)
                .setDrawEdge(false);
        mSpotlight.updateTheme(context);

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

        // Ensure we continuously receive ACTION_MOVE events during drag gestures even if no child consumes
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
        // Correct Z-layering: Spotlight surface light is drawn in onDraw directly above the card's
        // native background, but strictly BEHIND all child views in dispatchDraw.
        if (mSpotlight.isVisible()) {
            float r = Math.min(mCornerRadius, Math.min(getWidth(), getHeight()) / 2f);
            int save = -1;
            if (r > 0f) {
                save = canvas.save();
                mClipPath.reset();
                mClipPath.addRoundRect(mBounds, r, r, Path.Direction.CW);
                canvas.clipPath(mClipPath);
            }
            mSpotlight.drawSurface(canvas, mBounds, r);
            if (save >= 0) {
                canvas.restoreToCount(save);
            }
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        float r = Math.min(mCornerRadius, Math.min(getWidth(), getHeight()) / 2f);
        int save = -1;
        if (r > 0f) {
            save = canvas.save();
            mClipPath.reset();
            mClipPath.addRoundRect(mBounds, r, r, Path.Direction.CW);
            canvas.clipPath(mClipPath);
        }
        super.dispatchDraw(canvas);
        if (save >= 0) {
            canvas.restoreToCount(save);
        }
    }
}
