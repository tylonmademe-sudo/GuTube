package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * GUTUBE(spotlight-universal-test): Tests verifying spotlight removal across the whole app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SpotlightUniversalTest {

    @Test
    public void testSpotlightGloballyDisabled() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        // 1. Setting must return false
        assertFalse("Spotlight setting must be disabled globally", Glass.isSpotlight(activity));

        // 2. Helper instances must be inactive and not visible
        SpotlightTouchHelper touchHelper = new SpotlightTouchHelper(2.0f);
        assertFalse(touchHelper.isEnabled());
        assertFalse(touchHelper.isVisible());
        assertFalse(touchHelper.isActive());
        assertFalse(touchHelper.isDrawSurface());
        assertFalse(touchHelper.isDrawEdge());

        // 3. Spotlight class methods must report disabled
        Spotlight spotlight = new Spotlight(2.0f);
        assertFalse(spotlight.isEnabled());
        assertFalse(spotlight.isVisible());
        assertFalse(spotlight.isActive());

        // 4. Tilt engine must be disabled
        SpotlightTilt tilt = new SpotlightTilt(2.0f);
        assertFalse(tilt.isEnabled());
    }

    @Test
    public void testAttachDoesNotWrapBackgroundOrSetListeners() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Button button = new Button(activity);
        android.graphics.drawable.ColorDrawable origBg = new android.graphics.drawable.ColorDrawable(0xFF112233);
        button.setBackground(origBg);

        Spotlight s = SpotlightHelper.attach(button);
        assertNotNull(s);
        assertFalse(s.isEnabled());
        assertFalse(s.isVisible());

        // Background must remain untouched (never wrapped with SpotlightBackgroundDrawable)
        assertEquals("Background must remain original without wrapping", origBg, button.getBackground());
        assertFalse(button.getBackground() instanceof SpotlightBackgroundDrawable);

        // Tilt must not be attached
        assertNull(SpotlightHelper.getTilt(button));
        assertNull(SpotlightHelper.getSpotlight(button));
    }

    @Test
    public void testDispatcherIsDeadAcrossAllTouches() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        activity.setContentView(container);
        container.layout(0, 0, 800, 200);

        Button btn1 = new Button(activity);
        btn1.setClickable(true);
        container.addView(btn1);
        btn1.layout(0, 0, 200, 100);

        long now = System.currentTimeMillis();

        // Down
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0);
        SpotlightDispatcher.dispatch(container, down);
        down.recycle();

        // Move
        MotionEvent move = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, 80f, 50f, 0);
        SpotlightDispatcher.dispatch(container, move);
        move.recycle();

        // Hover
        MotionEvent hover = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_HOVER_MOVE, 80f, 50f, 0);
        SpotlightDispatcher.dispatch(container, hover);
        hover.recycle();

        // Up
        MotionEvent up = MotionEvent.obtain(now, now + 48, MotionEvent.ACTION_UP, 80f, 50f, 0);
        SpotlightDispatcher.dispatch(container, up);
        up.recycle();

        // No helpers should be active
        SpotlightTouchHelper helper = SpotlightTouchHelper.get(btn1);
        assertNull("No touch helper should be active or tracked", helper);
    }

    @Test
    public void testBackgroundDrawableRendersOnlyOriginalBackground() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View host = new View(activity);
        android.graphics.drawable.ColorDrawable origBg = new android.graphics.drawable.ColorDrawable(0xFF445566);
        SpotlightTouchHelper helper = new SpotlightTouchHelper(2.0f);

        SpotlightBackgroundDrawable bgDrawable = new SpotlightBackgroundDrawable(host, helper, origBg);
        assertEquals(origBg, bgDrawable.getOriginalBackground());
    }

    @Test
    public void testGlassViewSpotlightIsDisabled() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        GlassView glassView = new GlassView(activity);

        assertNotNull(glassView.getSpotlight());
        assertFalse("GlassView spotlight must be disabled", glassView.getSpotlight().isEnabled());
        assertFalse("GlassView spotlight must not be visible", glassView.getSpotlight().isVisible());
        assertFalse("GlassView tilt must be disabled", glassView.getTilt().isEnabled());
    }
}
