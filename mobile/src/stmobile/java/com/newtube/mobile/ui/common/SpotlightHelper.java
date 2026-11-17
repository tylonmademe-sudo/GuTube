package com.newtube.mobile.ui.common;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.WeakHashMap;

/**
 * GUTUBE(spotlight-helper): Central controller and attachment engine for MagicBento
 * spotlight illumination (.magic-bento__light), continuous drag tracking, and 3D spring tilt physics.
 *
 * <p>Key Architecture Upgrades:
 * <ul>
 *   <li><b>Double Box Elimination:</b> Disables edge rim strokes on menu containers, sheets, and list items
 *       so existing rounded backgrounds/ripples are never duplicated by stroked boundary outlines;</li>
 *   <li><b>Continuous Drag Tracking:</b> Tracks {@link MotionEvent#ACTION_MOVE} on every touch event,
 *       updating coordinate flows in real time across views and menus;</li>
 *   <li><b>3D Spring Tilt Physics:</b> Mounts {@link SpotlightTilt} onto cards, dialogs, and navigation pills
 *       to simulate natural depth perspective and reactive touch feedback.</li>
 * </ul>
 */
public final class SpotlightHelper {
    private static final WeakHashMap<View, Spotlight> sAttachedSpotlights = new WeakHashMap<>();
    private static final WeakHashMap<View, SpotlightTilt> sAttachedTilts = new WeakHashMap<>();
    private static final WeakHashMap<View, InteractiveTouchListener> sAttachedListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> sTrackedMenus = new WeakHashMap<>();

    private SpotlightHelper() {
    }

    /**
     * Attaches the MagicBento Spotlight effect to ANY View (buttons, cards, layout containers).
     */
    @NonNull
    public static Spotlight attach(@NonNull View view) {
        return attach(view, 0xFFFFFFFF, 0.15f);
    }

    /**
     * Attaches the Spotlight effect with custom color and intensity.
     */
    @NonNull
    public static Spotlight attach(@NonNull View view, int color, float intensity) {
        if (view instanceof GlassView) {
            GlassView gv = (GlassView) view;
            Spotlight s = gv.getSpotlight();
            s.setColor(color);
            s.setIntensity(intensity);
            s.updateTheme(view.getContext());
            return s;
        }

        if (view instanceof SpotlightCardView) {
            SpotlightCardView scv = (SpotlightCardView) view;
            Spotlight s = scv.getSpotlight();
            s.setColor(color);
            s.setIntensity(intensity);
            s.updateTheme(view.getContext());
            return s;
        }

        if (view instanceof SpotlightView) {
            SpotlightView sv = (SpotlightView) view;
            Spotlight s = sv.getSpotlight();
            s.setColor(color);
            s.setIntensity(intensity);
            s.updateTheme(view.getContext());
            return s;
        }

        Spotlight existing = sAttachedSpotlights.get(view);
        if (existing != null) {
            existing.setColor(color);
            existing.setIntensity(intensity);
            existing.updateTheme(view.getContext());
            return existing;
        }

        Spotlight spotlight = new Spotlight(view.getResources().getDisplayMetrics().density);
        spotlight.setColor(color);
        spotlight.setIntensity(intensity);
        spotlight.updateTheme(view.getContext());

        // Turn off edge rim stroke on list items and menu rows to prevent double boxes
        String viewName = view.getClass().getSimpleName();
        boolean isRowOrItem = viewName.contains("Row") || viewName.contains("Item")
                || (view.getParent() != null && view.getParent() instanceof RecyclerView);
        if (isRowOrItem) {
            spotlight.setDrawEdge(false);
            spotlight.setFlare(false);
        }

        link(view, spotlight);
        return spotlight;
    }

