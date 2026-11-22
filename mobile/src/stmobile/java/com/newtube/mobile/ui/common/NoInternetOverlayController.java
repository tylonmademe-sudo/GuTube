package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;

/**
 * Manages the No Internet Connection overlay with real-time NetworkCallback detection,
 * smooth 250ms fade-in / 200ms fade-out transitions, and lifecycle-safe Lottie playback.
 * 
 * Uses NET_CAPABILITY_INTERNET (with NET_CAPABILITY_NOT_SUSPENDED) rather than waiting
 * for NET_CAPABILITY_VALIDATED to avoid false stuck-offline states on 5G/mobile networks.
 */
public final class NoInternetOverlayController {
    private static final String TAG = "NoInternetOverlay";

    public interface OfflineSectionCheck {
        boolean isOfflineAllowed();
    }

    private final WeakReference<Activity> mActivityRef;
    private final View mContainer;
    private final LottieAnimationView mLottieView;
    private final TextView mTitleView;
    private final TextView mSubtitleView;
    private final MaterialButton mRetryButton;
    private final Runnable mOnRetryAction;
    private final OfflineSectionCheck mOfflineSectionCheck;

    private boolean mIsRegistered;
    private boolean mWasOffline;

    private final ConnectivityManager.NetworkCallback mNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            postEvaluateNetwork();
            postDelayedEvaluate(300);
        }

        @Override
        public void onLost(@NonNull Network network) {
            postEvaluateNetwork();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            postEvaluateNetwork();
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                postDelayedEvaluate(250);
            }
        }
    };

    public NoInternetOverlayController(
            @NonNull Activity activity,
            @NonNull View rootView,
            Runnable onRetryAction,
            OfflineSectionCheck offlineSectionCheck) {
        mActivityRef = new WeakReference<>(activity);
        mOnRetryAction = onRetryAction;
        mOfflineSectionCheck = offlineSectionCheck;

        mContainer = rootView.findViewById(R.id.no_internet_container);
        mLottieView = rootView.findViewById(R.id.no_internet_lottie);
        mTitleView = rootView.findViewById(R.id.no_internet_title);
        mSubtitleView = rootView.findViewById(R.id.no_internet_subtitle);
        mRetryButton = rootView.findViewById(R.id.no_internet_retry_btn);

        if (mLottieView != null) {
            mLottieView.setFailureListener(e ->
                    Log.e(TAG, "Lottie parse/render failure: " + e.getMessage(), e));
        }

        if (mRetryButton != null) {
            mRetryButton.setOnClickListener(v -> {
                Haptics.click(v);
                Activity act = mActivityRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) return;

                // When user taps Retry: if network has internet capability, immediately fade out and trigger retry
                // without waiting for NET_CAPABILITY_VALIDATED
                if (isNetworkOnline(act)) {
                    hideOverlay(true /* trigger retry */);
                } else {
                    // Still completely offline: restart lottie pulse and notify user
                    if (mLottieView != null) {
                        mLottieView.setProgress(0f);
                        mLottieView.playAnimation();
                    }
                    if (mSubtitleView != null) {
                        mSubtitleView.animate()
                                .scaleX(1.05f).scaleY(1.05f)
                                .setDuration(120)
                                .withEndAction(() -> {
                                    if (mSubtitleView != null) {
                                        mSubtitleView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                                    }
                                }).start();
                    }
                }
            });
        }
    }

    /**
     * Call from Activity.onResume(): registers network listener and evaluates state.
     */
    public void onResume() {
        registerNetworkCallback();
        evaluateNetworkState();

        if (isOverlayShowing() && mLottieView != null && !mLottieView.isAnimating()) {
            mLottieView.resumeAnimation();
        }
    }

    /**
     * Call from Activity.onPause(): unregisters network listener and pauses Lottie.
     */
    public void onPause() {
        unregisterNetworkCallback();
        if (mLottieView != null && mLottieView.isAnimating()) {
            mLottieView.pauseAnimation();
        }
    }

    /**
     * Call from Activity.onDestroy(): clean up animations and references.
     */
    public void onDestroy() {
        unregisterNetworkCallback();
        if (mContainer != null) {
            mContainer.animate().cancel();
        }
        if (mLottieView != null) {
            mLottieView.cancelAnimation();
            mLottieView.setImageDrawable(null);
        }
    }

    /**
     * Explicitly update overlay state (e.g. when user changes tabs / sections).
     */
    public void updateState() {
        evaluateNetworkState();
    }

    public boolean isOverlayShowing() {
        return mContainer != null && mContainer.getVisibility() == View.VISIBLE && mContainer.getAlpha() > 0f;
    }

    private void registerNetworkCallback() {
        if (mIsRegistered) return;
        Activity act = mActivityRef.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(mNetworkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, mNetworkCallback);
            }
            mIsRegistered = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register NetworkCallback: " + t.getMessage(), t);
        }
    }

    private void unregisterNetworkCallback() {
        if (!mIsRegistered) return;
        Activity act = mActivityRef.get();
        if (act != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(mNetworkCallback);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to unregister NetworkCallback: " + t.getMessage(), t);
            }
        }
        mIsRegistered = false;
    }

    private void postEvaluateNetwork() {
        Activity act = mActivityRef.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        act.runOnUiThread(this::evaluateNetworkState);
    }

    private void postDelayedEvaluate(long delayMs) {
        Activity act = mActivityRef.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        act.runOnUiThread(() -> {
            if (mContainer != null) {
                mContainer.postDelayed(this::evaluateNetworkState, delayMs);
            }
        });
    }

    private void evaluateNetworkState() {
        Activity act = mActivityRef.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        boolean isOfflineAllowed = mOfflineSectionCheck != null && mOfflineSectionCheck.isOfflineAllowed();
        if (isOfflineAllowed) {
            // User is on Downloads tab -> allow offline viewing, keep overlay hidden
            hideOverlaySilently();
            return;
        }

        boolean online = isNetworkOnline(act);

        if (!online) {
            mWasOffline = true;
            showOverlay();
        } else {
            if (isOverlayShowing() || mWasOffline) {
                mWasOffline = false;
                hideOverlay(true /* trigger retry / refresh */);
            }
        }
    }

    public void showOverlay() {
        if (mContainer == null) return;
        boolean isOfflineAllowed = mOfflineSectionCheck != null && mOfflineSectionCheck.isOfflineAllowed();
        if (isOfflineAllowed) return;

        if (mContainer.getVisibility() == View.VISIBLE && mContainer.getAlpha() >= 1f) {
            if (mLottieView != null && !mLottieView.isAnimating()) {
                mLottieView.playAnimation();
            }
            return;
        }

        mContainer.animate().cancel();
        mContainer.setVisibility(View.VISIBLE);
        mContainer.setAlpha(0f);
        mContainer.setScaleX(0.96f);
        mContainer.setScaleY(0.96f);
        if (mLottieView != null) {
            mLottieView.setVisibility(View.VISIBLE);
            mLottieView.playAnimation();
        }
        mContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public void hideOverlaySilently() {
        if (mContainer == null || mContainer.getVisibility() != View.VISIBLE) return;

        mContainer.animate().cancel();
        mContainer.animate()
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (mContainer != null) {
                        mContainer.setVisibility(View.GONE);
                        mContainer.setScaleX(1f);
                        mContainer.setScaleY(1f);
                    }
                    if (mLottieView != null) {
                        mLottieView.cancelAnimation();
                    }
                })
                .start();
    }

    public void hideOverlay(boolean triggerRetry) {
        if (mContainer == null) return;
        if (mContainer.getVisibility() != View.VISIBLE && mContainer.getAlpha() == 0f) {
            if (triggerRetry && mOnRetryAction != null) {
                mOnRetryAction.run();
            }
            return;
        }

        mContainer.animate().cancel();
        mContainer.animate()
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (mContainer != null) {
                        mContainer.setVisibility(View.GONE);
                        mContainer.setScaleX(1f);
                        mContainer.setScaleY(1f);
                    }
                    if (mLottieView != null) {
                        mLottieView.cancelAnimation();
                    }
                    if (triggerRetry && mOnRetryAction != null) {
                        mOnRetryAction.run();
                    }
                })
                .start();
    }

    /**
     * Checks if internet connectivity is active.
     * Uses NET_CAPABILITY_INTERNET with NET_CAPABILITY_NOT_SUSPENDED so 5G/mobile networks
     * that delay NET_CAPABILITY_VALIDATED are not falsely marked offline.
     */
    public static boolean isNetworkOnline(Context context) {
        if (context == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps == null) return false;

                boolean hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                boolean notSuspended = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                        || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);

                return hasInternet && notSuspended;
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Throwable t) {
            return true;
        }
    }
}
