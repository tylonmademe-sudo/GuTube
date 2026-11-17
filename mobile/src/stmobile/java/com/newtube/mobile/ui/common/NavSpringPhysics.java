package com.newtube.mobile.ui.common;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.WeakHashMap;

/**
 * GUTUBE(nav-spring): Tactile spring physics and clean transparency for bottom navigation items.
 *
 * <p>Key Behaviors:
 * <ul>
 *   <li><b>Clean Transparency:</b> Strips away all native Android ripples, bubble indicators,
 *       and solid backgrounds so tabs rely entirely on the underlying Spotlight canvas.</li>
 *   <li><b>Tactile Spring Physics:</b> Compresses the individual tab icon to 0.9x scale on press,
 *       and upon release, springs elastically back to 1.0x scale using {@link SpringForce#DAMPING_RATIO_MEDIUM_BOUNCY}
 *       and {@link SpringForce#STIFFNESS_MEDIUM}.</li>
 *   <li><b>Drag Coordination:</b> Supports programmatic squish and spring-release during continuous
 *       horizontal dragging across tabs.</li>
 * </ul>
 */
public final class NavSpringPhysics {
    private static final WeakHashMap<View, SpringPair> sSpringMap = new WeakHashMap<>();

    private NavSpringPhysics() {
    }

    public static class SpringPair {
        public final View iconView;
        public final SpringAnimation scaleX;
        public final SpringAnimation scaleY;
        private boolean mPressed = false;

        public SpringPair(@NonNull View icon) {
            this.iconView = icon;

            this.scaleX = new SpringAnimation(icon, DynamicAnimation.SCALE_X);
            SpringForce forceX = new SpringForce(1.0f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM);
            this.scaleX.setSpring(forceX);

            this.scaleY = new SpringAnimation(icon, DynamicAnimation.SCALE_Y);
            SpringForce forceY = new SpringForce(1.0f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM);
            this.scaleY.setSpring(forceY);
        }

        public void press() {
            if (!mPressed) {
                mPressed = true;
                scaleX.animateToFinalPosition(0.9f);
                scaleY.animateToFinalPosition(0.9f);
            }
        }

        public void release() {
            if (mPressed) {
                mPressed = false;
                scaleX.animateToFinalPosition(1.0f);
                scaleY.animateToFinalPosition(1.0f);
            }
        }

        public void reset() {
            mPressed = false;
            scaleX.cancel();
            scaleY.cancel();
            iconView.setScaleX(1.0f);
            iconView.setScaleY(1.0f);
        }
    }

    /**
     * Attaches tactile spring physics to every tab item inside the bottom navigation bar
     * and strips away native ripples and backgrounds.
     */
    public static void attach(@NonNull BottomNavigationView nav) {
        // Enforce transparent background and no ripples or indicators on the bar itself
        nav.setItemRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
        nav.setItemActiveIndicatorEnabled(false);
        nav.setItemBackground(null);
        nav.setBackground(null);

        // Find and attach to each item view
        nav.post(() -> attachToChildren(nav));
    }

    public static void attachToChildren(@NonNull BottomNavigationView nav) {
        for (int i = 0; i < nav.getMenu().size(); i++) {
            int itemId = nav.getMenu().getItem(i).getItemId();
            View itemView = nav.findViewById(itemId);
            if (itemView != null) {
                attachToItem(itemView);
            }
        }
    }

    public static void attachToItem(@NonNull View itemView) {
        // Remove any default background or foreground ripples
        itemView.setBackground(null);
        itemView.setBackgroundColor(Color.TRANSPARENT);
        itemView.setForeground(null);

        View iconView = findIconView(itemView);
        if (iconView == null) {
            iconView = itemView;
        }

        SpringPair pair = sSpringMap.get(itemView);
        if (pair == null || pair.iconView != iconView) {
            pair = new SpringPair(iconView);
            sSpringMap.put(itemView, pair);
        }

        final SpringPair finalPair = pair;
        itemView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                finalPair.press();
                Haptics.tick(v);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finalPair.release();
            }
            return false;
        });
    }

    /**
     * Programmatically sets the pressed state on a tab's icon spring (e.g. during horizontal drag).
     */
    public static void setItemPressed(@Nullable View itemView, boolean pressed) {
        if (itemView == null) {
            return;
        }
        SpringPair pair = sSpringMap.get(itemView);
        if (pair == null) {
            View icon = findIconView(itemView);
            if (icon != null) {
                pair = new SpringPair(icon);
                sSpringMap.put(itemView, pair);
            }
        }
        if (pair != null) {
            if (pressed) {
                pair.press();
            } else {
                pair.release();
            }
        }
    }

    /**
     * Resets all tabs back to 1.0f scale.
     */
    public static void resetAll(@NonNull BottomNavigationView nav) {
        for (int i = 0; i < nav.getMenu().size(); i++) {
            int itemId = nav.getMenu().getItem(i).getItemId();
            View itemView = nav.findViewById(itemId);
            if (itemView != null) {
                SpringPair pair = sSpringMap.get(itemView);
                if (pair != null) {
                    pair.reset();
                }
            }
        }
    }

    /**
     * Finds the icon ImageView inside a navigation item view.
     */
    @NonNull
    public static View findIconView(@NonNull View itemView) {
        int iconId = itemView.getResources().getIdentifier(
                "navigation_bar_item_icon_view", "id", itemView.getContext().getPackageName());
        if (iconId == 0) {
            iconId = itemView.getResources().getIdentifier(
                    "navigation_bar_item_icon_view", "id", "com.google.android.material");
        }
        if (iconId != 0) {
            View icon = itemView.findViewById(iconId);
            if (icon != null) {
                return icon;
            }
        }
        return findFirstImageView(itemView);
    }

    @NonNull
    private static View findFirstImageView(@NonNull View root) {
        if (root instanceof ImageView) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstImageView(group.getChildAt(i));
                if (found instanceof ImageView) {
                    return found;
                }
            }
        }
        return root;
    }
}