    /**
     * Attaches the Spotlight effect with explicit size and corner radius.
     */
    @NonNull
    public static Spotlight attach(@NonNull View view, int color, float sizeDp, float cornerRadiusDp) {
        Spotlight spotlight = attach(view, color, 1.0f);
        if (sizeDp > 0f) {
            spotlight.setSizeDp(sizeDp);
        }
        return spotlight;
    }

    /**
     * Attaches Spotlight configured specifically for card components, equipping it with
     * subtle illumination and 3D spring tilt physics without duplicate background boxes.
     */
    @NonNull
    public static Spotlight attachCard(@NonNull View card) {
        Spotlight spotlight;
        if (card instanceof SpotlightCardView) {
            spotlight = ((SpotlightCardView) card).getSpotlight();
        } else if (card instanceof GlassView) {
            spotlight = ((GlassView) card).getSpotlight();
        } else {
            spotlight = attach(card, 0xFFFFFFFF, 0.11f);
            // Disable edge rim stroke on host cards that already have background drawables
            spotlight.setDrawEdge(false);
        }

        spotlight.setSizeDp(260f)
                .setSoftness(0.70f)
                .setBorderGlow(0.60f)
                .setIntensity(0.15f)
                .setSmoothing(0.30f);

        return spotlight;
    }

    /**
     * Attaches 3D tilt spring physics to any View.
     */
    @NonNull
    public static SpotlightTilt attachTilt(@NonNull View view) {
        return attachTilt(view, 5.0f);
    }

    /**
     * Attaches 3D tilt spring physics with custom maximum tilt degrees.
     */
    @NonNull
    public static SpotlightTilt attachTilt(@NonNull View view, float maxTilt) {
        if (view instanceof SpotlightCardView) {
            SpotlightCardView scv = (SpotlightCardView) view;
            scv.setEnableTilt(true);
            scv.getTilt().setMaxTilt(maxTilt);
            return scv.getTilt();
        }

        if (view instanceof GlassView) {
            GlassView gv = (GlassView) view;
            gv.setEnableTilt(true);
            gv.getTilt().setMaxTilt(maxTilt);
            return gv.getTilt();
        }

        SpotlightTilt existing = sAttachedTilts.get(view);
        SpotlightTilt tilt;
        if (existing != null) {
            existing.setMaxTilt(maxTilt);
            tilt = existing;
        } else {
            tilt = new SpotlightTilt(view.getResources().getDisplayMetrics().density);
            tilt.setMaxTilt(maxTilt);
            sAttachedTilts.put(view, tilt);
        }

        bindUnifiedListener(view);
        return tilt;
    }

    private static void bindUnifiedListener(@NonNull View host) {
        InteractiveTouchListener listener = sAttachedListeners.get(host);
        if (listener == null) {
            listener = new InteractiveTouchListener(host);
            sAttachedListeners.put(host, listener);
            host.setOnTouchListener(listener);
        }
    }

    private static final class InteractiveTouchListener implements View.OnTouchListener {
        private final float mTouchSlop;
        private float mStartX;
        private float mStartY;
        private boolean mIsScrolling;

        InteractiveTouchListener(@NonNull View host) {
            mTouchSlop = android.view.ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Spotlight spotlight = sAttachedSpotlights.get(v);
            SpotlightTilt tilt = sAttachedTilts.get(v);

            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mStartX = x;
                    mStartY = y;
                    mIsScrolling = false;
                    if (spotlight != null) {
                        spotlight.onTouchEvent(v, event);
                    }
                    if (tilt != null && tilt.isEnabled()) {
                        tilt.updateTouch(v, event);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dx = x - mStartX;
                    float dy = y - mStartY;
                    if (!mIsScrolling && (Math.abs(dy) > mTouchSlop || Math.abs(dx) > mTouchSlop)) {
                        // Hand off smoothly if moving predominantly vertically on a scrollable container or sheet
                        if (Math.abs(dy) > Math.abs(dx) * 1.25f && isScrollable(v)) {
                            mIsScrolling = true;
                            if (tilt != null) {
                                tilt.springToNeutral(v);
                            }
                        }
                    }

                    if (spotlight != null) {
                        spotlight.onTouchEvent(v, event);
                    }
                    if (tilt != null && tilt.isEnabled() && !mIsScrolling) {
                        tilt.updateTouch(v, event);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mIsScrolling = false;
                    if (spotlight != null) {
                        spotlight.onTouchEvent(v, event);
                    }
                    if (tilt != null && tilt.isEnabled()) {
                        tilt.updateTouch(v, event);
                    }
                    break;
            }

            // Return false so child clicks, long clicks, and standard touch bubbling continue normally
            return false;
        }

        private boolean isScrollable(View v) {
            if (v instanceof RecyclerView || v instanceof android.widget.ScrollView) {
                return true;
            }
            return v.canScrollVertically(1) || v.canScrollVertically(-1);
        }
    }

