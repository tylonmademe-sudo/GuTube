package com.newtube.mobile.ui.browse;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * GUTUBE(feed-strip): how Home's floating chip row behaves while the feed scrolls (native YouTube UX).
 * At the top the chips show; scrolling down smoothly slides them upwards (-mRow.getHeight()) and fades them out (alpha 1.0 -> 0.0);
 * scrolling back up smoothly slides them back down into view and fades them back in (alpha 0.0 -> 1.0).
 * The row keeps its layout height while hidden (INVISIBLE, never GONE) so the feed never jumps under it.
 */
public final class ChipBar {
    private static final int SHOWN = 0;
    private static final int HIDDEN = 1;
    private static final long DURATION_MS = 240;

    private final View mRow;
    private final int mSlopPx;
    private boolean mActive;
    private int mState = SHOWN;
    private int mTravel; // signed scroll distance since the last change of direction

    public ChipBar(View row) {
        mRow = row;
        mSlopPx = Math.round(16 * row.getResources().getDisplayMetrics().density);
    }

    /** On for Home and its topics (not over the You panel); off hides row, without animation. */
    public void setActive(boolean active) {
        if (active == mActive) {
            return;
        }
        mActive = active;
        mRow.animate().cancel();
        mState = SHOWN;
        mTravel = 0;
        mRow.setTranslationY(0f);
        mRow.setAlpha(1f);
        mRow.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    public boolean isActive() {
        return mActive;
    }

    /** From the feed's scroll listener; {@code atTop} = the feed cannot scroll further up. */
    public void onScrolled(int dy, boolean atTop) {
        if (!mActive) {
            return;
        }
        if (atTop) {
            mTravel = 0;
            show();
            return;
        }
        if (dy == 0) {
            return;
        }
        mTravel = Integer.signum(dy) == Integer.signum(mTravel) ? mTravel + dy : dy;
        if (mTravel > mSlopPx && mState != HIDDEN) {
            mTravel = 0;
            hide();
        } else if (mTravel < -mSlopPx && mState != SHOWN) {
            mTravel = 0;
            show();
        }
    }

    /** A new section, scroll to the top, or scroll up: show the chips smoothly. */
    public void show() {
        if (!mActive) {
            return;
        }
        if (mState == SHOWN && mRow.getVisibility() == View.VISIBLE
                && mRow.getTranslationY() == 0f && mRow.getAlpha() == 1f) {
            return;
        }
        mState = SHOWN;
        mRow.setVisibility(View.VISIBLE);
        mRow.animate().cancel();
        mRow.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public void hide() {
        if (mState == HIDDEN) {
            return;
        }
        mState = HIDDEN;
        mRow.animate().cancel();
        float travel = -getSlideDistance();
        mRow.animate()
                .translationY(travel)
                .alpha(0f)
                .setDuration(DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (mState == HIDDEN) {
                        mRow.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
    }

    private float getSlideDistance() {
        int h = mRow.getHeight();
        if (h <= 0) {
            h = Math.round(48 * mRow.getResources().getDisplayMetrics().density);
        }
        return h;
    }
}
