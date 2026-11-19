package com.newtube.mobile.ui.common;

import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

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
    @Nullable
    private static WeakReference<View> sHoveredMenuItem;
    private static final int[] sLoc = new int[2];

    private SpotlightDispatcher() {
    }

    /**
     * Dispatches window-level MotionEvents to the interactive element under the user's finger.
     * Called from {@link MobileActivity#dispatchTouchEvent}, {@link Haptics#watch(android.app.Dialog)},
     * and {@link Haptics#watch(android.widget.PopupWindow)}.
     */
    public static void dispatch(@Nullable View decorView, @NonNull MotionEvent event) {
        // Disabled across whole app per user request
    }

    private static void updateMenuHover(@NonNull View menu, float lx, float ly) {
        View hit = null;
        if (menu instanceof RecyclerView) {
            hit = ((RecyclerView) menu).findChildViewUnder(lx, ly);
        } else if (menu instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) menu;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE) {
                    if (lx >= child.getLeft() && lx <= child.getRight()
                            && ly >= child.getTop() && ly <= child.getBottom()) {
                        hit = child;
                        break;
                    }
                }
            }
        }

        View current = (sHoveredMenuItem != null) ? sHoveredMenuItem.get() : null;
        if (hit != current) {
            if (current != null) {
                current.setPressed(false);
            }
            if (hit != null && (hit.isClickable() || hit.isFocusable())) {
                hit.setPressed(true);
                Haptics.tick(hit);
            }
            sHoveredMenuItem = (hit != null) ? new WeakReference<>(hit) : null;
        }
    }

    private static void clearMenuHover() {
        if (sHoveredMenuItem != null) {
            View current = sHoveredMenuItem.get();
            if (current != null) {
                current.setPressed(false);
            }
            sHoveredMenuItem = null;
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

        if (isMenuContainer(target) && target instanceof ViewGroup) {
            SpotlightHelper.attachMenu((ViewGroup) target);
            SpotlightTouchHelper helper = SpotlightTouchHelper.get(target);
            if (helper != null) {
                return helper;
            }
        }

        if (SpotlightHelper.isButtonLike(target)) {
            return SpotlightHelper.attachButton(target);
        }

        if (isCardOrCompound(target)) {
            return SpotlightHelper.attachCard(target);
        }

        return SpotlightHelper.attach(target);
    }

    /**
     * Traverses the view hierarchy to find the deepest visible interactive UI element at (wx, wy).
     *
     * <p>Features:
     * <ul>
     *   <li><b>Menu Containers (Fix 1):</b> In scrollable menus and bottom sheets, attaches to the
     *       parent ViewGroup so the gradient tracks fluidly without clipping to individual rows;</li>
     *   <li><b>Restricted Glow (Fix 2):</b> Transparent full-screen backgrounds, scrims, and overlays
     *       are strictly excluded so light only renders on interactive surfaces;</li>
     *   <li><b>Full Bounds Coverage (Fix 3):</b> Resolves compound buttons and pills to their container
     *       and maps coordinates to the button's local coordinate system so icons are fully illuminated.</li>
     * </ul>
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

        // Bottom Navigation container routing: illuminate the floating glass capsule
        if (root.getId() == R.id.mobile_bottom_nav) {
            View parent = (View) root.getParent();
            if (parent != null && parent.getId() == R.id.mobile_nav_glass) {
                return parent;
            }
        }

        // Cast sheet background container exclusion: glow strictly on buttons/rows, not sheet panel
        if (root.getId() == R.id.cast_sheet_list
                || root.getId() == R.id.cast_sheet_targets
                || root.getId() == R.id.cast_sheet_chooser
                || root.findViewById(R.id.cast_sheet_list) != null) {
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
            return null;
        }

        // 1. Menu container handling (Fix 1: Touch Tracking in Scrollable Menus - No Clipping)
        if (isMenuContainer(root)) {
            return root;
        }

        // 2. Card / Compound Button handling (Fix 3: Full Bounds & Icon Coverage)
        if (isCardOrCompound(root)) {
            return root;
        }

        // 3. Child traversal for non-menu, non-card containers
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

        // 4. Standalone interactive element check
        if (isInteractive(root)) {
            return root;
        }

        return null;
    }

    /**
     * Identifies whether a View is a Menu container (e.g. RecyclerView inside a bottom sheet or dialog,
     * or tracked via {@link SpotlightHelper#attachMenu}).
     */
    public static boolean isMenuContainer(@Nullable View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        // Exclude Cast sheet from unified menu container tracking; its glow is per-button/row only
        if (v.getId() == R.id.cast_sheet_list || v.findViewById(R.id.cast_sheet_list) != null) {
            return false;
        }
        if (SpotlightHelper.isTrackedMenu(v)) {
            return true;
        }
        int id = v.getId();
        if (id == R.id.mobile_dialog_list || id == R.id.mobile_dialog_content) {
            return true;
        }
        if (id == com.google.android.material.R.id.design_bottom_sheet) {
            return true;
        }
        if (v instanceof RecyclerView) {
            View parent = (View) v.getParent();
            while (parent != null) {
                int pid = parent.getId();
                if (pid == R.id.mobile_dialog_content
                        || pid == com.google.android.material.R.id.design_bottom_sheet) {
                    return true;
                }
                if (parent.getParent() instanceof View) {
                    parent = (View) parent.getParent();
                } else {
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Identifies whether a View is an interactive Card or Compound Button (e.g. Timer pill GlassView,
     * SpotlightCardView, MaterialCardView, or attached card).
     */
    public static boolean isCardOrCompound(@Nullable View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int id = v.getId();
        if (id == R.id.mobile_greet_overlay
                || id == R.id.mobile_greet_content
                || id == R.id.mobile_greet_title
                || id == R.id.mobile_greet_line
                || id == R.id.cast_sheet_list
                || id == R.id.cast_sheet_targets
                || id == R.id.cast_sheet_chooser
                || id == R.id.mobile_dialog_scrim
                || id == R.id.mobile_dialog_root
                || id == android.R.id.content) {
            return false;
        }

        if (v instanceof SpotlightCardView) {
            return true;
        }

        if (v instanceof GlassView) {
            GlassView gv = (GlassView) v;
            // GlassView cards/pills have a positive corner radius; full screen backdrops have 0
            return gv.getCornerRadius() > 0f && gv.getSpotlight().isEnabled();
        }

        if (v instanceof com.google.android.material.card.MaterialCardView
                || v instanceof androidx.cardview.widget.CardView) {
            return true;
        }

        // View explicitly registered as a card or custom spotlight host
        if (SpotlightHelper.getSpotlight(v) != null) {
            return true;
        }

        if (v instanceof com.google.android.material.chip.Chip) {
            return true;
        }

        String name = v.getClass().getSimpleName();
        return name.contains("CardView") || name.contains("Card") || name.contains("Pill");
    }

    /**
     * Identifies whether a View is an interactive element (button, card, chip, row, switch).
     * Strictly excludes full-screen transparent backdrops, scrims, and layout containers without backgrounds.
     */
    public static boolean isInteractive(@Nullable View v) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isEnabled()) {
            return false;
        }
        int id = v.getId();
        if (id == android.R.id.content) {
            return false;
        }

        // Avoid lighting up backdrops, scrims, or full-screen overlay roots (Fix 2: Restrict Glow to Interactive Elements Only)
        if (id == R.id.mobile_greet_overlay
                || id == R.id.mobile_greet_content
                || id == R.id.mobile_greet_title
                || id == R.id.mobile_greet_line
                || id == R.id.cast_sheet_list
                || id == R.id.cast_sheet_targets
                || id == R.id.cast_sheet_chooser
                || id == R.id.cast_sheet_progress
                || id == R.id.cast_sheet_empty
                || id == R.id.cast_smarttube_prompt
                || id == R.id.mobile_dialog_scrim
                || id == R.id.mobile_dialog_root) {
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

        // Scrolling containers themselves should not be interactive targets unless tracked as a menu container
        if (v instanceof RecyclerView
                || v instanceof android.widget.AbsListView
                || v instanceof android.widget.ScrollView
                || v instanceof androidx.core.widget.NestedScrollView
                || v instanceof androidx.viewpager2.widget.ViewPager2) {
            return isMenuContainer(v);
        }

        // Avoid text input fields that handle their own text cursor & selection
        if (v instanceof android.widget.EditText) {
            return false;
        }

        // GlassView cards & Spotlight cards
        if (v instanceof GlassView) {
            return ((GlassView) v).getCornerRadius() > 0f && ((GlassView) v).getSpotlight().isEnabled();
        }
        if (v instanceof SpotlightCardView || v instanceof SpotlightView) {
            return true;
        }

        // Material Cards & CardViews
        if (v instanceof com.google.android.material.card.MaterialCardView
                || v instanceof androidx.cardview.widget.CardView) {
            return true;
        }

        // Standard interactive controls (buttons, chips, switches, checkables)
        if (v instanceof android.widget.Button || v instanceof android.widget.ImageButton) {
            return true;
        }
        if (v instanceof com.google.android.material.chip.Chip) {
            return true;
        }
        if (v instanceof android.widget.Checkable) {
            return true;
        }

        // Clickable / long-clickable controls
        if (v.isClickable() || v.isLongClickable() || v.isFocusable()) {
            // If it's a ViewGroup container, require that it either has a visible background or is an attached spotlight
            if (v instanceof ViewGroup) {
                return v.getBackground() != null
                        || SpotlightTouchHelper.get(v) != null
                        || className.contains("Button")
                        || className.contains("Chip")
                        || className.contains("Tab")
                        || className.contains("Item")
                        || className.contains("Card");
            }
            return true;
        }

        if (className.contains("Card") || className.contains("Button") || className.contains("Chip")
                || className.contains("Tab") || className.contains("ItemView")) {
            return true;
        }

        return SpotlightTouchHelper.get(v) != null;
    }
}
