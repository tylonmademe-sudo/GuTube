package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

/**
 * GUTUBE(liquid-glass): High-fidelity Liquid Glass modal pipeline.
 * Translates CSS/SVG Liquid Glass specification to native Android:
 * - Real backdrop blur (Window blurBehindRadius ~10dp / 8-12dp)
 * - True crystal-clear transparency (rgba(255, 255, 255, 0.04) to rgba(255, 255, 255, 0.08))
 * - AGSL RuntimeShader liquid distortion (API 33+) replicating feTurbulence (baseFrequency 0.006)
 *   and feDisplacementMap (scale 45) on the glass background layer
 * - Pure frosted RenderEffect blur fallback (API 31-32)
 * - Crisp inner white specular border (inset 0 0 6px -3px rgba(255, 255, 255, 0.7))
 * - Soft white outer glow rim (0px 0px 21px -8px rgba(255, 255, 255, 0.3))
 */
public final class LiquidGlass {

    /**
     * Lightweight AGSL RuntimeShader (Android 13+ / API 33+):
     * Replicates SVG feTurbulence (baseFrequency 0.006) + feDisplacementMap (scale 45)
     * using a 2-octave smooth harmonic sinusoidal wave calculation.
     */
    private static final String LIQUID_DISTORTION_AGSL =
            "uniform shader content;\n"
            + "float2 turbulence(float2 p) {\n"
            + "    const float f = 0.006;\n"
            + "    float2 w1 = float2(\n"
            + "        sin(p.y * f * 6.28318 + 1.7) + cos(p.x * f * 6.28318 * 0.8 + 0.5),\n"
            + "        cos(p.x * f * 6.28318 + 2.3) + sin(p.y * f * 6.28318 * 0.7 + 1.1)\n"
            + "    );\n"
            + "    float2 w2 = float2(\n"
            + "        sin(p.y * 2.1 * f * 6.28318 + 3.1) * 0.5,\n"
            + "        cos(p.x * 2.3 * f * 6.28318 + 4.2) * 0.5\n"
            + "    );\n"
            + "    return (w1 + w2) * 0.5;\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 disp = turbulence(coord) * 4.5;\n"
            + "    return content.eval(coord + disp);\n"
            + "}\n";

    private LiquidGlass() {
    }

    /**
     * Configures the dialog window for crystal-clear transparency and real live backdrop blur.
     */
    public static void configureWindow(@Nullable Dialog dialog, @NonNull Activity activity) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // Subtle 25% dim so the blurred feed behind is clearly visible through the glass sheet
            window.setDimAmount(0.25f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                int blurPx = Math.round(10 * activity.getResources().getDisplayMetrics().density); // ~8dp-12dp blur
                window.getAttributes().setBlurBehindRadius(blurPx);
            }
        }
    }

    /**
     * Clears Material's opaque grey MaterialShapeDrawable on design_bottom_sheet so the sheet is truly transparent.
     */
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
     * Applies AGSL RuntimeShader liquid distortion (API 33+) or RenderEffect blur fallback (API 31+)
     * on the glass background layer so all text and buttons remain 100% crisp and unrefracted.
     */
    public static void applyLiquidEffect(@Nullable View glassBgView) {
        if (glassBgView == null) return;
        Context context = glassBgView.getContext();
        float density = context.getResources().getDisplayMetrics().density;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader shader = new RuntimeShader(LIQUID_DISTORTION_AGSL);
                RenderEffect effect = RenderEffect.createRuntimeShaderEffect(shader, "content");
                glassBgView.setRenderEffect(effect);
                return;
            } catch (Throwable ignored) {
                // Graceful fallback to pure frosted blur on error
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                float blurPx = 10 * density;
                RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);
                glassBgView.setRenderEffect(blur);
            } catch (Throwable ignored) {
            }
        }
    }
}
