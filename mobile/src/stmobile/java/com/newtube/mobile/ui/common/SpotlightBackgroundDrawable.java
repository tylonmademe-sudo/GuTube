package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GUTUBE(spotlight-bg): High-performance background wrapper drawable that renders
 * SpotlightTouchHelper illumination behind view content and text, eliminating Z-index
 * text occlusion caused by ViewOverlay.
 */
public final class SpotlightBackgroundDrawable extends Drawable implements Drawable.Callback {
    private final View mHost;
    private final SpotlightTouchHelper mHelper;
    private final Drawable mOriginalBackground;
    private final RectF mBounds = new RectF();
    private final Path mClipPath = new Path();
    private float mCornerRadius = -1f;

    public SpotlightBackgroundDrawable(@NonNull View host, @NonNull SpotlightTouchHelper helper, @Nullable Drawable originalBackground) {
        mHost = host;
        mHelper = helper;
        mOriginalBackground = originalBackground;
        if (mOriginalBackground != null) {
            mOriginalBackground.setCallback(this);
            if (host.getWidth() > 0 && host.getHeight() > 0) {
                mOriginalBackground.setBounds(0, 0, host.getWidth(), host.getHeight());
            }
        }
    }

    public void setCornerRadius(float cornerRadius) {
        mCornerRadius = cornerRadius;
    }

    public void setDrawEdge(boolean drawEdge) {
        mHelper.setDrawEdge(drawEdge);
    }

    @Nullable
    public Drawable getOriginalBackground() {
        return mOriginalBackground;
    }

    @Override
    public void getOutline(@NonNull android.graphics.Outline outline) {
        if (mOriginalBackground != null) {
            mOriginalBackground.getOutline(outline);
        } else {
            float radius = resolveRadius();
            if (mHost.getWidth() > 0 && mHost.getHeight() > 0) {
                if (radius > 0f) {
                    outline.setRoundRect(0, 0, mHost.getWidth(), mHost.getHeight(), radius);
                } else {
                    outline.setRect(0, 0, mHost.getWidth(), mHost.getHeight());
                }
                outline.setAlpha(1.0f);
            } else {
                super.getOutline(outline);
            }
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        if (mOriginalBackground != null) {
            mOriginalBackground.setVisible(visible, restart);
        }
        return super.setVisible(visible, restart);
    }

    @Override
    public int getIntrinsicWidth() {
        return mOriginalBackground != null ? mOriginalBackground.getIntrinsicWidth() : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return mOriginalBackground != null ? mOriginalBackground.getIntrinsicHeight() : -1;
    }

    @Nullable
    @Override
    public ConstantState getConstantState() {
        return mOriginalBackground != null ? mOriginalBackground.getConstantState() : super.getConstantState();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        // Spotlight disabled across whole app: render original background only
        if (mOriginalBackground != null) {
            mOriginalBackground.draw(canvas);
        }
    }

    // Ensure standard drawable methods are forwarded to the original background
    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        if (mOriginalBackground != null) {
            mOriginalBackground.setBounds(bounds);
        }
        mBounds.set(bounds);
    }

    @Override
    public boolean isStateful() {
        return mOriginalBackground != null && mOriginalBackground.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mOriginalBackground != null) {
            boolean changed = mOriginalBackground.setState(state);
            if (changed) {
                invalidateSelf();
            }
            return changed;
        }
        return false;
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        if (mOriginalBackground != null) {
            mOriginalBackground.jumpToCurrentState();
        }
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        if (mOriginalBackground != null) {
            return mOriginalBackground.getPadding(padding);
        }
        return super.getPadding(padding);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mOriginalBackground != null) {
            mOriginalBackground.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (mOriginalBackground != null) {
            mOriginalBackground.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return mOriginalBackground != null ? mOriginalBackground.getOpacity() : PixelFormat.TRANSLUCENT;
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }

    private float resolveRadius() {
        if (mCornerRadius >= 0f) {
            return mCornerRadius;
        }

        Drawable bg = mOriginalBackground != null ? mOriginalBackground : mHost.getBackground();
        float r = extractRadius(bg);
        if (r > 0f) {
            return r;
        }

        return SpotlightTouchHelper.getAdaptiveCornerRadius(mHost, mBounds);
    }

    private static float extractRadius(@Nullable Drawable d) {
        if (d == null) {
            return -1f;
        }
        if (d instanceof GradientDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return ((GradientDrawable) d).getCornerRadius();
        }
        if (d instanceof RippleDrawable) {
            RippleDrawable rd = (RippleDrawable) d;
            if (rd.getNumberOfLayers() > 0) {
                float r = extractRadius(rd.getDrawable(0));
                if (r > 0f) return r;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && d instanceof android.graphics.drawable.InsetDrawable) {
            android.graphics.drawable.InsetDrawable id = (android.graphics.drawable.InsetDrawable) d;
            float r = extractRadius(id.getDrawable());
            if (r > 0f) return r;
        }
        if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
            if (ld.getNumberOfLayers() > 0) {
                float r = extractRadius(ld.getDrawable(0));
                if (r > 0f) return r;
            }
        }
        if (d instanceof android.graphics.drawable.StateListDrawable) {
            Drawable current = d.getCurrent();
            if (current != null && current != d) {
                float r = extractRadius(current);
                if (r > 0f) return r;
            }
        }
        return -1f;
    }

    private static boolean extractInsets(@Nullable Drawable d, @NonNull Rect outInsets) {
        if (d == null) {
            return false;
        }
        if (d.getPadding(outInsets) && (outInsets.left > 0 || outInsets.top > 0 || outInsets.right > 0 || outInsets.bottom > 0)) {
            return true;
        }
        if (d instanceof android.graphics.drawable.StateListDrawable) {
            Drawable current = d.getCurrent();
            if (current != null && current != d) {
                return extractInsets(current, outInsets);
            }
        }
        return false;
    }
}
