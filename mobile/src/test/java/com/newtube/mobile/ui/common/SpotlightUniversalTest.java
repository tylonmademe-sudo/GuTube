package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * GUTUBE(spotlight-universal-test): Tests for universal ColorOS 17 spotlight touch engine.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SpotlightUniversalTest {

    @Test
    public void testFalloffStopsAndMultipliers() {
        // Verify edge 15-stop Gaussian falloff
        assertEquals(15, SpotlightTouchHelper.EDGE_STOPS_COUNT);
        assertEquals(0.0f, SpotlightTouchHelper.EDGE_POSITIONS[0], 1e-4f);
        assertEquals(1.0f, SpotlightTouchHelper.EDGE_POSITIONS[14], 1e-4f);
        assertEquals(1.0f, SpotlightTouchHelper.EDGE_MULTIPLIERS[0], 1e-4f);
        assertEquals(0.0f, SpotlightTouchHelper.EDGE_MULTIPLIERS[14], 1e-4f);

        // Verify fill stops table (16 stops)
        SpotlightTouchHelper helper = new SpotlightTouchHelper(2.0f);
        helper.setSoftness(0.70f);
        assertEquals(0.70f, helper.getSoftness(), 1e-4f);
        assertEquals(0.30f, helper.getSmoothing(), 1e-4f);
        assertEquals(0.15f, helper.getIntensity(), 1e-4f);
        assertEquals(0.60f, helper.getBorderGlow(), 1e-4f);
    }

    @Test
    public void testThemeAdaptation() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SpotlightTouchHelper helper = new SpotlightTouchHelper(2.0f);

        // Explicit Dark mode configuration
        Configuration darkConfig = new Configuration(activity.getResources().getConfiguration());
        darkConfig.uiMode = (darkConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context darkContext = activity.createConfigurationContext(darkConfig);
        helper.updateTheme(darkContext);
        assertEquals(0xFFFFFFFF, helper.getColor());

        // Explicit Light mode configuration
        Configuration lightConfig = new Configuration(activity.getResources().getConfiguration());
        lightConfig.uiMode = (lightConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        Context lightContext = activity.createConfigurationContext(lightConfig);
        helper.updateTheme(lightContext);
        assertEquals(0xFF18181B, helper.getColor());
    }

    @Test
    public void testInteractiveTargetResolution() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        root.layout(0, 0, 1000, 1000);

        // A card container with an inner text view
        FrameLayout card = new FrameLayout(activity);
        card.setClickable(true);
        root.addView(card);
        card.layout(50, 50, 400, 300);

        TextView label = new TextView(activity);
        label.setClickable(false);
        card.addView(label);
        label.layout(60, 60, 200, 120);

        // Clicking on the non-clickable label resolves to the parent card
        View target = SpotlightDispatcher.findInteractiveTarget(root, 70, 70);
        assertNotNull(target);
        assertEquals(card, target);

        // A separate clickable button inside the card resolves to the button itself
        Button button = new Button(activity);
        button.setClickable(true);
        card.addView(button);
        button.layout(250, 60, 380, 120);

        View buttonTarget = SpotlightDispatcher.findInteractiveTarget(root, 320, 130);
        assertNotNull(buttonTarget);
        assertEquals(button, buttonTarget);

        // Touching an empty region resolves to null
        View emptyTarget = SpotlightDispatcher.findInteractiveTarget(root, 800, 800);
        assertNull(emptyTarget);
    }

    @Test
    public void testContinuousDragTracking() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        activity.setContentView(container);
        container.layout(0, 0, 800, 200);

        Button btn1 = new Button(activity);
        btn1.setClickable(true);
        container.addView(btn1);
        btn1.layout(0, 0, 200, 100);

        Button btn2 = new Button(activity);
        btn2.setClickable(true);
        container.addView(btn2);
        btn2.layout(200, 0, 400, 100);

        // Touch DOWN on btn1
        long now = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0);
        SpotlightDispatcher.dispatch(container, down);
        down.recycle();

        SpotlightTouchHelper helper1 = SpotlightTouchHelper.get(btn1);
        assertNotNull(helper1);
        assertTrue(helper1.isActive());
        assertEquals(50f, helper1.getCurrentX(), 1e-2f);
        assertEquals(50f, helper1.getCurrentY(), 1e-2f);

        // Drag MOVE within btn1
        MotionEvent move1 = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, 80f, 50f, 0);
        SpotlightDispatcher.dispatch(container, move1);
        move1.recycle();
        assertTrue(helper1.isActive());

        // Drag MOVE across boundary into btn2 (x = 250)
        MotionEvent move2 = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_MOVE, 250f, 50f, 0);
        SpotlightDispatcher.dispatch(container, move2);
        move2.recycle();

        // btn1 should be deactivated and btn2 should now be active
        assertFalse(helper1.isActive());
        SpotlightTouchHelper helper2 = SpotlightTouchHelper.get(btn2);
        assertNotNull(helper2);
        assertTrue(helper2.isActive());

        // Touch UP releases btn2
        MotionEvent up = MotionEvent.obtain(now, now + 48, MotionEvent.ACTION_UP, 250f, 50f, 0);
        SpotlightDispatcher.dispatch(container, up);
        up.recycle();
        assertFalse(helper2.isActive());
    }
}