    /**
     * Links a {@link Spotlight} instance to a host View via continuous touch listener and overlay drawable.
     */
    public static void link(@NonNull View host, @NonNull Spotlight spotlight) {
        sAttachedSpotlights.put(host, spotlight);
        SpotlightTouchHelper.register(host, spotlight);

        bindUnifiedListener(host);

        SpotlightOverlayDrawable overlay = new SpotlightOverlayDrawable(host, spotlight);
        // Do not stroke edges if drawEdge is disabled
        overlay.setDrawEdge(spotlight.isDrawEdge());
        host.getOverlay().add(overlay);
    }

    /**
     * Retrieves the {@link Spotlight} instance attached to the view, or null if none.
     */
    @Nullable
    public static Spotlight getSpotlight(@NonNull View view) {
        if (view instanceof GlassView) {
            return ((GlassView) view).getSpotlight();
        }
        if (view instanceof SpotlightCardView) {
            return ((SpotlightCardView) view).getSpotlight();
        }
        if (view instanceof SpotlightView) {
            return ((SpotlightView) view).getSpotlight();
        }
        return sAttachedSpotlights.get(view);
    }

    /**
     * Retrieves the {@link SpotlightTilt} instance attached to the view, or null if none.
     */
    @Nullable
    public static SpotlightTilt getTilt(@NonNull View view) {
        if (view instanceof GlassView) {
            return ((GlassView) view).getTilt();
        }
        if (view instanceof SpotlightCardView) {
            return ((SpotlightCardView) view).getTilt();
        }
        return sAttachedTilts.get(view);
    }

    /**
     * Attaches the Spotlight effect to a menu or list container and enables continuous drag tracking
     * (ACTION_MOVE) across items. Edge rim stroke is disabled on the container to prevent double boxes.
     */
    public static void attachMenu(@NonNull ViewGroup menu) {
        if (Boolean.TRUE.equals(sTrackedMenus.get(menu))) {
            return;
        }
        sTrackedMenus.put(menu, Boolean.TRUE);

        Spotlight containerSpotlight = attach(menu, 0xFFFFFFFF, 0.11f);
        containerSpotlight.setSizeDp(260f);
        // Never stroke the outer perimeter of a menu list to prevent double boxes
        containerSpotlight.setDrawEdge(false);

        if (menu instanceof RecyclerView) {
            setupRecyclerViewMenuTracking((RecyclerView) menu, containerSpotlight);
        } else {
            setupViewGroupMenuTracking(menu, containerSpotlight);
        }
    }

