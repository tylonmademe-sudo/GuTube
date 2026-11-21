package com.newtube.mobile.ui.playback;

import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * GUTUBE(frame-studio): Advanced, lag-free Frame Studio for high-precision frame scrubbing,
 * dynamic step controller, quality selection, and zero-UI clean screenshot captures
 * 1:1 matching the Liquid Glass optical HUD design system.
 */
public class FrameStudioOverlay {
    private static final String PREFS_NAME = "gutube_frame_studio";
    private static final String KEY_STEP_TYPE = "step_type";
    private static final String KEY_STEP_VALUE = "step_value";
    private static final String KEY_CUSTOM_UNIT = "custom_unit";
    private static final String KEY_QUALITY_HEIGHT = "preferred_quality_height";

    private static final int STEP_FRAME = 0;
    private static final int STEP_SECOND = 1;
    private static final int STEP_MINUTE = 2;
    private static final int STEP_CUSTOM = 3;

    private static final int UNIT_FRAME = 0;
    private static final int UNIT_SECOND = 1;
    private static final int UNIT_MINUTE = 2;

    private static final int[] RES_PRESETS = {2160, 1440, 1080, 720, 480, 360};
    private static final String[] RES_FULL_LABELS = {"4K UHD", "2K QHD", "1080p FHD", "720p HD", "480p SD", "360p SD"};
    private static final String[] RES_SHORT_LABELS = {"4K", "2K", "1080p", "720p", "480p", "360p"};

    private final MobilePlaybackActivity mActivity;
    private final View mRoot;
    private final View mFlashView;
    private final View mTopBar;
    private final TextView mTimestampView;
    private final View mPopoversContainer;
    private final View mStepPopover;
    private final View mResPopover;
    private final View mDock;
    private final View mStepSelectorContainer;
    private final TextView mStepSelectorText;
    private final View mResSelectorContainer;
    private final TextView mResSelectorText;
    private final ImageButton mStepBackBtn;
    private final ImageButton mStepForwardBtn;
    private final View mSaveBtn;

    // Popover Views
    private final TextView mTabFrame;
    private final TextView mTabSecond;
    private final TextView mTabMinute;
    private final TextView mTabCustom;
    private final TextView mStepInfoView;
    private final View mChipsFrame;
    private final View mChipsSecond;
    private final View mChipsMinute;
    private final View mCustomContainer;
    private final EditText mCustomInput;
    private final Spinner mCustomUnitSpinner;
    private final Button mCustomApplyBtn;
    private final LinearLayout mResChipsContainer;

    // Saved Confirmation Card
    private final View mSavedCard;
    private final ImageView mSavedThumb;
    private final TextView mSavedBadge;
    private final TextView mSavedTitle;
    private final TextView mSavedAlbum;
    private final View mSavedShare;
    private final View mSavedGallery;
    private final View mSavedDismiss;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDismissSavedCardRunnable = () -> hideSavedCard();

    private boolean mIsOpen = false;
    private boolean mControlsHidden = false;
    private SeekParameters mPreviousSeekParameters = null;

    private int mStepType = STEP_FRAME;
    private int mStepValue = 1;
    private int mCustomUnit = UNIT_FRAME;
    private int mSelectedQualityHeight = 1080;
    @Nullable private Uri mLastSavedUri = null;

    public FrameStudioOverlay(@NonNull MobilePlaybackActivity activity, @NonNull View root) {
        mActivity = activity;
        mRoot = root;

        mFlashView = root.findViewById(R.id.frame_studio_flash);
        mTopBar = root.findViewById(R.id.frame_studio_top_bar);
        mTimestampView = root.findViewById(R.id.frame_studio_timestamp);
        mPopoversContainer = root.findViewById(R.id.frame_studio_popover_container);
        mStepPopover = root.findViewById(R.id.frame_studio_step_popover);
        mResPopover = root.findViewById(R.id.frame_studio_res_popover);
        mDock = root.findViewById(R.id.frame_studio_dock);

        mStepSelectorContainer = root.findViewById(R.id.frame_studio_step_selector);
        View stepTv = root.findViewById(R.id.frame_studio_step_text);
        mStepSelectorText = stepTv instanceof TextView ? (TextView) stepTv : (TextView) mStepSelectorContainer;

        mResSelectorContainer = root.findViewById(R.id.frame_studio_res_selector);
        View resTv = root.findViewById(R.id.frame_studio_res_text);
        mResSelectorText = resTv instanceof TextView ? (TextView) resTv : (TextView) mResSelectorContainer;

        mStepBackBtn = root.findViewById(R.id.frame_studio_step_back);
        mStepForwardBtn = root.findViewById(R.id.frame_studio_step_forward);
        mSaveBtn = root.findViewById(R.id.frame_studio_save_btn);
        ImageView saveIcon = root.findViewById(R.id.frame_studio_save_icon);
        if (saveIcon != null) {
            saveIcon.setColorFilter(0xFF111111);
        }

        // Step Popover Tabs & Chips
        mTabFrame = root.findViewById(R.id.frame_studio_tab_frame);
        mTabSecond = root.findViewById(R.id.frame_studio_tab_second);
        mTabMinute = root.findViewById(R.id.frame_studio_tab_minute);
        mTabCustom = root.findViewById(R.id.frame_studio_tab_custom);
        mStepInfoView = root.findViewById(R.id.frame_studio_step_info);
        mChipsFrame = root.findViewById(R.id.frame_studio_chips_frame);
        mChipsSecond = root.findViewById(R.id.frame_studio_chips_second);
        mChipsMinute = root.findViewById(R.id.frame_studio_chips_minute);
        mCustomContainer = root.findViewById(R.id.frame_studio_custom_container);
        mCustomInput = root.findViewById(R.id.frame_studio_custom_input);
        mCustomUnitSpinner = root.findViewById(R.id.frame_studio_custom_unit);
        mCustomApplyBtn = root.findViewById(R.id.frame_studio_custom_apply);
        mResChipsContainer = root.findViewById(R.id.frame_studio_res_chips);

        // Saved Card
        mSavedCard = root.findViewById(R.id.frame_studio_saved_card);
        mSavedThumb = root.findViewById(R.id.frame_studio_saved_thumb);
        mSavedBadge = root.findViewById(R.id.frame_studio_saved_badge);
        mSavedTitle = root.findViewById(R.id.frame_studio_saved_title);
        mSavedAlbum = root.findViewById(R.id.frame_studio_saved_album);
        mSavedShare = root.findViewById(R.id.frame_studio_saved_share);
        mSavedGallery = root.findViewById(R.id.frame_studio_saved_gallery);
        mSavedDismiss = root.findViewById(R.id.frame_studio_saved_dismiss);

        setupListeners();
        restorePreferences();
    }

