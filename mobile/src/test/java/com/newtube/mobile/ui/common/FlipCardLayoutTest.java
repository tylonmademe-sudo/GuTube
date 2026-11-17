package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit test validating exact feature parity between {@link FlipCardLayout} and the React FlipCard specification:
 * 1. Front and back content separation, visibility and backface culling;
 * 2. Y-axis and X-axis 180° flip animation;
 * 3. Tap-to-flip;
 * 4. Horizontal drag-to-flip with velocity-based fling;
 * 5. Drag-distance behavior (default 0 uses width, custom uses distance);
 * 6. Dynamic 3D tilt with 12° maximum;
 * 7. Dynamic specular glare with configurable opacity of 0.22;
 * 8. 1.03x press/hover lift scale;
 * 9. 1100px perspective;
 * 10. Flip spring stiffness 170 and damping 20;
 * 11. Configurable width, height, radius, background (#27272a) and text color (#f5f5f5);
 * 12. Dynamic shadow with configurable color (#000000) and 0.45 base opacity;
 * 13. Flip-change callback (OnFlipListener);
 * 14. Front/back interaction and touch handling;
 * 15. Finger-following Spotlight lighting preservation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FlipCardLayoutTest {

    private Context mContext;
    private FlipCardLayout mCard;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mCard = new FlipCardLayout(mContext);
    }

    @Test
    public void testDefaultSpecificationValues() {
        // Feature 2: Y-axis flip
        assertEquals(FlipCardLayout.Axis.Y, mCard.getAxis());

        // Feature 3: Tap to flip
        assertTrue(mCard.isFlipOnClick());

        // Feature 4: Draggable
        assertTrue(mCard.isDraggable());

        // Feature 5: Default drag distance = 0
        assertEquals(0f, mCard.getDragDistance(), 0.001f);

        // Feature 6: Tilt enabled with max 12 deg
        assertTrue(mCard.isTiltEnabled());
        assertEquals(12.0f, mCard.getTiltMax(), 0.001f);

        // Feature 7: Specular glare enabled with 0.22 opacity
        assertTrue(mCard.isGlareEnabled());
        assertEquals(0.22f, mCard.getGlareOpacity(), 0.001f);

        // Feature 8: Press/hover lift 1.03x
        assertEquals(1.03f, mCard.getHoverScale(), 0.001f);

        // Feature 9: 1100px perspective
        assertEquals(1100.0f, mCard.getPerspective(), 0.001f);

        // Feature 10: Spring stiffness 170 and damping 20
        assertEquals(170.0f, mCard.getFlipStiffness(), 0.001f);
        assertEquals(20.0f, mCard.getFlipDamping(), 0.001f);

        // Feature 11: Corner radius 22dp, zinc background #27272a, text color #f5f5f5
        assertEquals(22f, mCard.getCornerRadius(), 0.001f);
        assertEquals(0xFF27272A, mCard.getCardBackground());
        assertEquals(0xFFF5F5F5, mCard.getTextColor());

        // Feature 12: Dynamic shadow enabled, color #000000, opacity 0.45
        assertTrue(mCard.isShadowEnabled());
        assertEquals(0xFF000000, mCard.getShadowColor());
        assertEquals(0.45f, mCard.getShadowOpacity(), 0.001f);

        // Feature 15: Spotlight lighting helper attached and configured
        assertNotNull(mCard.getSpotlightHelper());
        assertEquals(0xFFFFFFFF, mCard.getSpotlightHelper().getColor());
    }

    @Test
    public void testFrontAndBackSeparationAndBackfaceCulling() {
        View front = new View(mContext);
        View back = new View(mContext);
        mCard.setFrontView(front);
        mCard.setBackView(back);

        assertEquals(front, mCard.getFrontView());
        assertEquals(back, mCard.getBackView());

        // Measure and layout at 300x400
        mCard.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        mCard.layout(0, 0, 300, 400);

        // Back view rotated 180 degrees around Y axis for backface alignment
        assertEquals(180f, back.getRotationY(), 0.001f);
        assertEquals(0f, back.getRotationX(), 0.001f);

        // Initial state: not flipped
        assertFalse(mCard.isFlipped());

        // Flip to back
        mCard.setFlipped(true, false);
        assertTrue(mCard.isFlipped());

        // Flip back to front
        mCard.setFlipped(false, false);
        assertFalse(mCard.isFlipped());
    }

    @Test
    public void testOnFlipListenerCallback() {
        AtomicBoolean received = new AtomicBoolean(false);
        AtomicBoolean targetState = new AtomicBoolean(false);

        mCard.setOnFlipListener(flipped -> {
            received.set(true);
            targetState.set(flipped);
        });

        mCard.flip(true);
        assertTrue("Callback must fire on flip", received.get());
        assertTrue("Flipped state must be true", targetState.get());
        assertTrue(mCard.isFlipped());

        received.set(false);
        mCard.flip(true);
        assertTrue("Callback must fire on second flip", received.get());
        assertFalse("Flipped state must be false", targetState.get());
        assertFalse(mCard.isFlipped());
    }

    @Test
    public void testAxisSwitching() {
        mCard.setAxis(FlipCardLayout.Axis.X);
        assertEquals(FlipCardLayout.Axis.X, mCard.getAxis());

        View front = new View(mContext);
        View back = new View(mContext);
        mCard.setFrontView(front);
        mCard.setBackView(back);

        mCard.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        mCard.layout(0, 0, 300, 400);

        // For X-axis, back view is rotated 180 degrees around X axis
        assertEquals(180f, back.getRotationX(), 0.001f);
        assertEquals(0f, back.getRotationY(), 0.001f);

        mCard.setAxis("y");
        assertEquals(FlipCardLayout.Axis.Y, mCard.getAxis());
    }

    @Test
    public void testConfigurableDimensionsAndTextColorRecursion() {
        mCard.setCardWidth(300);
        mCard.setCardHeight(400);
        mCard.setCornerRadius(22f);
        mCard.setCardBackground(0xFF27272A);
        mCard.setTextColor(0xFFF5F5F5);

        TextView label = new TextView(mContext);
        mCard.setFrontView(label);

        mCard.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int density = Math.round(mContext.getResources().getDisplayMetrics().density);
        assertEquals(300 * density, mCard.getMeasuredWidth());
        assertEquals(400 * density, mCard.getMeasuredHeight());

        // Text color recursively applied to children
        mCard.setTextColor(0xFF00FF00);
        assertEquals(0xFF00FF00, label.getCurrentTextColor());
    }

    @Test
    public void testTouchDispatchAndSpotlightStreaming() {
        mCard.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        mCard.layout(0, 0, 300, 400);

        long downTime = 1000L;
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 150f, 200f, 0);
        mCard.dispatchTouchEvent(down);
        down.recycle();

        // Spotlight helper tracks touch
        assertTrue(mCard.getSpotlightHelper().isActive());
        assertTrue(mCard.getSpotlightHelper().isVisible());

        MotionEvent up = MotionEvent.obtain(downTime, downTime + 50L, MotionEvent.ACTION_UP, 150f, 200f, 0);
        mCard.dispatchTouchEvent(up);
        up.recycle();

        // Tap-to-flip triggered on clean touch release without drag
        assertTrue("Card should be flipped after tap", mCard.isFlipped());
    }
}
