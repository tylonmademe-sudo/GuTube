package com.newtube.mobile.ui.common;

import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * GUTUBE(spotlight-dispatcher): Universal touch routing engine inspired by ColorOS 17.
 *
 * <p>Key Architecture:
 * <ul>
 *   <li><b>Universal Interaction:</b> Automatically discovers interactive UI elements (buttons,
 *       cards, chips, list rows, tabs, switches) under the finger on every touch and drag gesture;</li>
 *   <li><b>Dynamic Touch Origin:</b> The spotlight originates exactly at the user's touch coordinates
 *       and follows smoothly across the surface in real time;</li>
 *   <li><b>Continuous Drag Tracking:</b> When dragging across buttons, cards, or list items, the light
 *       softly flows from the leaving element into the newly entered element;</li>
 *   <li><b>Zero Interference:</b> Does not intercept or block gestures; scrolling, clicking, long presses,
 *       and custom OnTouchListeners continue completely unhampered;</li>
 *   <li><b>Integrated 3D Tilt:</b> Forwards touch events to {@link SpotlightTilt} on supported views
 *       so the card micro-tilts toward the finger while the spotlight illuminates the surface.</li>
 * </ul>
 */
public final class SpotlightDispatcher {
    @Nullable
    private static WeakReference<View> sActiveView;
    private static final int[] sLoc = new int[2];

    private SpotlightDispatcher() {
    }

    /**
     * Dispatches window-level MotionEvents to the interactive element under the user's finger.
     * Called from {@link MobileActivity#dispatchTouchEvent}, {@link Haptics#watch(android.app.Dialog)},
     * and {@link Haptics#watch(android.widget.PopupWindow)}.
     */
    public static void dispatch(@Nullable View decorView, @NonNull MotionEvent event) {
        if (decorView == null || BatterySaver.lite()
                || !Glass.isSpotlight(decorView.getContext())) {
            return;
        }

        int action = event.getActionMasked();
        float wx = event.getX();
        float wy = event.getY();
        float rawPressure = event.getPressure();
        float pressure = (rawPressure > 0.01f) ? Math.max(0.1f, Math.min(1.2f, rawPressure)) : 0.5f;

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                View target = findInteractiveTarget(decorView, wx, wy);
                if (target != null) {
                    sActiveView = new WeakReference<>(target);
                    SpotlightTouchHelper helper = getOrCreateHelper(target);
                    target.getLocationInWindow(sLoc);
                    float lx = wx - sLoc[0];
                    float ly = wy - sLoc[1];
                    helper.onTouch(target, MotionEvent.ACTION_DOWN, lx, ly, pressure);

                    SpotlightTilt tilt = SpotlightHelper.getTilt(target);
                    if (tilt != null && tilt.isEnabled()) {
                        tilt.updateTouch(target, action, lx, ly);
                    }
                } else {
                    sActiveView = null;
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                View active = (sActiveView != null) ? sActiveView.get() : null;
                if (active != null && active.getVisibility() == View.VISIBLE) {
                    active.getLocationInWindow(sLoc);
                    float lx = wx - sLoc[0];
                    float ly = wy - sLoc[1];
                    boolean inside = lx >= 0 && ly >= 0 && lx <= active.getWidth() && ly <= active.getHeight();

                    if (inside) {
                        SpotlightTouchHelper helper = getOrCreateHelper(active);
                        helper.onTouch(active, MotionEvent.ACTION_MOVE, lx, ly, pressure);

                        SpotlightTilt tilt = SpotlightHelper.getTilt(active);
                        if (tilt != null && tilt.isEnabled()) {
                            tilt.updateTouch(active, MotionEvent.ACTION_MOVE, lx, ly);
                        }
                    } else {
                        // Dragged outside active element: check if dragging into adjacent interactive element
                        View newTarget = findInteractiveTarget(decorView, wx, wy);
                        if (newTarget != active) {
                            SpotlightTouchHelper oldHelper = SpotlightTouchHelper.get(active);
                            if (oldHelper != null) {
                                oldHelper.onTouch(active, MotionEvent.ACTION_CANCEL, lx, ly, 0.5f);
                            }
                            SpotlightTilt oldTilt = SpotlightHelper.getTilt(active);
                            if (oldTilt != null && oldTilt.isEnabled()) {
                                oldTilt.springToNeutral(active);
                            }

                            if (newTarget != null) {
                                sActiveView = new WeakReference<>(newTarget);
                                SpotlightTouchHelper newHelper = getOrCreateHelper(newTarget);
                                newTarget.getLocationInWindow(sLoc);
                                float nlx = wx - sLoc[0];
                                float nly = wy - sLoc[1];
                                newHelper.onTouch(newTarget, MotionEvent.ACTION_DOWN, nlx, nly, pressure);

                                SpotlightTilt newTilt = SpotlightHelper.getTilt(newTarget);
                                if (newTilt != null && newTilt.isEnabled()) {
                                    newTilt.updateTouch(newTarget, MotionEvent.ACTION_DOWN, nlx, nly);
                                }
                            } else {
                                sActiveView = null;
                            }
                        }
                    }
                } else {
                    View newTarget = findInteractiveTarget(decorView, wx, wy);
                    if (newTarget != null) {
                        sActiveView = new WeakReference<>(newTarget);
                        SpotlightTouchHelper newHelper = getOrCreateHelper(newTarget);
                        newTarget.getLocationInWindow(sLoc);
                        float nlx = wx - sLoc[0];
                        float nly = wy - sLoc[1];
                        newHelper.onTouch(newTarget, MotionEvent.ACTION_DOWN, nlx, nly, pressure);

                        SpotlightTilt newTilt = SpotlightHelper.getTilt(newTarget);
                        if (newTilt != null && newTilt.isEnabled()) {
                            newTilt.updateTouch(newTarget, MotionEvent.ACTION_DOWN, nlx, nly);
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP: {
                View active = (sActiveView != null) ? sActiveView.get() : null;
                if (active != null) {
                    active.getLocationInWindow(sLoc);
                    float lx = wx - sLoc[0];
                    float ly = wy - sLoc[1];
                    SpotlightTouchHelper helper = SpotlightTouchHelper.get(active);
                    if (helper != null) {
                        helper.onTouch(active, MotionEvent.ACTION_UP, lx, ly, 0.5f);
                    }
                    SpotlightTilt tilt = SpotlightHelper.getTilt(active);
                    if (tilt != null && tilt.isEnabled()) {
                        tilt.updateTouch(active, MotionEvent.ACTION_UP, lx, ly);
                    }
                    sActiveView = null;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                View active = (sActiveView != null) ? sActiveView.get() : null;
                if (active != null) {
                    active.getLocationInWindow(sLoc);
                    float lx = wx - sLoc[0];
                    float ly = wy - sLoc[1];
                    SpotlightTouchHelper helper = SpotlightTouchHelper.get(active);
                    if (helper != null) {
                        helper.onTouch(active, MotionEvent.ACTION_CANCEL, lx, ly, 0.5f);
                    }
                    SpotlightTilt tilt = SpotlightHelper.getTilt(active);
                    if (tilt != null && tilt.isEnabled()) {
                        tilt.springToNeutral(active);
                    }
                    sActiveView = null;
                }
                break;
            }
        }
    }

    /**
     * Resolves the target view's existing {@link SpotlightTouchHelper} or creates/attaches one dynamically.
     */
    @NonNull
    public static SpotlightTouchHelper getOrCreateHelper(@NonNull View target) {
        if (target instanceof GlassView) {
            return ((GlassView) target).getSpotlight();
        }
        if (target instanceof SpotlightCardView) {
            return ((SpotlightCardView) target).getSpotlight();
        }
        if (target instanceof SpotlightView) {
            return ((SpotlightView) target).getSpotlight();
        }

        SpotlightTouchHelper existing = SpotlightTouchHelper.get(target);
        if (existing != null) {
            return existing;
        }

        return SpotlightHelper.attach(target);
    }

    /**
     * Traverses the view hierarchy to find the deepest visible interactive UI element at (wx, wy).
     */
    @Nullable
    public static View findInteractiveTarget(@Nullable View root, float wx, float wy) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }

        root.getLocationInWindow(sLoc);
        int left = sLoc[0];
        int top = sLoc[1];
        int right = left + root.getWidth();
        int bottom = top + root.getHeight();

        if (wx < left || wx > right || wy < top || wy > bottom) {
            return null;
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                View hit = findInteractiveTarget(child, wx, wy);
                if (hit != null) {
                    return hit;
                }
            }
        }

        if (isInteractive(root)) {
            return root;
        }

        return null;
    }

    /**
     * Identifies whether a View is an interactive element (button, card, chip, row, switch).
     */
    public static boolean isInteractive(@Nullable View v) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isEnabled()) {
            return false;
        }
        if (v.getId() == android.R.id.content) {
            return false;
        }

