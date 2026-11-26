package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Random;

/**
 * GUTUBE(glass): Single master control center for ALL Liquid Glass design parameters,
 * AGSL shaders, specular rim gradients, inner glow/sheen, grain texture, and surface builders.
 *
 * <p>Editing the constants here (plus the semantic {@code gutube_*} tokens in
 * {@code colors_mobile.xml} / {@code values-night/colors_mobile.xml}) updates every glass
 * surface across the entire mobile app automatically.</p>
 */
public final class LiquidGlassEngine {

    public enum SurfaceRole {
        CHROME,
        CARD,
        SHEET,
        PILL,
        PLAYER,
        UPDATER
    }

    // -------------------------------------------------------------------------
    // Centralized Material Design Knobs
    // -------------------------------------------------------------------------

    // Blur radii
    public static final float BLUR_CHROME_DP = 7f;
    public static final float BLUR_CARD_DP = 10f;
    public static final float BLUR_PILL_DP = 24f;
    public static final float BLUR_PLAYER_DP = 12f;
    public static final float BLUR_PLAYER_PILL_DP = 28f;
    public static final float BLUR_SHEET_LIVE_PX = 47f;
    public static final int BLUR_SHEET_BOX_RADIUS = 3;
    public static final float BLUR_UPDATER_BEHIND_DP = 12f;

    // Saturation
    public static final float SATURATION_CHROME = 1.0f;
    public static final float SATURATION_PLAYER = 1.5f;
    public static final float SATURATION_SHEET = 1.8f;

    // Liquid Refraction AGSL knobs
    public static final float REFRACTION_HEIGHT_DP = 24f;
    public static final float REFRACTION_AMOUNT_DP = 24f;
    public static final float DEPTH_EFFECT = 1.0f;
    public static final float CHROMATIC_ABERRATION = 1.0f;
    public static final float WAVE_STRENGTH = 1.0f;

    // Highlight AGSL knob
    public static final float HIGHLIGHT_ANGLE = 45f;

    // Noise / Grain
    public static final int NOISE_ALPHA_VIEW = 10;
    public static final int NOISE_ALPHA_DRAWABLE = 8;

    // Rim, Sheen & Modal specs
    public static final float RIM_STROKE_DP = 1.25f;
    public static final float SHEET_RIM_STROKE_DP = 1.2f;
    public static final float INNER_GLOW_HEIGHT_DP = 40f;
    public static final float SHEET_SHEEN_HEIGHT_DP = 64f;
    public static final int FALLBACK_ALPHA = 0xEB;
    public static final float UPDATER_DIM_AMOUNT = 0.25f;

    // -------------------------------------------------------------------------
    // Shared AGSL Shaders (Kyant backdrop v2.0.0)
    // -------------------------------------------------------------------------