    private static void setupRecyclerViewMenuTracking(@NonNull RecyclerView recyclerView, @NonNull Spotlight spotlight) {
        final View[] activeChild = new View[1];
        final View[] initialChild = new View[1];
        final Rect hitRect = new Rect();

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleContinuousDrag(rv, e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleContinuousDrag(rv, e);
            }

            private void handleContinuousDrag(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                float x = e.getX();
                float y = e.getY();
                boolean inside = x >= 0f && y >= 0f && x <= rv.getWidth() && y <= rv.getHeight();

                // Continuously stream coordinates to the spotlight physics engine
                spotlight.onTouchEvent(rv, e);
                propagateToParentSpotlight(rv, e.getRawX(), e.getRawY(), e);

                if (!inside || action == MotionEvent.ACTION_CANCEL) {
                    if (activeChild[0] != null) {
                        activeChild[0].setPressed(false);
                        Spotlight oldS = getSpotlight(activeChild[0]);
                        if (oldS != null) oldS.release(activeChild[0]);
                        SpotlightTilt oldT = getTilt(activeChild[0]);
                        if (oldT != null) oldT.springToNeutral(activeChild[0]);
                        activeChild[0] = null;
                    }
                    initialChild[0] = null;
                    return;
                }

                if (action == MotionEvent.ACTION_DOWN) {
                    initialChild[0] = rv.findChildViewUnder(x, y);
                }

                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    View hit = rv.findChildViewUnder(x, y);
                    if (hit != activeChild[0]) {
                        if (activeChild[0] != null) {
                            activeChild[0].setPressed(false);
                            Spotlight oldS = getSpotlight(activeChild[0]);
                            if (oldS != null) oldS.release(activeChild[0]);
                            SpotlightTilt oldT = getTilt(activeChild[0]);
                            if (oldT != null) oldT.springToNeutral(activeChild[0]);
                        }
                        if (hit != null && (hit.isClickable() || hit.isFocusable())) {
                            hit.setPressed(true);
                            Haptics.tick(hit);
                        }
                        activeChild[0] = hit;
                    }

                    // Propagate continuous drag coordinates into the highlighted child's spotlight & tilt
                    if (hit != null) {
                        float cx = x - hit.getLeft();
                        float cy = y - hit.getTop();
                        Spotlight childSpotlight = getSpotlight(hit);
                        if (childSpotlight != null) {
                            childSpotlight.setDrawEdge(false);
                            childSpotlight.setCoordinates(cx, cy);
                            childSpotlight.wake(hit);
                        }
                        SpotlightTilt childTilt = getTilt(hit);
                        if (childTilt != null && childTilt.isEnabled()) {
                            MotionEvent ce = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(), cx, cy, e.getMetaState());
                            childTilt.updateTouch(hit, ce);
                            ce.recycle();
                        }
                    }
                } else if (action == MotionEvent.ACTION_UP) {
                    if (activeChild[0] != null) {
                        View target = activeChild[0];
                        target.getHitRect(hitRect);
                        activeChild[0].setPressed(false);
                        Spotlight s = getSpotlight(target);
                        if (s != null) s.release(target);
                        SpotlightTilt t = getTilt(target);
                        if (t != null) t.springToNeutral(target);
                        activeChild[0] = null;
                        // Slide-to-select: if user dragged onto a different item, trigger its click
                        if (hitRect.contains((int) x, (int) y) && target.isClickable()
                                && initialChild[0] != null && target != initialChild[0]) {
                            target.performClick();
                        }
                        initialChild[0] = null;
                    }
                }
            }
        });
    }

    private static void setupViewGroupMenuTracking(@NonNull ViewGroup container, @NonNull Spotlight spotlight) {
        final View[] activeChild = new View[1];
        final Rect hitRect = new Rect();

        View.OnTouchListener childListener = (v, e) -> {
            int action = e.getActionMasked();
            float rawX = e.getRawX();
            float rawY = e.getRawY();

            int[] cLoc = new int[2];
            container.getLocationOnScreen(cLoc);
            float cx = rawX - cLoc[0];
            float cy = rawY - cLoc[1];

            MotionEvent ce = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), action, cx, cy, e.getMetaState());
            spotlight.onTouchEvent(container, ce);
            ce.recycle();

            propagateToParentSpotlight(container, rawX, rawY, e);

            boolean inside = cx >= 0f && cy >= 0f && cx <= container.getWidth() && cy <= container.getHeight();
            if (!inside || action == MotionEvent.ACTION_CANCEL) {
                if (activeChild[0] != null) {
                    activeChild[0].setPressed(false);
                    Spotlight oldS = getSpotlight(activeChild[0]);
                    if (oldS != null) oldS.release(activeChild[0]);
                    SpotlightTilt oldT = getTilt(activeChild[0]);
                    if (oldT != null) oldT.springToNeutral(activeChild[0]);
                    activeChild[0] = null;
                }
                return false;
            }

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                View hit = findChildUnder(container, cx, cy);
                if (hit != activeChild[0]) {
                    if (activeChild[0] != null) {
                        activeChild[0].setPressed(false);
                        Spotlight oldS = getSpotlight(activeChild[0]);
                        if (oldS != null) oldS.release(activeChild[0]);
                        SpotlightTilt oldT = getTilt(activeChild[0]);
                        if (oldT != null) oldT.springToNeutral(activeChild[0]);
                    }
                    if (hit != null && (hit.isClickable() || hit.isFocusable())) {
                        hit.setPressed(true);
                        Haptics.tick(hit);
                    }
                    activeChild[0] = hit;
                }

                if (hit != null) {
                    float chx = cx - hit.getLeft();
                    float chy = cy - hit.getTop();
                    Spotlight childSpotlight = getSpotlight(hit);
                    if (childSpotlight != null) {
                        childSpotlight.setDrawEdge(false);
                        childSpotlight.setCoordinates(chx, chy);
                        childSpotlight.wake(hit);
                    }
                    SpotlightTilt childTilt = getTilt(hit);
                    if (childTilt != null && childTilt.isEnabled()) {
                        MotionEvent hce = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(), chx, chy, e.getMetaState());
                        childTilt.updateTouch(hit, hce);
                        hce.recycle();
                    }
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (activeChild[0] != null) {
                    View target = activeChild[0];
                    target.getHitRect(hitRect);
                    activeChild[0].setPressed(false);
                    Spotlight s = getSpotlight(target);
                    if (s != null) s.release(target);
                    SpotlightTilt t = getTilt(target);
                    if (t != null) t.springToNeutral(target);
                    activeChild[0] = null;
                    if (hitRect.contains((int) cx, (int) cy) && target.isClickable() && target != v) {
                        target.performClick();
                    }
                }
            }
            return false;
        };

        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setOnTouchListener(childListener);
        }

        container.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                child.setOnTouchListener(childListener);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });

        container.setOnTouchListener((v, e) -> {
            spotlight.onTouchEvent(v, e);
            propagateToParentSpotlight(container, e.getRawX(), e.getRawY(), e);

            int action = e.getActionMasked();
            float x = e.getX();
            float y = e.getY();
            boolean inside = x >= 0f && y >= 0f && x <= container.getWidth() && y <= container.getHeight();

            if (!inside || action == MotionEvent.ACTION_CANCEL) {
                if (activeChild[0] != null) {
                    activeChild[0].setPressed(false);
                    Spotlight oldS = getSpotlight(activeChild[0]);
                    if (oldS != null) oldS.release(activeChild[0]);
                    SpotlightTilt oldT = getTilt(activeChild[0]);
                    if (oldT != null) oldT.springToNeutral(activeChild[0]);
                    activeChild[0] = null;
                }
                return false;
            }

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                View hit = findChildUnder(container, x, y);
                if (hit != activeChild[0]) {
                    if (activeChild[0] != null) {
                        activeChild[0].setPressed(false);
                        Spotlight oldS = getSpotlight(activeChild[0]);
                        if (oldS != null) oldS.release(activeChild[0]);
                        SpotlightTilt oldT = getTilt(activeChild[0]);
                        if (oldT != null) oldT.springToNeutral(activeChild[0]);
                    }
                    if (hit != null && (hit.isClickable() || hit.isFocusable())) {
                        hit.setPressed(true);
                        Haptics.tick(hit);
                    }
                    activeChild[0] = hit;
                }

                if (hit != null) {
                    float chx = x - hit.getLeft();
                    float chy = y - hit.getTop();
                    Spotlight childSpotlight = getSpotlight(hit);
                    if (childSpotlight != null) {
                        childSpotlight.setCoordinates(chx, chy);
                        childSpotlight.wake(hit);
                    }
                    SpotlightTilt childTilt = getTilt(hit);
                    if (childTilt != null && childTilt.isEnabled()) {
                        MotionEvent hce = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(), chx, chy, e.getMetaState());
                        childTilt.updateTouch(hit, hce);
                        hce.recycle();
                    }
                }
            } else if (action == MotionEvent.ACTION_UP) {
                if (activeChild[0] != null) {
                    View target = activeChild[0];
                    target.getHitRect(hitRect);
                    activeChild[0].setPressed(false);
                    Spotlight s = getSpotlight(target);
                    if (s != null) s.release(target);
                    SpotlightTilt t = getTilt(target);
                    if (t != null) t.springToNeutral(target);
                    activeChild[0] = null;
                    if (hitRect.contains((int) x, (int) y) && target.isClickable()) {
                        target.performClick();
                    }
                }
            }
            return false;
        });
    }

    /**
     * Propagates continuous touch coordinates to parent Spotlight and SpotlightTilt views.
     */
    public static void propagateToParentSpotlight(@NonNull View view, float rawX, float rawY, @NonNull MotionEvent event) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            Spotlight ps = getSpotlight(parentView);
            SpotlightTilt pt = sAttachedTilts.get(parentView);
            if (ps != null || pt != null) {
                int[] pLoc = new int[2];
                parentView.getLocationOnScreen(pLoc);
                float px = rawX - pLoc[0];
                float py = rawY - pLoc[1];
                MotionEvent pe = MotionEvent.obtain(
                        event.getDownTime(), event.getEventTime(), event.getAction(),
                        px, py, event.getMetaState());
                if (ps != null) {
                    ps.onTouchEvent(parentView, pe);
                }
                if (pt != null && pt.isEnabled()) {
                    pt.updateTouch(parentView, pe);
                }
                pe.recycle();
            }
            parent = parent.getParent();
        }
    }

    /**
     * Finds which child in a ViewGroup contains the coordinate (x, y).
     */
    @Nullable
    public static View findChildUnder(@NonNull ViewGroup parent, float x, float y) {
        int count = parent.getChildCount();
        Rect rect = new Rect();
        for (int i = count - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                child.getHitRect(rect);
                if (rect.contains((int) x, (int) y)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Attaches Spotlight to container and equips all interactive children.
     */
    public static void attachWithChildren(@NonNull ViewGroup container) {
        attach(container);
        applyToCardsAndButtons(container);
    }

    /**
     * Recursively attaches the Spotlight effect to all interactive buttons, cards, and clickable views
     * in the given container hierarchy without overlapping or duplicating borders.
     */
    public static void applyToCardsAndButtons(@Nullable View root) {
        if (root == null) {
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof GlassView || child instanceof SpotlightCardView) {
                    continue; // GlassView & SpotlightCardView have spotlight built-in
                }
                String name = child.getClass().getSimpleName();
                if (child.isClickable() || child.isFocusable() || name.contains("Card")
                        || name.contains("Button") || name.contains("Chip") || child.getId() != View.NO_ID) {
                    Spotlight s = attach(child);
                    // Turn off edge rim for list items/rows to prevent double boxes
                    if (name.contains("Row") || name.contains("Item") || child.getParent() instanceof RecyclerView) {
                        s.setDrawEdge(false);
                    }
                }
                if (child instanceof ViewGroup) {
                    applyToCardsAndButtons(child);
                }
            }
        } else if (root.isClickable() || root.isFocusable()) {
            attach(root);
        }
    }
}
