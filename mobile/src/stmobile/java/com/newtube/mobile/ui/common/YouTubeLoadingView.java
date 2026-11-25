package com.newtube.mobile.ui.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.RenderMode;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * GUTUBE: Lightweight, auto-managed YouTube Lottie loading spinner.
 * Automatically loads R.raw.yt_loading_spinner, repeats infinitely, and manages
 * playback strictly based on visibility and window attachment to ensure 0% battery/CPU overhead
 * when hidden, detached, or off-screen.
 */
public class YouTubeLoadingView extends LottieAnimationView {

    public YouTubeLoadingView(Context context) {
        super(context);
        init();
    }

    public YouTubeLoadingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public YouTubeLoadingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setAnimation(R.raw.yt_loading_spinner);
        setRepeatCount(LottieDrawable.INFINITE);
        try {
            setRenderMode(RenderMode.HARDWARE);
        } catch (Exception e) {
            setRenderMode(RenderMode.AUTOMATIC);
        }
    }

    private void updateAnimationState() {
        if (isShown()) {
            if (!isAnimating()) {
                playAnimation();
            }
        } else {
            if (isAnimating()) {
                pauseAnimation();
            }
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        updateAnimationState();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimationState();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimationState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimationState();
    }

    @Override
    protected void onDetachedFromWindow() {
        pauseAnimation();
        super.onDetachedFromWindow();
    }
}