    private static final String ROUNDED_RECT_SDF =
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    public static final String REFRACTION_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + "uniform float waveStrength;\n"
            + ROUNDED_RECT_SDF
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    float2 liquidWave = float2(\n"
            + "        sin(coord.y * 0.015 + 92.0) * 3.5 + cos(coord.x * 0.030) * 1.5,\n"
            + "        cos(coord.x * 0.015 + 46.0) * 3.5 + sin(coord.y * 0.030) * 1.5\n"
            + "    ) * waveStrength;\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord + liquidWave);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
            + " + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad + liquidWave;\n"
            + "    float dispersionIntensity = chromaticAberration"
            + " * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    public static final String HIGHLIGHT_AGSL =
            "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + ROUNDED_RECT_SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float d = dot(grad, normal);\n"
            + "    float intensity = pow(abs(d), falloff);\n"
            + "    return color * intensity;\n"
            + "}\n";

    private static Bitmap sNoise;

    private LiquidGlassEngine() {
    }

    public static boolean isDarkTheme(@NonNull Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode != Configuration.UI_MODE_NIGHT_NO;
    }

    @NonNull
    public static Bitmap noiseBitmap() {
        if (sNoise == null) {
            int size = 96;
            int[] px = new int[size * size];
            Random random = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = random.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            sNoise = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        }
        return sNoise;
    }

    // -------------------------------------------------------------------------
    // Shared GPU RenderEffect & Shader Factory Methods
    // -------------------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.S)
    @NonNull
    public static RenderEffect createBackdropEffect(int w, int h, float radiusPx,
            float blurDp, float saturation, boolean liquid, float density) {
        float blurPx = Math.max(0.5f, blurDp * density);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation);
        RenderEffect frost = RenderEffect.createBlurEffect(
                blurPx, blurPx,
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(matrix)),
                Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !liquid || w <= 0 || h <= 0) {
            return frost;
        }
        try {
            RuntimeShader lens = createRefractionShader(w, h, radiusPx, density);
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(lens, "content"), frost);
        } catch (Throwable ignored) {
            return frost;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Nullable
    public static RenderEffect createLiquidOverlayEffect(int w, int h, float radiusPx,
            float blurDp, boolean liquid, float density) {
        float blurPx = Math.max(0.5f, blurDp * density);
        RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !liquid || w <= 0 || h <= 0) {
            return blur;
        }
        try {
            RuntimeShader lens = createRefractionShader(w, h, radiusPx, density);
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(lens, "content"), blur);
        } catch (Throwable ignored) {
            return blur;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @NonNull
    private static RuntimeShader createRefractionShader(int w, int h, float radiusPx, float density) {
        RuntimeShader lens = new RuntimeShader(REFRACTION_AGSL);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("radius", Math.min(radiusPx, Math.min(w, h) / 2f));
        lens.setFloatUniform("refractionHeight", REFRACTION_HEIGHT_DP * density);
        lens.setFloatUniform("refractionAmount", -REFRACTION_AMOUNT_DP * density);
        lens.setFloatUniform("depthEffect", DEPTH_EFFECT);
        lens.setFloatUniform("chromaticAberration", CHROMATIC_ABERRATION);
        lens.setFloatUniform("waveStrength", WAVE_STRENGTH);
        return lens;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @NonNull
    public static Shader createHighlightShader(int w, int h, float radiusPx) {
        RuntimeShader shader = new RuntimeShader(HIGHLIGHT_AGSL);
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("radius", radiusPx);
        shader.setColorUniform("color", 0x80FFFFFF);
        shader.setFloatUniform("angle", (float) Math.toRadians(HIGHLIGHT_ANGLE));
        shader.setFloatUniform("falloff", 1f);
        return shader;
    }

    @NonNull
    public static Shader createRimShader(int w, int h, boolean dark) {
        int[] colors = dark
                ? new int[] {0xB3FFFFFF, 0x33FFFFFF, 0x1AFFFFFF, 0x4DFFFFFF}
                : new int[] {0xCCFFFFFF, 0x33000000, 0x1A000000, 0x2E000000};
        return new LinearGradient(0, 0, 0, Math.max(1, h),
                colors, new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
    }

    @NonNull
    public static Shader createInnerGlowShader(int w, int h, float density, boolean dark) {
        int[] colors = dark
                ? new int[] {0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF}
                : new int[] {0x80FFFFFF, 0x26FFFFFF, 0x00FFFFFF};
        return new LinearGradient(0, 0, 0, Math.max(1f, Math.min(h, INNER_GLOW_HEIGHT_DP * density)),
                colors, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    public static void configureRimPaint(@NonNull Paint paint, float density) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(RIM_STROKE_DP * density);
    }

    @NonNull
    public static LayerDrawable createSheetRimOverlay(@NonNull Context context, float cornerPx, boolean sheenToo) {
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {ContextCompat.getColor(context, R.color.gutube_glass_sheen), 0x00FFFFFF});
        sheen.setCornerRadii(new float[] {cornerPx, cornerPx, cornerPx, cornerPx, 0, 0, 0, 0});

        GradientDrawable line = new GradientDrawable();
        line.setCornerRadius(cornerPx);
        int lineColor = ContextCompat.getColor(context, sheenToo ? R.color.gutube_glass_rim : R.color.gutube_toast_rim);
        line.setStroke(Math.max(1, Math.round(SHEET_RIM_STROKE_DP * density)), lineColor);

        LayerDrawable rim = new LayerDrawable(
                sheenToo ? new Drawable[] {sheen, line} : new Drawable[] {line});
        if (sheenToo) {
            rim.setLayerHeight(0, Math.round(SHEET_SHEEN_HEIGHT_DP * density));
            rim.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        }
        return rim;
    }

    public static void applySheetRimOverlay(@NonNull View cover, float cornerPx, boolean sheenToo) {
        if (cover.getTag(R.id.mobile_sheet_rim) != null) {
            return;
        }
        LayerDrawable rim = createSheetRimOverlay(cover.getContext(), cornerPx, sheenToo);
        rim.setBounds(0, 0, cover.getWidth(), cover.getHeight());
        cover.getOverlay().add(rim);
        cover.setTag(R.id.mobile_sheet_rim, rim);
        cover.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                rim.setBounds(0, 0, right - left, bottom - top));
    }

    @NonNull
    public static Drawable createStaticSurfaceDrawable(@NonNull Context context,
            @NonNull SurfaceRole role, float cornerDp, boolean withRipple) {
        float density = context.getResources().getDisplayMetrics().density;
        float cornerPx = cornerDp * density;

        int fillColor;
        int strokeColor;
        int strokePx = Math.max(1, Math.round(SHEET_RIM_STROKE_DP * density));
        boolean includeSheen = false;

        switch (role) {
            case UPDATER:
            case SHEET:
                fillColor = ContextCompat.getColor(context, R.color.gutube_sheet_glass);
                strokeColor = ContextCompat.getColor(context, R.color.gutube_glass_rim);
                includeSheen = true;
                break;
            case CARD:
                fillColor = ContextCompat.getColor(context, R.color.gutube_dialog_glass_tint);
                strokeColor = ContextCompat.getColor(context, R.color.gutube_glass_stroke);
                includeSheen = true;
                break;
            case PLAYER:
                fillColor = ContextCompat.getColor(context, R.color.gutube_player_glass_fill_start);
                strokeColor = ContextCompat.getColor(context, R.color.gutube_player_glass_rim);
                strokePx = Math.max(1, Math.round(1f * density));
                break;
            case CHROME:
                fillColor = ContextCompat.getColor(context, R.color.gutube_glass_tint);
                strokeColor = ContextCompat.getColor(context, R.color.gutube_glass_stroke);
                strokePx = Math.max(1, Math.round(1f * density));
                break;
            case PILL:
            default:
                fillColor = ContextCompat.getColor(context, R.color.gutube_glass_fill);
                strokeColor = ContextCompat.getColor(context, R.color.gutube_glass_stroke);
                strokePx = Math.max(1, Math.round(1f * density));
                break;
        }

        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(cornerPx);
        base.setColor(fillColor);
        base.setStroke(strokePx, strokeColor);

        Drawable surface;
        if (includeSheen) {
            GradientDrawable sheen = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {ContextCompat.getColor(context, R.color.gutube_glass_sheen), 0x00FFFFFF});
            sheen.setCornerRadii(new float[] {cornerPx, cornerPx, cornerPx, cornerPx, 0, 0, 0, 0});
            LayerDrawable layered = new LayerDrawable(new Drawable[] {base, sheen});
            layered.setLayerHeight(1, Math.round(SHEET_SHEEN_HEIGHT_DP * density));
            layered.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            surface = layered;
        } else {
            surface = base;
        }

        if (!withRipple) {
            return surface;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(cornerPx);
        mask.setColor(0xFFFFFFFF);
        int rippleColor = isDarkTheme(context) ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), surface, mask);
    }

    public static void applySurface(@NonNull View view, @NonNull SurfaceRole role,
            float cornerDp, boolean withRipple) {
        view.setBackground(createStaticSurfaceDrawable(view.getContext(), role, cornerDp, withRipple));
    }
}