        // Avoid rendering over active video playback surfaces
        if (v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) {
            return false;
        }
        String className = v.getClass().getSimpleName();
        if (className.contains("Surface") || className.contains("Texture") || className.equals("PlayerView")) {
            return false;
        }

        // Avoid lighting up whole scrolling containers (only the items inside them)
        if (v instanceof androidx.recyclerview.widget.RecyclerView
                || v instanceof android.widget.AbsListView
                || v instanceof android.widget.ScrollView
                || v instanceof androidx.core.widget.NestedScrollView
                || v instanceof androidx.viewpager2.widget.ViewPager2) {
            return false;
        }

        // Avoid text input fields that handle their own text cursor & selection
        if (v instanceof android.widget.EditText) {
            return false;
        }

        // First-class spotlight & glass containers
        if (v instanceof GlassView || v instanceof SpotlightCardView || v instanceof SpotlightView) {
            return true;
        }

        // Standard interactive controls
        if (v.isClickable() || v.isLongClickable()) {
            return true;
        }
        if (v instanceof android.widget.Checkable) {
            return true;
        }
        if (v instanceof android.widget.Button || v instanceof android.widget.ImageButton) {
            return true;
        }
        if (v instanceof com.google.android.material.chip.Chip) {
            return true;
        }
        if (v instanceof com.google.android.material.card.MaterialCardView
                || v instanceof androidx.cardview.widget.CardView) {
            return true;
        }
        if (className.contains("Card") || className.contains("Button") || className.contains("Chip")
                || className.contains("Tab") || className.contains("ItemView")) {
            return true;
        }
        if (v.isFocusable()) {
            return true;
        }

        return SpotlightTouchHelper.get(v) != null;
    }
}