    private void restorePreferences() {
        SharedPreferences prefs = mActivity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mStepType = prefs.getInt(KEY_STEP_TYPE, STEP_FRAME);
        mStepValue = prefs.getInt(KEY_STEP_VALUE, 1);
        mCustomUnit = prefs.getInt(KEY_CUSTOM_UNIT, UNIT_FRAME);
        mSelectedQualityHeight = prefs.getInt(KEY_QUALITY_HEIGHT, 1080);
    }

    private void savePreferences() {
        mActivity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STEP_TYPE, mStepType)
                .putInt(KEY_STEP_VALUE, mStepValue)
                .putInt(KEY_CUSTOM_UNIT, mCustomUnit)
                .putInt(KEY_QUALITY_HEIGHT, mSelectedQualityHeight)
                .apply();
    }

    private void setupListeners() {
        // Intercept and consume all touches on Frame Studio root so underlying video player
        // views (PinchZoomLayout, PlayerContainerLayout, DoubleTapPlayerView) receive nothing!
        GestureDetector gestureDetector = new GestureDetector(mActivity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mStepPopover.getVisibility() == View.VISIBLE || mResPopover.getVisibility() == View.VISIBLE) {
                    hidePopovers();
                } else {
                    toggleCleanPreview();
                }
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        mRoot.setOnTouchListener((v, event) -> {
            // Disallow parent views (PinchZoomLayout, PlayerContainerLayout, WatchRootLayout)
            // from intercepting any touch gestures while Frame Studio is active!
            if (v.getParent() != null) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            gestureDetector.onTouchEvent(event);
            return true; // Consume touch event completely to isolate touches from underlying player
        });

        // Close Button
        View closeBtn = mRoot.findViewById(R.id.frame_studio_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> close());
        }

        // Hold-to-Scrub Listeners for Backward / Forward
        mStepBackBtn.setOnTouchListener(new HoldToScrubListener(-1));
        mStepForwardBtn.setOnTouchListener(new HoldToScrubListener(1));

        // Step Selector Menu Toggle
        View stepClickTarget = mStepSelectorContainer != null ? mStepSelectorContainer : mStepSelectorText;
        stepClickTarget.setOnClickListener(v -> {
            Haptics.tick(v);
            if (mStepPopover.getVisibility() == View.VISIBLE) {
                hidePopovers();
            } else {
                mResPopover.setVisibility(View.GONE);
                showStepPopover();
            }
        });

        // Resolution Selector Menu Toggle
        View resClickTarget = mResSelectorContainer != null ? mResSelectorContainer : mResSelectorText;
        resClickTarget.setOnClickListener(v -> {
            Haptics.tick(v);
            if (mResPopover.getVisibility() == View.VISIBLE) {
                hidePopovers();
            } else {
                mStepPopover.setVisibility(View.GONE);
                showResPopover();
            }
        });

        // Save Button
        mSaveBtn.setOnClickListener(v -> captureAndSave());

        // Step Popover Tabs
        mTabFrame.setOnClickListener(v -> selectStepTab(STEP_FRAME));
        mTabSecond.setOnClickListener(v -> selectStepTab(STEP_SECOND));
        mTabMinute.setOnClickListener(v -> selectStepTab(STEP_MINUTE));
        mTabCustom.setOnClickListener(v -> selectStepTab(STEP_CUSTOM));

        // Preset Chips
        bindChip(mRoot.findViewById(R.id.frame_chip_1f), STEP_FRAME, 1);
        bindChip(mRoot.findViewById(R.id.frame_chip_2f), STEP_FRAME, 2);
        bindChip(mRoot.findViewById(R.id.frame_chip_5f), STEP_FRAME, 5);
        bindChip(mRoot.findViewById(R.id.frame_chip_10f), STEP_FRAME, 10);

        bindChip(mRoot.findViewById(R.id.frame_chip_05s), STEP_SECOND, 500);
        bindChip(mRoot.findViewById(R.id.frame_chip_1s), STEP_SECOND, 1000);
        bindChip(mRoot.findViewById(R.id.frame_chip_2s), STEP_SECOND, 2000);
        bindChip(mRoot.findViewById(R.id.frame_chip_5s), STEP_SECOND, 5000);

        bindChip(mRoot.findViewById(R.id.frame_chip_1m), STEP_MINUTE, 1);
        bindChip(mRoot.findViewById(R.id.frame_chip_2m), STEP_MINUTE, 2);
        bindChip(mRoot.findViewById(R.id.frame_chip_5m), STEP_MINUTE, 5);

        // Safe Insets handling for notches and cutouts
        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0;
            int right = 0;
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left = bars.left;
                right = bars.right;
                top = bars.top;
                bottom = bars.bottom;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = cutout.getSafeInsetLeft();
                    right = cutout.getSafeInsetRight();
                    top = cutout.getSafeInsetTop();
                    bottom = cutout.getSafeInsetBottom();
                }
            }
            applyInsetsInternal(left, top, right, bottom);
            return insets;
        });

        // Custom Unit Spinner Setup
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_spinner_item,
                new String[]{mActivity.getString(R.string.frame_studio_unit_frames),
                             mActivity.getString(R.string.frame_studio_unit_seconds),
                             mActivity.getString(R.string.frame_studio_unit_minutes)}) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                    ((TextView) v).setTextSize(11);
                    ((TextView) v).setGravity(android.view.Gravity.CENTER);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                    ((TextView) v).setBackgroundColor(0xFF202024);
                    ((TextView) v).setTextSize(12);
                    ((TextView) v).setPadding(24, 20, 24, 20);
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCustomUnitSpinner.setAdapter(adapter);
        mCustomUnitSpinner.setSelection(mCustomUnit);

        mCustomApplyBtn.setOnClickListener(v -> {
            String text = mCustomInput.getText().toString().trim();
            int val = 1;
            try {
                val = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {}
            if (val <= 0) val = 1;
            mStepType = STEP_CUSTOM;
            mStepValue = val;
            mCustomUnit = mCustomUnitSpinner.getSelectedItemPosition();
            savePreferences();
            updateStepSelectorButton();
            updateChipHighlight();
            hidePopovers();
        });

        // Saved Card Action Listeners
        if (mSavedShare != null) {
            mSavedShare.setOnClickListener(v -> {
                if (mLastSavedUri != null) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_STREAM, mLastSavedUri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    mActivity.startActivity(Intent.createChooser(intent, mActivity.getString(R.string.frame_studio_share)));
                }
            });
        }

        if (mSavedGallery != null) {
            mSavedGallery.setOnClickListener(v -> {
                if (mLastSavedUri != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(mLastSavedUri, "image/png");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        mActivity.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Intent fallback = new Intent(Intent.ACTION_VIEW);
                        fallback.setType("image/*");
                        mActivity.startActivity(fallback);
                    }
                }
            });
        }

        if (mSavedDismiss != null) {
            mSavedDismiss.setOnClickListener(v -> hideSavedCard());
        }
    }

    private void bindChip(View chipView, int stepType, int value) {
        if (chipView != null) {
            chipView.setOnClickListener(v -> {
                Haptics.tick(v);
                mStepType = stepType;
                mStepValue = value;
                savePreferences();
                updateStepSelectorButton();
                updateChipHighlight();
                hidePopovers();
            });
        }
    }

    private void updateChipHighlight() {
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_1f), mStepType == STEP_FRAME && mStepValue == 1);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_2f), mStepType == STEP_FRAME && mStepValue == 2);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_5f), mStepType == STEP_FRAME && mStepValue == 5);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_10f), mStepType == STEP_FRAME && mStepValue == 10);

        updateSingleChip(mRoot.findViewById(R.id.frame_chip_05s), mStepType == STEP_SECOND && mStepValue == 500);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_1s), mStepType == STEP_SECOND && (mStepValue == 1000 || mStepValue == 1));
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_2s), mStepType == STEP_SECOND && (mStepValue == 2000 || mStepValue == 2));
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_5s), mStepType == STEP_SECOND && (mStepValue == 5000 || mStepValue == 5));

        updateSingleChip(mRoot.findViewById(R.id.frame_chip_1m), mStepType == STEP_MINUTE && mStepValue == 1);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_2m), mStepType == STEP_MINUTE && mStepValue == 2);
        updateSingleChip(mRoot.findViewById(R.id.frame_chip_5m), mStepType == STEP_MINUTE && mStepValue == 5);
    }

    private void updateSingleChip(View chip, boolean active) {
        if (chip instanceof TextView) {
            TextView tv = (TextView) chip;
            tv.setBackgroundResource(active ? R.drawable.bg_frame_studio_chip_active : R.drawable.bg_frame_studio_chip_inactive);
            tv.setTextColor(active ? 0xFFFFFFFF : 0xCCFFFFFF);
        }
    }

    private void applyInsetsInternal(int left, int top, int right, int bottom) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        // Top bar: Left & Right safe inset (min 16dp), Top padding (14dp + notch/status bar inset), Bottom 12dp
        mTopBar.setPadding(
                Math.max(Math.round(16 * density), left),
                top + Math.round(14 * density),
                Math.max(Math.round(16 * density), right),
                Math.round(12 * density)
        );
        int dockBottomMargin = Math.max(Math.round(12 * density), bottom + Math.round(6 * density));
        FrameLayout.LayoutParams dockLp = (FrameLayout.LayoutParams) mDock.getLayoutParams();
        if (dockLp != null) {
            dockLp.bottomMargin = dockBottomMargin;
            mDock.setLayoutParams(dockLp);
        }
        // Floating popover menu container: strictly above the dock by at least 14dp
        int popoverBottomMargin = dockBottomMargin + Math.round(42 * density) + Math.round(14 * density);
        View popoverContainer = mRoot.findViewById(R.id.frame_studio_popover_container);
        if (popoverContainer != null) {
            FrameLayout.LayoutParams popLp = (FrameLayout.LayoutParams) popoverContainer.getLayoutParams();
            if (popLp != null) {
                popLp.bottomMargin = popoverBottomMargin;
                popoverContainer.setLayoutParams(popLp);
            }
        }
        // Bottom-left saved card: floats cleanly above dock level with zero dock overlap
        if (mSavedCard != null) {
            FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) mSavedCard.getLayoutParams();
            if (cardLp != null) {
                cardLp.bottomMargin = popoverBottomMargin;
                cardLp.leftMargin = Math.max(Math.round(16 * density), left);
                mSavedCard.setLayoutParams(cardLp);
            }
        }
        if (mResPopover != null && mResPopover.getVisibility() == View.VISIBLE) {
            alignResPopoverWithButton();
        }
        if (mIsOpen) {
            ExoPlayer player = mActivity.getPlayer();
            long pos = player != null ? player.getCurrentPosition() : 0;
            long dur = player != null ? player.getDuration() : 0;
            updateTimestamp(pos, dur);
        }
    }

    private void updateInsetsImmediately() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.view.WindowInsets rootInsets = mRoot.getRootWindowInsets();
            if (rootInsets != null) {
                int left = 0, right = 0, top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = rootInsets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    left = bars.left;
                    right = bars.right;
                    top = bars.top;
                    bottom = bars.bottom;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    android.view.DisplayCutout cutout = rootInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = cutout.getSafeInsetLeft();
                        right = cutout.getSafeInsetRight();
                        top = cutout.getSafeInsetTop();
                        bottom = cutout.getSafeInsetBottom();
                    }
                }
                applyInsetsInternal(left, top, right, bottom);
                return;
            }
        }
        applyInsetsInternal(0, 0, 0, 0);
    }

    public boolean isOpen() {
        return mIsOpen;
    }

    public void open() {
        if (mIsOpen) return;
        mIsOpen = true;
        mControlsHidden = false;

        // 1. Immediately pause video
        ExoPlayer player = mActivity.getPlayer();
        if (player != null && player.isPlaying()) {
            player.pause();
        }

        // 2. Hide normal player controls and all floating pills/bars to guarantee zero overlap
        mActivity.hideControls();
        View controlsRoot = mActivity.findViewById(R.id.mobile_controls_root);
        if (controlsRoot != null) {
            controlsRoot.setVisibility(View.GONE);
        }
        View timeBar = mActivity.findViewById(R.id.mobile_player_time_bar);
        if (timeBar != null) {
            timeBar.setVisibility(View.GONE);
        }
        View seekTime = mActivity.findViewById(R.id.mobile_player_seek_time);
        if (seekTime != null) {
            seekTime.animate().cancel();
            seekTime.setVisibility(View.GONE);
        }
        View scrubChapter = mActivity.findViewById(R.id.mobile_player_scrub_chapter);
        if (scrubChapter != null) {
            scrubChapter.setVisibility(View.GONE);
        }
        View chapterPill = mActivity.findViewById(R.id.mobile_player_chapter_pill);
        if (chapterPill != null) {
            chapterPill.setVisibility(View.GONE);
        }
        View topPill = mActivity.findViewById(R.id.mobile_player_top_pill);
        if (topPill != null) {
            topPill.setVisibility(View.GONE);
        }

        // 3. Switch seek parameters to EXACT
        if (player != null) {
            mPreviousSeekParameters = player.getSeekParameters();
            player.setSeekParameters(SeekParameters.EXACT);
        }

        // 4. Update safe insets immediately
        updateInsetsImmediately();

        // 5. Update UI labels and selectors
        updateStepSelectorButton();
        validateSelectedResolution();
        updateResSelectorButton();
        updateChipHighlight();

        long pos = player != null ? player.getCurrentPosition() : 0;
        long dur = player != null ? player.getDuration() : 0;
        updateTimestamp(pos, dur);

        // 6. Fade-in Frame Studio overlay
        mTopBar.setVisibility(View.VISIBLE);
        mTopBar.setAlpha(1f);
        mTopBar.setTranslationY(0f);
        mDock.setVisibility(View.VISIBLE);
        mDock.setAlpha(1f);
        mDock.setTranslationY(0f);
        mRoot.setVisibility(View.VISIBLE);
        mRoot.setAlpha(0f);
        mRoot.animate()
                .alpha(1f)
                .setDuration(180)
                .withEndAction(null)
                .start();
    }

    private boolean isLandscape() {
        return mActivity != null
                && mActivity.getResources() != null
                && mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean isPopoverVisible() {
        return (mStepPopover != null && mStepPopover.getVisibility() == View.VISIBLE)
                || (mResPopover != null && mResPopover.getVisibility() == View.VISIBLE);
    }

    public void close() {
        if (!mIsOpen) return;
        mIsOpen = false;

        hidePopovers();
        hideSavedCard();

        // 1. Restore previous seek parameters
        ExoPlayer player = mActivity.getPlayer();
        if (player != null && mPreviousSeekParameters != null) {
            player.setSeekParameters(mPreviousSeekParameters);
        }

        // 2. Restore time bar
        View timeBar = mActivity.findViewById(R.id.mobile_player_time_bar);
        if (timeBar != null) {
            timeBar.setVisibility(View.VISIBLE);
        }

        // 3. Fade-out Frame Studio overlay
        mRoot.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    mRoot.setVisibility(View.GONE);
                    // 4. Bring back normal player controls
                    mActivity.showControlsInternal(true);
                })
                .start();
    }

    private void toggleCleanPreview() {
        if (isPopoverVisible()) {
            hidePopovers();
            return;
        }
        mControlsHidden = !mControlsHidden;
        float targetAlpha = mControlsHidden ? 0f : 1f;
        float topY = mControlsHidden ? -16f : 0f;
        float dockY = mControlsHidden ? 16f : 0f;
        mTopBar.animate().alpha(targetAlpha).translationY(topY).setDuration(180).start();
        mDock.animate().alpha(targetAlpha).translationY(dockY).setDuration(180).start();
    }

    private void hidePopovers() {
        boolean wasStepVisible = mStepPopover != null && mStepPopover.getVisibility() == View.VISIBLE;
        boolean wasResVisible = mResPopover != null && mResPopover.getVisibility() == View.VISIBLE;

        if (wasStepVisible) {
            mStepPopover.animate()
                    .alpha(0f)
                    .translationY(16f)
                    .setDuration(150)
                    .withEndAction(() -> mStepPopover.setVisibility(View.GONE))
                    .start();
        }
        if (wasResVisible) {
            mResPopover.animate()
                    .alpha(0f)
                    .translationY(16f)
                    .setDuration(150)
                    .withEndAction(() -> mResPopover.setVisibility(View.GONE))
                    .start();
        }

        if (!isLandscape() && mTopBar != null && !mControlsHidden && (wasStepVisible || wasResVisible)) {
            mTopBar.setVisibility(View.VISIBLE);
            mTopBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(160)
                    .withEndAction(null)
                    .start();
        }
    }

    private void selectStepTab(int tabIndex) {
        mTabFrame.setBackgroundResource(tabIndex == STEP_FRAME ? R.drawable.bg_frame_studio_chip_active : 0);
        mTabFrame.setTextColor(tabIndex == STEP_FRAME ? 0xFFFFFFFF : 0x99FFFFFF);

        mTabSecond.setBackgroundResource(tabIndex == STEP_SECOND ? R.drawable.bg_frame_studio_chip_active : 0);
        mTabSecond.setTextColor(tabIndex == STEP_SECOND ? 0xFFFFFFFF : 0x99FFFFFF);

        mTabMinute.setBackgroundResource(tabIndex == STEP_MINUTE ? R.drawable.bg_frame_studio_chip_active : 0);
        mTabMinute.setTextColor(tabIndex == STEP_MINUTE ? 0xFFFFFFFF : 0x99FFFFFF);

        mTabCustom.setBackgroundResource(tabIndex == STEP_CUSTOM ? R.drawable.bg_frame_studio_chip_active : 0);
        mTabCustom.setTextColor(tabIndex == STEP_CUSTOM ? 0xFFFFFFFF : 0x99FFFFFF);

        mChipsFrame.setVisibility(tabIndex == STEP_FRAME ? View.VISIBLE : View.GONE);
        mChipsSecond.setVisibility(tabIndex == STEP_SECOND ? View.VISIBLE : View.GONE);
        mChipsMinute.setVisibility(tabIndex == STEP_MINUTE ? View.VISIBLE : View.GONE);
        mCustomContainer.setVisibility(tabIndex == STEP_CUSTOM ? View.VISIBLE : View.GONE);
    }

    private void showStepPopover() {
        if (!isLandscape() && mTopBar != null) {
            mTopBar.animate()
                    .alpha(0f)
                    .translationY(-12f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        if (isPopoverVisible()) {
                            mTopBar.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
        selectStepTab(mStepType);
        updateChipHighlight();
        float fps = getDetectedFps();
        float msPerFrame = 1000f / fps;
        mStepInfoView.setText(String.format(Locale.US, "Detected: %.1ffps (1F ≈ %.1fms)", fps, msPerFrame));
        mStepPopover.setTranslationX(0f);
        mStepPopover.setVisibility(View.VISIBLE);
        mStepPopover.setAlpha(0f);
        mStepPopover.setTranslationY(16f);
        mStepPopover.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void showResPopover() {
        if (!isLandscape() && mTopBar != null) {
            mTopBar.animate()
                    .alpha(0f)
                    .translationY(-12f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        if (isPopoverVisible()) {
                            mTopBar.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
        populateResolutionChips();
        mResPopover.setVisibility(View.VISIBLE);
        alignResPopoverWithButton();
        mResPopover.setAlpha(0f);
        mResPopover.setTranslationY(16f);
        mResPopover.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void alignResPopoverWithButton() {
        View targetButton = mResSelectorContainer != null ? mResSelectorContainer : mResSelectorText;
        if (targetButton == null || mDock == null || mRoot == null || mResPopover == null) return;

        float density = mActivity.getResources().getDisplayMetrics().density;
        int popoverWidth = mResPopover.getWidth();
        if (popoverWidth <= 0) {
            int rootW = mRoot.getWidth() > 0 ? mRoot.getWidth() : mActivity.getResources().getDisplayMetrics().widthPixels;
            int rootH = mRoot.getHeight() > 0 ? mRoot.getHeight() : mActivity.getResources().getDisplayMetrics().heightPixels;
            mResPopover.measure(
                    View.MeasureSpec.makeMeasureSpec(rootW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST)
            );
            popoverWidth = mResPopover.getMeasuredWidth();
        }
        if (popoverWidth <= 0) {
            popoverWidth = Math.round(200 * density);
        }

        int[] rootLocation = new int[2];
        int[] buttonLocation = new int[2];
        mRoot.getLocationInWindow(rootLocation);
        targetButton.getLocationInWindow(buttonLocation);

        int rootWidth = mRoot.getWidth();
        if (rootWidth <= 0) {
            rootWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        }

        float buttonCenterInRoot = (buttonLocation[0] - rootLocation[0]) + (targetButton.getWidth() / 2f);
        float popoverInitialCenterInRoot = rootWidth / 2f;
        float targetShiftX = buttonCenterInRoot - popoverInitialCenterInRoot;

        float margin = 12 * density;
        float popoverLeft = popoverInitialCenterInRoot + targetShiftX - (popoverWidth / 2f);
        float popoverRight = popoverLeft + popoverWidth;

        if (popoverRight > rootWidth - margin) {
            targetShiftX -= (popoverRight - (rootWidth - margin));
        }
        if (popoverLeft < margin) {
            targetShiftX += (margin - popoverLeft);
        }
        mResPopover.setTranslationX(targetShiftX);
    }

    private int getMaxAvailableVideoHeight() {
        int max = 1080;
        List<FormatItem> formats = mActivity.getVideoFormats();
        if (formats != null && !formats.isEmpty()) {
            for (FormatItem item : formats) {
                if (item != null && item.getHeight() > max) {
                    max = item.getHeight();
                }
            }
        }
        ExoPlayer player = mActivity.getPlayer();
        if (player != null) {
            Format fmt = player.getVideoFormat();
            if (fmt != null && fmt.height > max) {
                max = fmt.height;
            }
        }
        return max;
    }

    private void validateSelectedResolution() {
        int max = getMaxAvailableVideoHeight();
        if (mSelectedQualityHeight > max) {
            mSelectedQualityHeight = max;
        }
    }

    private void populateResolutionChips() {
        mResChipsContainer.removeAllViews();
        int max = getMaxAvailableVideoHeight();
        float density = mActivity.getResources().getDisplayMetrics().density;
        int rowHeight = Math.round(28 * density);

        java.util.ArrayList<Integer> availableIndices = new java.util.ArrayList<>();
        for (int i = 0; i < RES_PRESETS.length; i++) {
            if (RES_PRESETS[i] <= max) {
                availableIndices.add(i);
            }
        }
        if (availableIndices.isEmpty()) {
            availableIndices.add(2); // 1080p fallback
        }

        for (int i = 0; i < availableIndices.size(); i += 2) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            int idx1 = availableIndices.get(i);
            View chip1 = createResolutionChip(idx1, density);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, rowHeight, 1f);
            lp1.rightMargin = Math.round(3 * density);
            row.addView(chip1, lp1);

            if (i + 1 < availableIndices.size()) {
                int idx2 = availableIndices.get(i + 1);
                View chip2 = createResolutionChip(idx2, density);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, rowHeight, 1f);
                lp2.leftMargin = Math.round(3 * density);
                row.addView(chip2, lp2);
            } else {
                View spacer = new View(mActivity);
                LinearLayout.LayoutParams lpSpacer = new LinearLayout.LayoutParams(0, rowHeight, 1f);
                lpSpacer.leftMargin = Math.round(3 * density);
                row.addView(spacer, lpSpacer);
            }

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Math.round(4 * density);
            mResChipsContainer.addView(row, rowLp);
        }
    }

    private View createResolutionChip(int index, float density) {
        final int height = RES_PRESETS[index];
        final String fullLabel = RES_FULL_LABELS[index];
        final boolean isSelected = (height == mSelectedQualityHeight);

        LinearLayout chip = new LinearLayout(mActivity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(Math.round(4 * density), 0, Math.round(4 * density), 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setBackgroundResource(isSelected ? R.drawable.bg_frame_studio_chip_active : R.drawable.bg_frame_studio_chip_inactive);

        TextView labelView = new TextView(mActivity);
        labelView.setText(fullLabel);
        labelView.setTextSize(10f);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        labelView.setShadowLayer(3f, 0f, 1f, 0xCC000000);
        labelView.setTextColor(isSelected ? 0xFFFFFFFF : 0xCCFFFFFF);
        labelView.setTypeface(Typeface.create("sans-serif-medium", isSelected ? Typeface.BOLD : Typeface.NORMAL));
        chip.addView(labelView);

        if (isSelected) {
            ImageView checkView = new ImageView(mActivity);
            checkView.setImageResource(R.drawable.ic_mobile_check);
            checkView.setColorFilter(0xFFFFFFFF);
            int checkSize = Math.round(11 * density);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(checkSize, checkSize);
            iconLp.leftMargin = Math.round(2 * density);
            chip.addView(checkView, iconLp);
        }

        chip.setOnClickListener(v -> {
            Haptics.tick(v);
            mSelectedQualityHeight = height;
            savePreferences();
            updateResSelectorButton();
            hidePopovers();
        });

        return chip;
    }

    private void updateStepSelectorButton() {
        String label;
        switch (mStepType) {
            case STEP_FRAME:
                label = mStepValue == 1 ? "1 Frame" : mStepValue + " Frames";
                break;
            case STEP_SECOND:
                if (mStepValue == 500) {
                    label = "0.5s";
                } else if (mStepValue >= 1000) {
                    label = (mStepValue / 1000) + "s";
                } else {
                    label = mStepValue + "s";
                }
                break;
            case STEP_MINUTE:
                label = mStepValue + "m";
                break;
            case STEP_CUSTOM:
                if (mCustomUnit == UNIT_FRAME) {
                    label = mStepValue == 1 ? "1 Frame" : mStepValue + " Frames";
                } else if (mCustomUnit == UNIT_MINUTE) {
                    label = mStepValue + "m";
                } else {
                    label = mStepValue + "s";
                }
                break;
            default:
                label = "1 Frame";
        }
        mStepSelectorText.setText(label);
    }

    private void updateResSelectorButton() {
        String label = mSelectedQualityHeight + "p";
        for (int i = 0; i < RES_PRESETS.length; i++) {
            if (RES_PRESETS[i] == mSelectedQualityHeight) {
                label = RES_SHORT_LABELS[i];
                break;
            }
        }
        mResSelectorText.setText(label);
    }

    private float getDetectedFps() {
        ExoPlayer player = mActivity.getPlayer();
        if (player != null) {
            Format format = player.getVideoFormat();
            if (format != null && format.frameRate > 0) {
                return format.frameRate;
            }
        }
        return 30f;
    }

    public long getStepDeltaMs() {
        float fps = getDetectedFps();
        long frameMs = Math.max(1, Math.round(1000f / fps));

        switch (mStepType) {
            case STEP_FRAME:
                return mStepValue * frameMs;
            case STEP_SECOND:
                return mStepValue >= 50 ? mStepValue : mStepValue * 1000L;
            case STEP_MINUTE:
                return mStepValue * 60_000L;
            case STEP_CUSTOM:
                if (mCustomUnit == UNIT_FRAME) {
                    return mStepValue * frameMs;
                } else if (mCustomUnit == UNIT_MINUTE) {
                    return mStepValue * 60_000L;
                } else {
                    return mStepValue * 1000L;
                }
            default:
                return frameMs;
        }
    }

    private void stepSeek(int direction) {
        ExoPlayer player = mActivity.getPlayer();
        if (player == null) return;

        long current = player.getCurrentPosition();
        long duration = player.getDuration();
        long delta = getStepDeltaMs() * direction;
        long target = Math.max(0, Math.min(duration > 0 ? duration : Long.MAX_VALUE, current + delta));

        player.seekTo(target);
        updateTimestamp(target, duration);
        Haptics.tick(mStepBackBtn);
    }

    private void updateTimestamp(long positionMs, long durationMs) {
        String pos = formatMs(positionMs);
        if (isLandscape()) {
            mTimestampView.setTextSize(12f);
            String dur = formatMs(durationMs);
            String full = pos + "  /  " + dur;
            SpannableString span = new SpannableString(full);
            span.setSpan(new StyleSpan(Typeface.BOLD), 0, pos.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, pos.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(0x73FFFFFF), pos.length(), full.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            mTimestampView.setText(span);
        } else {
            mTimestampView.setTextSize(11f);
            SpannableString span = new SpannableString(pos);
            span.setSpan(new StyleSpan(Typeface.BOLD), 0, pos.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, pos.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            mTimestampView.setText(span);
        }
    }

    public static String formatPreciseTime(long positionMs, long durationMs) {
        return formatMs(positionMs) + " / " + formatMs(durationMs);
    }

    private static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long milliseconds = ms % 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
        }
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, milliseconds);
    }

    private void captureAndSave() {
        // 1. Shutter Flash Animation (Soft, calm camera flash)
        mFlashView.setVisibility(View.VISIBLE);
        mFlashView.setAlpha(0.6f);
        mFlashView.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction(() -> mFlashView.setVisibility(View.GONE))
                .start();
        Haptics.tick(mSaveBtn);

        // 2. Determine target width & height based on aspect ratio
        int targetHeight = mSelectedQualityHeight;
        int nativeWidth = 1920;
        int nativeHeight = 1080;
        ExoPlayer player = mActivity.getPlayer();
        if (player != null && player.getVideoFormat() != null && player.getVideoFormat().height > 0) {
            nativeWidth = player.getVideoFormat().width;
            nativeHeight = player.getVideoFormat().height;
        }
        int targetWidth = Math.round((float) targetHeight * nativeWidth / (float) nativeHeight);
        targetWidth = targetWidth & ~1; // Even width

        // 3. Capture clean video surface bitmap
        Bitmap bitmap = mActivity.captureVideoFrame(targetWidth, targetHeight);
        if (bitmap == null) {
            return;
        }

        // 4. Title and Filename formatting
        Video video = mActivity.getVideo();
        String rawTitle = video != null ? video.getTitle() : "GuTube_Video";
        String cleanTitle = sanitizeTitle(rawTitle);

        long positionMs = player != null ? player.getCurrentPosition() : 0;
        String filename = formatFilename(cleanTitle, positionMs, targetHeight);

        // Thumbnail for popup (56dp x 35dp aspect)
        Bitmap thumbBitmap = Bitmap.createScaledBitmap(bitmap, 112, 70, true);

        // 5. Asynchronous MediaStore Saving
        new Thread(() -> {
            Uri savedUri = saveToGallery(mActivity, bitmap, filename);
            mMainHandler.post(() -> {
                if (savedUri != null && mIsOpen) {
                    showSavedCard(thumbBitmap, filename, savedUri, positionMs, targetHeight);
                }
            });
        }).start();
    }

    public static String sanitizeTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return "GuTube_Video";
        }
        String clean = title.replaceAll("[\\\\/:*?\"<>|'#%&{}\\$!@+=`~^,.;\\[\\]()]", " ")
                .trim()
                .replaceAll("\\s+", "_");
        if (clean.length() > 40) {
            clean = clean.substring(0, 40).replaceAll("_+$", "");
        }
        return clean.isEmpty() ? "GuTube_Video" : clean;
    }

    public static String getQualityLabel(int height) {
        for (int i = 0; i < RES_PRESETS.length; i++) {
            if (RES_PRESETS[i] == height) {
                return RES_SHORT_LABELS[i];
            }
        }
        return height + "p";
    }

    public static String formatFilename(String cleanTitle, long positionMs, int qualityHeight) {
        if (positionMs < 0) positionMs = 0;
        long totalSec = positionMs / 1000L;
        long ms = positionMs % 1000L;
        long s = totalSec % 60L;
        long m = (totalSec / 60L) % 60L;
        long h = totalSec / 3600L;
        String qLabel = getQualityLabel(qualityHeight);
        String timePart;
        if (h > 0) {
            timePart = String.format(Locale.US, "%02dh_%02dm_%02ds_%03dms_%s.png", h, m, s, ms, qLabel);
        } else {
            timePart = String.format(Locale.US, "%02dm_%02ds_%03dms_%s.png", m, s, ms, qLabel);
        }
        if (TextUtils.isEmpty(cleanTitle)) {
            return timePart;
        }
        return cleanTitle + "_" + timePart;
    }

    private static Uri saveToGallery(Context context, Bitmap bitmap, String filename) {
        OutputStream out = null;
        Uri resultUri = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GuTube");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                resultUri = context.getContentResolver().insert(collection, values);
                if (resultUri != null) {
                    out = context.getContentResolver().openOutputStream(resultUri);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    context.getContentResolver().update(resultUri, values, null, null);
                }
            } else {
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File albumDir = new File(picturesDir, "GuTube");
                if (!albumDir.exists()) {
                    albumDir.mkdirs();
                }
                File imageFile = new File(albumDir, filename);
                out = new FileOutputStream(imageFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();

                MediaScannerConnection.scanFile(context, new String[]{imageFile.getAbsolutePath()}, new String[]{"image/png"}, null);
                resultUri = Uri.fromFile(imageFile);
            }
        } catch (Exception e) {
            android.util.Log.e("FrameStudio", "Error saving screenshot", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {}
            }
        }
        return resultUri;
    }

    private void showSavedCard(Bitmap thumb, String filename, Uri savedUri, long positionMs, int qualityHeight) {
        mLastSavedUri = savedUri;
        mSavedThumb.setImageBitmap(thumb);
        String qLabel = getQualityLabel(qualityHeight);
        if (mSavedBadge != null) {
            mSavedBadge.setText(qLabel);
        }
        if (mSavedTitle != null) {
            mSavedTitle.setText("Frame Captured");
        }
        if (mSavedAlbum != null) {
            mSavedAlbum.setText(formatMs(positionMs) + " • " + qLabel + " PNG");
        }

        mSavedCard.setVisibility(View.VISIBLE);
        mSavedCard.setAlpha(0f);
        mSavedCard.setTranslationY(20f);
        mSavedCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        mMainHandler.removeCallbacks(mDismissSavedCardRunnable);
        mMainHandler.postDelayed(mDismissSavedCardRunnable, 3500); // 3.5s auto-dismiss
    }

    private void hideSavedCard() {
        mMainHandler.removeCallbacks(mDismissSavedCardRunnable);
        if (mSavedCard.getVisibility() == View.VISIBLE) {
            mSavedCard.animate()
                    .alpha(0f)
                    .translationY(20f)
                    .setDuration(180)
                    .withEndAction(() -> mSavedCard.setVisibility(View.GONE))
                    .start();
        }
    }

    /** Continuous hold-to-scrub with silky acceleration and haptic ticks */
    private class HoldToScrubListener implements View.OnTouchListener {
        private final int mDirection;
        private boolean mIsHolding = false;
        private int mTickCount = 0;

        private final Runnable mRepeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mIsHolding || !mIsOpen) return;
                mTickCount++;
                stepSeek(mDirection);
                long delay = mTickCount > 15 ? 40 : (mTickCount > 6 ? 60 : 80);
                mMainHandler.postDelayed(this, delay);
            }
        };

        HoldToScrubListener(int direction) {
            mDirection = direction;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mIsHolding = true;
                    mTickCount = 0;
                    v.setPressed(true);
                    stepSeek(mDirection);
                    mMainHandler.postDelayed(mRepeatRunnable, 300); // 300ms initial threshold
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mIsHolding = false;
                    v.setPressed(false);
                    mMainHandler.removeCallbacks(mRepeatRunnable);
                    return true;
            }
            return false;
        }
    }
}
