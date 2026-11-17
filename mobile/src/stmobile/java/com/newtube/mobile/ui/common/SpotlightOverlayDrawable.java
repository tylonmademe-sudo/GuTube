package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
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
 * GUTUBE(spotlight): High-performance overlay drawable that renders SpotlightTouchHelper glow
 * on top of any View without modifying its layout or hierarchy.
 */
public final class SpotlightOverlayDrawable extends Drawable {
    private final View mHost;
    private final SpotlightTouchHelper mHelper;
    private final RectF mBounds = new RectF();
    private float mCornerRadius = -1f;

    public SpotlightOverlayDrawable(@NonNull View host, @NonNull SpotlightTouchHelper helper) {
        mHost = host;
        mHelper = helper;
    }

    public void setCornerRadius(float cornerRadius) {
        mCornerRadius = cornerRadius;
    }

    public void setDrawEdge(boolean drawEdge) {
        mHelper.setDrawEdge(drawEdge);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!mHelper.isVisible() || !mHost.isAttachedToWindow()) {
            return;
        }

        mBounds.set(0, 0, mHost.getWidth(), mHost.getHeight());
        if (mBounds.isEmpty()) {
            return;
        }

        // Disable edge rim stroke on list items to prevent rectangular box outlines over cells
        if (mHost.getParent() instanceof androidx.recyclerview.widget.RecyclerView
                || mHost.getParent() instanceof android.widget.AbsListView
                || (mHost.getParent() != null && mHost.getParent().getParent() instanceof androidx.recyclerview.widget.RecyclerView)) {
            mHelper.setDrawEdge(false);
        }

        float radius = resolveRadius();
        mHelper.draw(canvas, mBounds, radius);
    }

    private float resolveRadius() {
        if (mCornerRadius >= 0f) {
            return mCornerRadius;
        }

        Drawable bg = mHost.getBackground();
        if (bg instanceof GradientDrawable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                float r = ((GradientDrawable) bg).getCornerRadius();
                if (r > 0f) {
                    return r;
                }
            }
        } else if (bg instanceof RippleDrawable) {
            RippleDrawable rd = (RippleDrawable) bg;
            if (rd.getNumberOfLayers() > 0) {
                Drawable layer0 = rd.getDrawable(0);
                if (layer0 instanceof GradientDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    float r = ((GradientDrawable) layer0).getCornerRadius();
                    if (r > 0f) {
                        return r;
                    }
                }
            }
        }

        return SpotlightTouchHelper.getAdaptiveCornerRadius(mHost, mBounds);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mBounds.set(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
