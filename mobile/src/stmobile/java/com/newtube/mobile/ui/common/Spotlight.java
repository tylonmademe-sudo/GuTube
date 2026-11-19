package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GUTUBE(spotlight): High-performance Spotlight illumination engine extending
 * {@link SpotlightTouchHelper}, fully aligned with Vercel and Linear UI specifications.
 */
public final class Spotlight extends SpotlightTouchHelper {
    public static final int SHAPE_CIRCLE = 0;
    public static final int SHAPE_BEAM = 1;

    public Spotlight() {
        super(2.5f);
    }

    public Spotlight(float density) {
        super(density);
    }

    @Override
    public Spotlight setColor(int color) {
        super.setColor(color);
        return this;
    }

    @Override
    public Spotlight setIntensity(float intensity) {
        super.setIntensity(intensity);
        return this;
    }

    @Override
    public Spotlight setBorderGlow(float borderGlow) {
        super.setBorderGlow(borderGlow);
        return this;
    }

    @Override
    public Spotlight setSoftness(float softness) {
        super.setSoftness(softness);
        return this;
    }

    @Override
    public Spotlight setSmoothing(float smoothing) {
        super.setSmoothing(smoothing);
        return this;
    }

    @Override
    public Spotlight setPressure(float pressure) {
        super.setPressure(pressure);
        return this;
    }

    @Override
    public Spotlight setSizeDp(float sizeDp) {
        super.setSizeDp(sizeDp);
        return this;
    }

    @Override
    public Spotlight setFlare(boolean flare) {
        super.setFlare(flare);
        return this;
    }

    @Override
    public Spotlight setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        return this;
    }

    @Override
    public Spotlight setDrawEdge(boolean drawEdge) {
        super.setDrawEdge(drawEdge);
        return this;
    }

    @Override
    public Spotlight setDrawSurface(boolean drawSurface) {
        super.setDrawSurface(drawSurface);
        return this;
    }

    public Spotlight setAmbient(boolean ambient) {
        return this;
    }

    public boolean isAmbient() {
        return false;
    }

    public Spotlight setGrain(float grain) {
        return this;
    }

    public float getGrain() {
        return 0f;
    }

    public Spotlight setShape(int shape) {
        return this;
    }

    public int getShape() {
        return SHAPE_CIRCLE;
    }

    public Spotlight setProximityDp(float proximityDp) {
        return this;
    }

    public float getProximityDp() {
        return 80f;
    }

    @Override
    public void release(@NonNull View view) {
        super.release(view);
    }

    // =========================================================================
    // Delegation to SpotlightTouchHelper and SpotlightHelper
    // =========================================================================

    @NonNull
    public static Spotlight attach(@NonNull View view) {
        return SpotlightHelper.attach(view);
    }

    @NonNull
    public static Spotlight attach(@NonNull View view, int color, float intensity) {
        return SpotlightHelper.attach(view, color, intensity);
    }

    @NonNull
    public static Spotlight attachButton(@NonNull View button) {
        return SpotlightHelper.attachButton(button);
    }

    @NonNull
    public static Spotlight attachCard(@NonNull View card) {
        return SpotlightHelper.attachCard(card);
    }

    @NonNull
    public static Spotlight attachNavPill(@NonNull View navPill) {
        return SpotlightHelper.attachNavPill(navPill);
    }

    public static void link(@NonNull View host, @NonNull Spotlight spotlight) {
        SpotlightHelper.link(host, spotlight);
    }

    public static void applyToCardsAndButtons(@Nullable View root) {
        SpotlightHelper.applyToCardsAndButtons(root);
    }
}
