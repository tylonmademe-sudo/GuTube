package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

/**
 * GUTUBE(liquid-glass): Modal pipeline delegating all blur, dim, and AGSL liquid refraction
 * parameters to {@link LiquidGlassEngine}.
 */
public final class LiquidGlass {

    private LiquidGlass() {
    }

    /** Configures the dialog window for crystal-clear transparency and real live backdrop blur. */
    public static void configureWindow(@Nullable Dialog dialog, @NonNull Activity activity) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(LiquidGlassEngine.UPDATER_DIM_AMOUNT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && Glass.isLiquid(activity) && !BatterySaver.lite()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                int blurPx = Math.round(LiquidGlassEngine.BLUR_UPDATER_BEHIND_DP
                        * activity.getResources().getDisplayMetrics().density);
                window.getAttributes().setBlurBehindRadius(blurPx);
            }
        }
    }

    /** Clears Material's opaque grey MaterialShapeDrawable on design_bottom_sheet. */
    public static void configureBottomSheetContainer(@Nullable View bottomSheet) {
        if (bottomSheet == null) return;
        bottomSheet.setBackgroundResource(android.R.color.transparent);
        bottomSheet.setBackground(null);
        ViewCompat.setBackgroundTintList(bottomSheet, ColorStateList.valueOf(Color.TRANSPARENT));
        if (bottomSheet instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) bottomSheet;
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
        }
    }

    /**
     * Applies unified {@link LiquidGlassEngine} backdrop refraction/blur on {@code glassBgView},
     * respecting {@link Glass#isLiquid(Context)} and {@link BatterySaver#lite()}.
     */
    public static void applyLiquidEffect(@Nullable View glassBgView) {
        if (glassBgView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Context context = glassBgView.getContext();
        if (!Glass.isLiquid(context) || BatterySaver.lite()) {
            glassBgView.setRenderEffect(null);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        float cornerPx = 28f * density;
        int w = glassBgView.getWidth();
        int h = glassBgView.getHeight();
        if (w > 0 && h > 0) {
            RenderEffect effect = LiquidGlassEngine.createLiquidOverlayEffect(
                    w, h, cornerPx, LiquidGlassEngine.BLUR_UPDATER_BEHIND_DP, true, density);
            glassBgView.setRenderEffect(effect);
        } else {
            RenderEffect initial = LiquidGlassEngine.createLiquidOverlayEffect(
                    0, 0, cornerPx, LiquidGlassEngine.BLUR_UPDATER_BEHIND_DP, false, density);
            glassBgView.setRenderEffect(initial);
            glassBgView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                        int ol, int ot, int or_, int ob) {
                    int nw = r - l;
                    int nh = b - t;
                    if (nw > 0 && nh > 0) {
                        v.removeOnLayoutChangeListener(this);
                        v.setRenderEffect(LiquidGlassEngine.createLiquidOverlayEffect(
                                nw, nh, cornerPx, LiquidGlassEngine.BLUR_UPDATER_BEHIND_DP, true, density));
                    }
                }
            });
        }
    }
}
