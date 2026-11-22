package com.newtube.mobile.ui.browse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.newtube.mobile.downloads.DownloadItem;
import com.newtube.mobile.downloads.DownloadMenu;
import com.newtube.mobile.downloads.DownloadRegistry;
import com.newtube.mobile.downloads.DownloadsBridge;
import com.newtube.mobile.SessionWarmup;
import com.newtube.mobile.casting.CastPickerLauncher;
import com.newtube.mobile.casting.CastSessionManager;
import com.newtube.mobile.casting.CastTarget;
import com.newtube.mobile.casting.CastVolumeKeys;
import com.newtube.mobile.ui.common.FeedCache;
import com.newtube.mobile.ui.common.FeedSwapWarmup;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.common.Motion;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.common.SkeletonReveal;
import com.newtube.mobile.ui.common.ThemeMode;
import com.newtube.mobile.ui.update.MobileUpdateActivity;
import com.newtube.mobile.update.AppUpdates;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.PlayerGesturePrefs;
import com.newtube.mobile.ui.playback.SystemPipBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch Home shell - Wave 1 vertical slice.
 *
 * Drives the existing {@link BrowsePresenter} exactly like the TV
 * {@code BrowseFragment} does (setView/onViewInitialized + the
 * onSectionFocused/onVideoItemClicked/onScrollEnd input contract), but renders with a
 * {@link BottomNavigationView} for sections (in place of the Leanback headers column)
 * and a flat {@link RecyclerView} grid for the selected section's videos (in place of
 * nested Leanback rows/PageRow fragments - row/shorts/multi-grid layouts are a later
 * wave per ROADMAP.md Wave 2).
 *
 * {@code BottomNavigationView} hard-caps at 5 items, but {@code BrowsePresenter} can
 * deliver 10+ sections (Home, Trending, Subscriptions, History, Music, Gaming, News,
 * Playlists, Settings, ...). The bar shows only Home, the two tabs picked in {@link NavTabs},
 * Search and the synthetic You tab - no backfill; every other
 * delivered section (and Settings, and the account entry) is reachable through the You
 * tab's list instead (YouTube's "You" model - there is no drawer).
 *
 * Wave 2: tapping a card routes through {@link BrowsePresenter#onVideoItemClicked} into
 * the real touch player ({@code MobilePlaybackActivity}).
 */
public class MobileBrowseActivity extends MobileActivity
        implements BrowseView, MiniPlayerBridge.MiniHost {
    /** BottomNavigationView item ids must be non-zero; BrowseSection ids start at 0. */
    static final int ITEM_ID_OFFSET = 1_000_000;
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;
    /**
     * NEWTUBE(lazy-home): Home's next section page is requested when the last visible card is this
     * close to the end (~6 portrait screens of full-width cards) - far enough ahead that a page
     * (one /browse, ~0.2-0.5 s) lands before the reader reaches the end. See HomeSectionPacer.
     */
    private static final int NEAR_END_LOOKAHEAD_ITEMS = 16;
    /** BottomNavigationView hard-caps at this many items. */
    private static final int MAX_NAV_ITEMS = 5;
    /**
     * Menu item id of the synthetic "You" tab (account + extra sections + settings). Far above
     * {@link #ITEM_ID_OFFSET} + any real section id, so it can never collide with one.
     */
    static final int YOU_ITEM_ID = 2_000_000;
    /** GUTUBE(search): the Search tab - opens the search screen, never stays selected. */
    static final int SEARCH_ITEM_ID = 2_000_001;
    static final int CAST_ITEM_ID = 2_000_002;
    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    /** Intent action (download notifications) that lands on the Downloads tab. */
    public static final String ACTION_OPEN_DOWNLOADS = "com.newtube.mobile.OPEN_DOWNLOADS";
    /** App launcher shortcut actions. */
    public static final String ACTION_SEARCH = "com.newtube.mobile.action.SEARCH";
    public static final String ACTION_SHORTS = "com.newtube.mobile.action.SHORTS";
    public static final String ACTION_SUBSCRIPTIONS = "com.newtube.mobile.action.SUBSCRIPTIONS";
    public static final String ACTION_HISTORY = "com.newtube.mobile.action.HISTORY";

    private BrowsePresenter mPresenter;

    private RecyclerView mContentGrid;
    private SwipeRefreshLayout mContentSwipe;
    private View mFeedSkeleton;
    /** GUTUBE(issue #91): the skeleton's card ghosts, kept while List layout's rows stand in. */
    @Nullable
    private List<View> mSkeletonCards;
    private boolean mSkeletonRows;

    /** GUTUBE(issue #91): a load or refresh's ghosts take the feed's shape - cards, or rows. */
    /** Issue #92: the tab on screen as cards or as list rows, by its own Customize setting. */
    private void applyVideoLayout() {
        String page = HomeLayout.PAGE_LIBRARY;
        for (int chip : HOME_CHIPS) {
            if (chip == mCurrentSectionId) {
                page = HomeLayout.PAGE_HOME;
            }
        }
        if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
            page = HomeLayout.PAGE_SUBSCRIPTIONS;
        }
        boolean list = HomeLayout.isVideoList(this, page);
        if (mAdapter != null) {
            mAdapter.setListRows(list);
        }
        if (mFeedSkeleton != null) {
            applyFeedSkeletonShape(list);
        }
    }

    private void applyFeedSkeletonShape(boolean rows) {
        ViewGroup skeleton = (ViewGroup) mFeedSkeleton;
        if (mSkeletonCards == null) {
            mSkeletonCards = new ArrayList<>();
            for (int i = 0; i < skeleton.getChildCount(); i++) {
                mSkeletonCards.add(skeleton.getChildAt(i));
            }
        }
        if (mSkeletonRows == rows) {
            return;
        }
        mSkeletonRows = rows;
        skeleton.removeAllViews();
        if (rows) {
            getLayoutInflater().inflate(R.layout.mobile_feed_skeleton_rows, skeleton, true);
        } else {
            for (View card : mSkeletonCards) {
                skeleton.addView(card);
            }
        }
    }
    /** NEWTUBE(motion): last session's cards for the current section, shown only if its load fails. */
    @androidx.annotation.Nullable
    private List<Video> mLastSessionFallback;
    /** NEWTUBE(motion): a reload's skeleton hid the grid (showReloadSkeleton). */
    private boolean mGridHiddenForSkeleton;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    // GUTUBE(feed-strip, 2.9.8): Home's topic chips / Subscriptions' channel avatars above the feed.
    private FeedStrip mStrip;
    private RecyclerView mChipRow;
    private com.newtube.mobile.ui.common.GlassChipTrack mChipTrack;
    private com.google.android.material.appbar.AppBarLayout mAppBar;
    // GUTUBE(nav hide, issue #37): the floating nav pill slides away while a feed scrolls down.
    private com.newtube.mobile.ui.common.NavHider mNavHider;
    // GUTUBE(search fab): Search floated out of the slid-away bar; out = shown in its corner.
    private View mSearchFab;
    private boolean mSearchFabOut;
    private boolean mNavHeld;
    private float mNavY; // how far NavHider has the bar slid down
    @Nullable
    private android.animation.ValueAnimator mSearchMorphAnim;

    // GUTUBE(home-rows): Home drawn as SmartTube's shelves (HomeLayout), mirroring mAdapter's list.
    private HomeRowsAdapter mRows;
    private androidx.recyclerview.widget.ConcatAdapter mFeedAdapter;
    /** GUTUBE(feed segment): All | Videos | Shorts on Subscriptions and pinned channels. */
    private ShortsSegment mSegment;
    private int mSegmentSectionId = Integer.MIN_VALUE;
    private androidx.recyclerview.widget.ConcatAdapter mRowsFeedAdapter;
    private boolean mRowsShown;
    /** Home's chips (issue #4): the topic feeds SmartTube already has, shown as Home filters. */
    private static final int[] HOME_CHIPS = {MediaGroup.TYPE_HOME, MediaGroup.TYPE_MUSIC,
            MediaGroup.TYPE_GAMING, MediaGroup.TYPE_NEWS, MediaGroup.TYPE_SPORTS, MediaGroup.TYPE_LIVE};
    /**
     * Subscriptions narrowed to one channel (issue #2); null = every channel. GUTUBE(issue #79):
     * also Home narrowed to one of its personal chips ({@link #mHomeChip}).
     */
    @Nullable private Video mSubsChannel;
    /**
     * GUTUBE(issue #79): Home's personal chips, the channels the account watches most (its watch
     * history). YouTube's own Home chips are WEB-only, and WEB refuses the TV sign-in (400, as does
     * every non-TV client), so they are built here. Empty = none yet, null = ask.
     */
    @Nullable private List<Video> mHomeChips;
    /** The personal chip whose channel feed is on Home now (the channel filter's feed). */
    @Nullable private Video mHomeChip;
    @Nullable private io.reactivex.rxjava3.disposables.Disposable mHomeChipsAction;
    /** Personal chips' strip ids, clear of the section ids the topic chips use. */
    private static final long HOME_CHIP_ID = 1_000_000;
    private static final int HOME_CHIPS_MAX = 10;
    /**
     * GUTUBE(hyped): Hyped's chart, one list per category (in order, Top 100 first), and the
     * category on screen (null: the first). Every category comes in the one fetch; a chip only
     * changes which list the grid shows.
     */
    private final Map<String, List<Video>> mHypedGroups = new java.util.LinkedHashMap<>();
    @Nullable private String mHypedChip;
    private static final long HYPED_CHIP_ID = 2_000_000;
    /** That channel's last page, for the next one. */
    @Nullable private MediaGroup mSubsChannelPage;
    @Nullable private io.reactivex.rxjava3.disposables.Disposable mSubsAction;
    /** GUTUBE(you page): the You page's History row, fetched each time the page opens. */
    @Nullable private io.reactivex.rxjava3.disposables.Disposable mYouHistoryAction;
    private VideoCardAdapter mYouHistoryAdapter;
    /** GUTUBE(issue #215): the History row is to land at its start once this visit's lists are in. */
    private boolean mYouHistoryToStart;
    @Nullable private List<Video> mSubsChannels;
    // GUTUBE(issue #87): Video holds its group weakly; keep the strip's menu context alive.
    @Nullable private VideoGroup mSubsChannelsGroup;
    @Nullable private VideoGroup mSubsMenuGroup;
    private int mSubsMenuRequest;
    @Nullable private io.reactivex.rxjava3.disposables.Disposable mSubsMenuAction;
    @Nullable private io.reactivex.rxjava3.disposables.Disposable mSubsChannelsAction;
    private BottomNavigationView mBottomNav;
    // "You" tab panel (account header + grouped section rows + Settings row; replaces the drawer).
    private View mYouPanel;
    private LinearLayout mYouRows;
    /** You-tab account header views (sign-in entry / current account name+email+avatar). */
    private TextView mYouAccountText;
    private TextView mYouAccountSub;
    private ImageView mYouAvatar;
    private final Handler mTypewriterHandler = new Handler(Looper.getMainLooper());
    private Runnable mTypewriterRunnable;
    private int mTypewriterPhraseIndex = 0;
    private int mTypewriterCharIndex = 0;
    private boolean mTypewriterDeleting = false;
    private boolean mTypewriterRunning = false;
    /** True while the You panel covers the content grid (the You tab is selected). */
    private boolean mYouShowing;
    private HomePreview mPreview; // GUTUBE(home autoplay)
    /** True when the current grid section was opened from a You-panel row (back returns to You). */
    private boolean mSectionFromYou;
    private BrowseTopBar mTopBar;
    private View mErrorContainer;
    private ImageView mErrorIcon;
    private TextView mErrorMessage;
    private MaterialButton mErrorAction;
    private ImageButton mSearchButton;
    private ImageButton mCastButton;
    /** Process-wide cast session singleton; Browse only reads state + opens the picker. */
    private CastSessionManager mCastSessionManager;

    // Floating in-app mini-player (YouTube-style video-only card over the grid's bottom-right
    // corner; renders the playback activity's live player after a swipe-down minimize - see
    // MiniPlayerBridge).
    private View mMiniPlayerBar;
    private FrameLayout mMiniPlayerFrame;
    private ImageView mMiniFreeze;
    private TextureView mMiniTexture;
    private ImageButton mMiniPlayPause;
    private ProgressBar mMiniProgress;
    /** 500ms UI ticker while the bar is visible: progress line, play/pause icon, liveness check. */
    private final Runnable mMiniPlayerTick = this::onMiniPlayerTick;
    private float[] mMiniPlace; // the card's dragged place, kept while the app is away (onStop)
    private int mMiniPlaceSession;
    /**
     * SCREEN-ON (#50): the card's screen-on hold follows the player's own events, not the 500 ms
     * tick. onPause stops the tick but keeps the card up (frozen, audio playing) while a translucent
     * window such as the card menu sits on top and this Activity never reaches onStop - a pause or
     * end there must still let the screen go, and playing there must still hold it.
     */
    private final Player.Listener mMiniScreenOnListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                setMiniKeepScreenOn(isMiniActivelyPlaying(player));
                if (mMiniPip != null) {
                    mMiniPip.update(); // GUTUBE(issue #233): disarm even while the host tick is stopped
                }
            }
        }
    };
    private Player mMiniScreenOnPlayer;
    private static final long MINI_TICK_MS = 500;
    /** NEWTUBE(motion): the longest the expanding player waits for this card to fold. */
    private static final long MINI_FOLD_WAIT_MS = 50;
    /** NEWTUBE(motion): the card was pre-drawn for a minimize; its buttons fade in once it is ours. */
    private boolean mMiniButtonsFadePending;
    private com.newtube.mobile.ui.playback.MiniControls mMiniControls;
    private final MiniPlayerBridge.CardFold mMiniCardFold = onFolded -> {
        foldMiniCard();
        if (onFolded != null) {
            FrameGate.afterNextFrame(mMiniPlayerBar.getRootView(), MINI_FOLD_WAIT_MS, onFolded);
        }
    };

    private final List<BrowseSection> mSections = new ArrayList<>();
    private final List<Video> mCurrentVideos = new ArrayList<>();
    /**
     * GUTUBE(refresh): cards already on screen when Home or one of its topics (issue #185: Gaming...)
     * was pulled to refresh, by section. YouTube answers a refresh with much the same page, so the
     * refreshed list leaves these out and its top fills with the rest, unseen - no extra request.
     * For this run of the app.
     */
    private final java.util.Map<Integer, java.util.Set<Video>> mSeenOnRefresh = new java.util.HashMap<>();

    private java.util.Set<Video> seenOnRefresh() {
        return mSeenOnRefresh.computeIfAbsent(mCurrentSectionId, id -> new java.util.HashSet<>());
    }
    private int mCurrentSectionId = -1;
    private boolean mProgressShowing;
    private boolean mSuppressNavCallback;
    private int mLastPaginationTriggerCount = -1;
    private int mLastNearEndTriggerCount = -1;
    /**
     * The grid is painting a stale {@link FeedCache} snapshot while the presenter refetches the
     * section. While set, the presenter's clear-before-load empty REPLACE is skipped (it would
     * blank the snapshot), and the first fresh group swaps the whole list instead of appending
     * below the stale items.
     */
    private boolean mAwaitingFreshContent;
    /**
     * NEWTUBE(feed-swap): the stale -> fresh swap in flight while the fresh first screen's pictures
     * warm up (see {@link #submitFeed}). While it is pending, grid submissions wait for it - it
     * submits the newest {@link #mCurrentVideos} when it lands.
     */
    private FeedSwapWarmup mFeedSwap;
    /** NEWTUBE(feed-swap): the grid's item animator, parked while a swap is laid out. */
    private RecyclerView.ItemAnimator mParkedItemAnimator;
    private boolean mFeedSwapPinTop;
    private final Runnable mRestoreItemAnimator = this::restoreItemAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.newtube.mobile.ui.common.ShortsSeen.load(this); // GUTUBE(shorts): before the feed is filtered

        // Transitions: same task as the player now (singleTop + reorder, see the manifest note).
        // During interactive minimize this already-rendered Activity remains visible through the
        // translucent player. The final zero-duration reorder only hands the live texture to the
        // mini card; ordinary navigation still uses BrowseWindowAnimation's quick fade.

        setContentView(R.layout.activity_mobile_browse);
        com.newtube.mobile.ui.common.LaunchSplash.play(this);

        registerBackHandler(this::handleBack);
        sLive = new java.lang.ref.WeakReference<>(this);

        bindViews();
        if (savedInstanceState == null) {
            beatBrandMark();
        }
        setupContentGrid();
        setupBottomNav();
        setupYouPanel();
        setupErrorAction();
        setupSearchButton();
        setupCastButton();

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
        // The presenter just reported every section; build the bar ONCE, now, so the first
        // measure already has its tabs (see rebuildNavigation).
        flushNavRebuild();

        // NEWTUBE(downloads): keep the Downloads grid live (progress badges, finished files)
        // while it is the section on screen; see onDownloadsChanged.
        DownloadRegistry.instance(this).addListener(mDownloadsListener);
        handleOpenDownloads(getIntent());
        handleShortcutIntent(getIntent());

        // NEWTUBE(update-flow): a known update puts a dot on the You tab and a row at the top of
        // its list; the first launch after an update says so (once the feed has had its moment).
        AppUpdates.instance(this).addListener(mUpdatesListener);
        refreshUpdateBadge();
        mBottomNav.postDelayed(mAnnounceUpdateInstalled, UPDATED_NOTICE_DELAY_MS);

        // GUTUBE(github-updater): clean up any leftover update APKs and check on startup
        com.newtube.mobile.update.GitHubAppUpdater.cleanupOldUpdates(this);
        if (savedInstanceState == null) {
            getWindow().getDecorView().postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    com.newtube.mobile.update.GitHubAppUpdater.checkUpdateOnStartup(this);
                }
            }, 3000);
        }

        // Android 13+ (targetSdk 35): POST_NOTIFICATIONS is a runtime permission - without it
        // the media-playback notification never shows on a fresh install. Ask plainly on every
        // cold start until granted; the framework itself stops showing the dialog after two
        // denials, so no extra bookkeeping (and no custom rationale UI) is needed.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void bindViews() {
        mContentGrid = findViewById(R.id.mobile_content_grid);
        mContentSwipe = findViewById(R.id.mobile_content_swipe);
        mFeedSkeleton = findViewById(R.id.mobile_feed_skeleton);
        setupSwipeRefresh();
        mBottomNav = findViewById(R.id.mobile_bottom_nav);
        mYouPanel = findViewById(R.id.mobile_you_panel);
        com.newtube.mobile.ui.common.RgbGlow youGlow = findViewById(R.id.mobile_you_glow);
        float youFadeIn = 64 * getResources().getDisplayMetrics().density;
        mYouPanel.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float strength = Math.min(1f, y / youFadeIn);
            if (youGlow != null) {
                youGlow.setBandCover(strength);
            }
        });
        mYouRows = findViewById(R.id.mobile_you_rows);
        mYouAccountText = findViewById(R.id.mobile_you_account_text);
        mYouAccountSub = findViewById(R.id.mobile_you_account_sub);
        mYouAvatar = findViewById(R.id.mobile_you_avatar);

        mErrorContainer = findViewById(R.id.mobile_error_container);
        mErrorIcon = findViewById(R.id.mobile_error_icon);
        mErrorMessage = findViewById(R.id.mobile_error_message);
        mErrorAction = findViewById(R.id.mobile_error_action);
        mCastButton = findViewById(R.id.mobile_cast_button);
        mSearchButton = findViewById(R.id.mobile_search_button);
        mTopBar = new BrowseTopBar(this, () -> getOnBackPressedDispatcher().onBackPressed());

        // GUTUBE(glass): the floating nav pill blurs the page scrolling under it (real backdrop
        // blur via RenderNode on API 31+, BlurView's own fallback below that).
        View navGlass = findViewById(R.id.mobile_nav_glass);
        com.newtube.mobile.ui.common.Glass.blur(navGlass,
                findViewById(R.id.mobile_blur_target));
        if (navGlass != null) {
            navGlass.bringToFront();
            if (mBottomNav != null) {
                mBottomNav.bringToFront();
            }
        }
        mSearchFab = findViewById(R.id.mobile_search_fab);
        com.newtube.mobile.ui.common.Glass.blur(mSearchFab, findViewById(R.id.mobile_blur_target));
        mSearchFab.setOnClickListener(v -> SearchPresenter.instance(this).startTextSearch());
        // The pill floats above the system bar (content is already inset), so the bar must not
        // pad itself by that inset too - Material does, whatever paddingBottomSystemWindowInsets says.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mBottomNav, (v, insets) -> insets);
        if (com.newtube.mobile.ui.common.YouTubeMode.isOn(this)) {
            // GUTUBE(youtube mode): docked to the bottom edge; the pages end above its full height.
            com.newtube.mobile.ui.common.YouTubeMode.dockBar(findViewById(R.id.mobile_nav_glass), height -> {
                for (int id : new int[] {R.id.mobile_content_grid, R.id.mobile_error_container, R.id.mobile_you_content}) {
                    com.newtube.mobile.ui.common.YouTubeMode.padBottom(findViewById(id), height);
                }
            });
            // The Search bubble the hidden bar leaves behind sits above the gesture area too.
            int fabMargin = ((android.view.ViewGroup.MarginLayoutParams) mSearchFab.getLayoutParams()).bottomMargin;
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mSearchFab, (v, insets) -> {
                com.newtube.mobile.ui.common.YouTubeMode.marginBottom(v, fabMargin + insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom);
                return insets;
            });
        }
        // The top bar sits as the first child of the AppBarLayout, directly beneath the status bar.
        View topGlass = findViewById(R.id.mobile_top_glass);
        if (com.newtube.mobile.ui.common.YouTubeMode.isOn(this)) {
            setUpYouTubeTopBar();
        }
        com.newtube.mobile.ui.common.Glass.blur(findViewById(R.id.mobile_quote_card), findViewById(R.id.mobile_blur_target));
        topGlass.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, oldBottom) -> {
            if (bottom - t != oldBottom - ot) {
                refreshTopPadding();
            }
        });

        mMiniPlayerBar = findViewById(R.id.mobile_mini_player);
        mMiniPlayerFrame = findViewById(R.id.mobile_mini_player_frame);
        mMiniFreeze = findViewById(R.id.mobile_mini_freeze);
        mMiniPlayPause = findViewById(R.id.mobile_mini_play_pause);
        mMiniProgress = findViewById(R.id.mobile_mini_progress);
        com.newtube.mobile.ui.playback.MobileMiniPlayerController.glassButtons(this);
        mMiniControls = new com.newtube.mobile.ui.playback.MiniControls(mMiniPlayPause,
                findViewById(R.id.mobile_mini_close), findViewById(R.id.mobile_mini_audio));
        setupMiniPlayerBar();
        mMiniPip = new com.newtube.mobile.ui.playback.MiniPip(this, mMiniPlayerBar, () -> {
            hideMiniPlayer();
            MiniPlayerBridge.close(); // the PiP window was closed - so is the video
            // This screen floated in a task of its own; the app's task gets a fresh Home when
            // the player finishes, so this copy goes with its window.
            finishAndRemoveTask();
        }, mMiniPlayPause, findViewById(R.id.mobile_mini_close), mMiniProgress,
                findViewById(R.id.mobile_mini_audio));
        // NEWTUBE(mini-inset): the last card can scroll clear of the docked mini-player.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(mMiniPlayerBar, mContentGrid);
        checkPostUpdateConfetti();
    }

    /**
     * Celebratory Confetti Lottie animation played once on the first launch after an app update.
     */
    private void checkPostUpdateConfetti() {
        android.content.SharedPreferences prefs = getSharedPreferences("gutube_app", MODE_PRIVATE);
        String lastSeenVersion = prefs.getString("last_seen_version", null);
        String currentVersion = BuildConfig.VERSION_NAME + "_" + BuildConfig.VERSION_CODE;

        if (lastSeenVersion == null) {
            // Fresh install: save current version and do not play confetti
            prefs.edit().putString("last_seen_version", currentVersion).apply();
            return;
        }

        if (!currentVersion.equals(lastSeenVersion)) {
            // After Update: immediately record current version so it triggers strictly once
            prefs.edit().putString("last_seen_version", currentVersion).apply();

            com.airbnb.lottie.LottieAnimationView confettiView = findViewById(R.id.mobile_confetti_view);
            if (confettiView == null) {
                return;
            }

            confettiView.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                confettiView.setVisibility(View.VISIBLE);
                confettiView.setRepeatCount(0);
                confettiView.addAnimatorListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        confettiView.setVisibility(View.GONE);
                        confettiView.cancelAnimation();
                        confettiView.setImageDrawable(null);
                    }

                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        confettiView.setVisibility(View.GONE);
                        confettiView.cancelAnimation();
                        confettiView.setImageDrawable(null);
                    }
                });
                confettiView.playAnimation();
            }, 350);
        }
    }

    /** CoordinatorLayout + AppBarLayout handles top positioning automatically. */
    private void refreshTopPadding() {
        padUnderTopBar(0);
    }

    /** A new page: the nav pill back if a scroll had slid it away. */
    private void showNav() {
        if (mNavHider != null) {
            mNavHider.show();
        }
        if (mAppBar != null) {
            mAppBar.setExpanded(true, true);
        }
    }

    private void updateChipRow() {
        if (mChipRow == null || mStrip == null) {
            return;
        }
        boolean active = mStrip.mode() == FeedStrip.MODE_CHIPS && !mYouShowing;
        boolean segment = com.newtube.mobile.ui.common.Glass.chipStyle(this) == com.newtube.mobile.ui.common.Glass.CHIPS_SEGMENT;
        mChipRow.setVisibility(active && !segment ? View.VISIBLE : View.GONE);
        mChipTrack.setVisibility(active && segment ? View.VISIBLE : View.GONE);
        if (mAppBar != null) {
            mAppBar.setExpanded(true, true);
        }
    }

    private void padUnderTopBar(int barPx) {
        for (View page : new View[] {mContentGrid, mFeedSkeleton, mErrorContainer}) {
            if (page != null) {
                page.setPadding(page.getPaddingLeft(), 0, page.getPaddingRight(), page.getPaddingBottom());
            }
        }
        int spinner = Math.round(40 * getResources().getDisplayMetrics().density);
        if (mContentSwipe != null) {
            mContentSwipe.setProgressViewOffset(false, 0, spinner);
        }
    }

    private void setupSwipeRefresh() {
        mContentSwipe.setOnRefreshListener(this::refreshFeed);
    }

    /** A pull to refresh, or (issue #130) a tap on the tab you are on with its feed at the top. */
    private void refreshFeed() {
        com.newtube.mobile.ui.common.RefreshFeedback.play(mContentSwipe);
        if (isHomeChip(mCurrentSectionId) && mHomeChip == null) {
            rememberSeenHome();
        }
        if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
            mSubsChannels = null; // new subscriptions show up in the strip too
            updateStrip();
        }
        if (isHomeChip(mCurrentSectionId) && mHomeChip == null) {
            // GUTUBE(issue #79): the chips follow the watch history - asked again, the ones shown
            // staying until it answers (dropped and rebuilt, the strip jumped twice per refresh)
            loadHomeChips();
        }
        if (mHomeChip != null) {
            loadHomeChip(mHomeChip);
            return;
        }
        if (mSubsChannel != null) {
            loadSubsChannel(mSubsChannel);
            return;
        }
        if (mPresenter != null) {
            mPresenter.refresh(false);
        } else {
            mContentSwipe.setRefreshing(false);
        }
    }

    /** GUTUBE(refresh): every Home card down to the last one on screen counts as seen. */
    private void rememberSeenHome() {
        java.util.Set<Video> seen = seenOnRefresh();
        if (seen.size() > 300) {
            seen.clear(); // ponytail: most of Home's first pages seen, start over rather than run dry
        }
        List<Video> shown = mAdapter.getCurrentList();
        int last = Math.min(lastVisibleCard(), shown.size() - 1);
        for (int i = 0; i <= last; i++) {
            seen.add(shown.get(i));
        }
    }

    private int getColorInt(int colorRes) {
        return getResources().getColor(colorRes);
    }

    /**
     * Loading skeleton: card ghosts under a sweeping shimmer (ShimmerLinearLayout) instead of a
     * naked spinner. Only ever shown over an EMPTY grid - a section repainted from
     * {@link FeedCache} keeps its content visible while refreshing. NEWTUBE(motion): it leaves by
     * fading out over the cards that replace it (a cut used to swap one for the other), and at
     * once when nothing replaces it (an error, an empty section).
     */
    private void setSkeletonVisible(boolean visible) {
        if (visible) {
            mSkeletonShows++;
            mFeedSkeleton.removeCallbacks(mSkeletonLeaveDeadline);
            mFeedSkeleton.animate().cancel();
            // The Shorts tab shows a spinner, never the skeleton (showShortsTab).
            mFeedSkeleton.setAlpha(mCurrentSectionId == MediaGroup.TYPE_SHORTS ? 0f : 1f);
            mFeedSkeleton.setVisibility(View.VISIBLE);
            return;
        }
        if (mGridHiddenForSkeleton) {
            mGridHiddenForSkeleton = false;
            mContentGrid.animate().cancel();
            mContentGrid.setAlpha(1f); // under the skeleton, which fades away over it
        }
        if (mFeedSkeleton.getVisibility() != View.VISIBLE) {
            return;
        }
        if (mCurrentVideos.isEmpty()) {
            mFeedSkeleton.animate().cancel();
            mFeedSkeleton.setVisibility(View.GONE);
            mFeedSkeleton.setAlpha(1f);
            return;
        }
        // Issue #20: each page of a loading feed says "hide" again (3-8 times a page), and each call
        // used to cancel the fade and start it over, so on a slow phone the skeleton stayed over
        // the cards for as long as pages kept coming. One fade per show; only a new show stops it.
        if (mSkeletonLeaving == mSkeletonShows) {
            return;
        }
        mSkeletonLeaving = mSkeletonShows;
        mFeedSkeleton.animate().cancel();
        mFeedSkeleton.removeCallbacks(mSkeletonLeaveDeadline);
        mFeedSkeleton.postDelayed(mSkeletonLeaveDeadline, SKELETON_LEAVE_MAX_MS);
        int shows = mSkeletonShows;
        SkeletonReveal.fadeOverCards(mFeedSkeleton, mContentGrid, () -> shows == mSkeletonShows, () -> {
            mFeedSkeleton.setVisibility(View.GONE);
            mFeedSkeleton.setAlpha(1f);
        });
    }

    /** Counts skeleton shows, so a reveal waiting for its cards yields to a newer load. */
    private int mSkeletonShows;
    /** The show whose fade-out is under way (setSkeletonVisible(false) starts one per show). */
    private int mSkeletonLeaving = -1;
    /**
     * Issue #56: a leave waits up to 400 ms for the cards, then fades; this is well past both.
     * On the reporter's phone (Galaxy A03 Core, Android 13 Go) the skeleton still sat over the
     * loaded cards, and every later "hide" took the leave for under way and did nothing - so
     * however that fade was lost, a leave now ends here at the latest, cut instead of faded.
     */
    private static final long SKELETON_LEAVE_MAX_MS = 1_500;
    private final Runnable mSkeletonLeaveDeadline = () -> {
        if (mSkeletonLeaving != mSkeletonShows || mFeedSkeleton.getVisibility() != View.VISIBLE) {
            return; // gone already, or a newer load put it back (its own leave ends it)
        }
        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("skeleton leave-deadline alpha="
                + mFeedSkeleton.getAlpha() + " cards=" + mContentGrid.getChildCount());
        mFeedSkeleton.animate().cancel();
        mFeedSkeleton.setVisibility(View.GONE);
        mFeedSkeleton.setAlpha(1f);
    };

    // ---------------------------------------------------------------------------------
    // In-app mini-player bar
    // ---------------------------------------------------------------------------------

    private void setupMiniPlayerBar() {
        // Tap the video (anywhere but the overlay buttons) = expand back to the watch screen.
        // The card keeps rendering until our onPause detaches it (hideMiniPlayer there) - the
        // player's onResume then re-parents the session texture back. Detaching HERE would blank
        // the card for the whole activity-switch latency.
        View.OnClickListener expand = v -> {
            if (!mMiniClosing && MiniPlayerBridge.isActive()) { // not while a closed card leaves
                MiniPlayerBridge.expand(this);
            }
        };
        mMiniPlayerBar.setOnClickListener(expand);
        mMiniPlayerFrame.setOnClickListener(expand);

        mMiniPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updateMiniPlayPauseIcon(player);
            }
            mMiniControls.show(); // a few seconds more
        });

        findViewById(R.id.mobile_mini_close).setOnClickListener(v -> closeMiniPlayer());
        com.newtube.mobile.ui.playback.MiniPip.wireAudioButton(findViewById(R.id.mobile_mini_audio));
        // NEWTUBE(motion): or swipe the card away sideways.
        mMiniSwipe = com.newtube.mobile.ui.playback.MiniCardSwipe.attach(mMiniPlayerBar,
                new com.newtube.mobile.ui.playback.MiniCardSwipe.Callback() {
                    @Override
                    public boolean canSwipe() {
                        return !mMiniClosing && MiniPlayerBridge.isActive();
                    }

                    @Override
                    public void onSwipedAway(float toTranslationX, long durationMs) {
                        closeMiniPlayer(toTranslationX, durationMs);
                    }

                    @Override
                    public void onTouched() {
                        mMiniControls.show();
                    }
                }, mMiniPlayerFrame, mMiniPlayerBar);
    }

    /** GUTUBE(launch): the heart in the brand pill beats once as Home first shows, like the splash's. */
    private void beatBrandMark() {
        View mark = findViewById(R.id.mobile_brand_mark);
        if (mark == null) {
            return;
        }
        android.animation.Keyframe[] frames = {
                android.animation.Keyframe.ofFloat(0f, 1f),
                android.animation.Keyframe.ofFloat(0.18f, 1.2f),
                android.animation.Keyframe.ofFloat(0.38f, 1f),
                android.animation.Keyframe.ofFloat(0.54f, 1.1f),
                android.animation.Keyframe.ofFloat(1f, 1f)};
        android.animation.ObjectAnimator beat = android.animation.ObjectAnimator.ofPropertyValuesHolder(mark,
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_X, frames),
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_Y, frames));
        beat.setDuration(700);
        beat.setStartDelay(250);
        beat.start();
    }

    /**
     * NEWTUBE(motion): X - the sound stops at once and the card, frozen on its last frame, shrinks
     * and fades away instead of vanishing in one frame. The session itself closes when the card is
     * gone: finishing the hidden player blocks this main thread for ~60 ms (Pixel 9), which ate
     * the whole animation when it ran first.
     */
    private boolean mMiniClosing;
    private float mMiniVolumeBeforeClose = 1f;
    @androidx.annotation.Nullable
    private com.newtube.mobile.ui.playback.MiniCardSwipe mMiniSwipe;
    /** GUTUBE(mini-pip): Home with the card playing floats it in a PiP window. */
    private com.newtube.mobile.ui.playback.MiniPip mMiniPip;

    private void closeMiniPlayer() {
        closeMiniPlayer(null, 0);
    }

    /** {@code flyToX}: swiped away - the card keeps going off that side instead of shrinking. */
    private void closeMiniPlayer(@androidx.annotation.Nullable Float flyToX, long flyMs) {
        if (mMiniClosing) {
            return;
        }
        mMiniClosing = true;
        if (MiniPlayerBridge.isActive()) {
            mMiniVolumeBeforeClose = MiniPlayerBridge.getVolume();
            MiniPlayerBridge.setVolume(0f); // released with the session right after
        }
        MiniPlayerBridge.setClosing(this::abortMiniClose);
        if (mMiniPip != null) {
            mMiniPip.update(); // GUTUBE(issue #233): disarm before detaching and fading the playing card
        }
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        detachMiniTexture();
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel();
        android.view.ViewPropertyAnimator exit = flyToX != null
                ? mMiniPlayerBar.animate().translationX(flyToX).alpha(0f).setDuration(flyMs)
                        .setInterpolator(new android.view.animation.LinearInterpolator())
                : mMiniPlayerBar.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                        .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        exit.withLayer().withEndAction(() -> {
                    if (mMiniClosing) {
                        finishMiniClose();
                    } else {
                        foldMiniCard(); // aborted: the player took the session back; only the card goes
                    }
                }).start();
    }

    /** The end of closeMiniPlayer; also run at once by anything that would re-show the card. */
    private void finishMiniClose() {
        if (!mMiniClosing) {
            return;
        }
        mMiniClosing = false;
        MiniPlayerBridge.setClosing(null);
        foldMiniCard();
        MiniPlayerBridge.close();
    }

    /** The session was handed a new video while the card left: it lives on; the card still goes. */
    private void abortMiniClose() {
        if (!mMiniClosing) {
            return;
        }
        mMiniClosing = false;
        MiniPlayerBridge.setVolume(mMiniVolumeBeforeClose);
    }

    /** Show the card and adopt the live session texture if a mini session is active. */
    private void syncMiniPlayer() {
        finishMiniClose(); // a card closing when this runs is closed, not brought back
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hideMiniPlayer();
            return;
        }

        // Keep the exact endpoint frame visible while the newly-created TextureView adopts the
        // session SurfaceTexture. Without this, releasing a fully-minimized drag briefly reveals
        // the card's black background/next decoder frame and reads as a small refresh.
        Bitmap entryStill = MiniPlayerBridge.takeMiniEntryStill();
        if (entryStill != null) {
            mMiniFreeze.setImageBitmap(entryStill);
            mMiniFreeze.setVisibility(View.VISIBLE);
        }

        MiniPlayerBridge.fitToVideo(mMiniPlayerFrame); // NEWTUBE(issue #9): letterbox, never stretch
        attachMiniTexture();
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel(); // a closing card (closeMiniPlayer) comes back whole
        mMiniPlayerBar.setAlpha(1f);
        mMiniPlayerBar.setScaleX(1f);
        mMiniPlayerBar.setScaleY(1f);
        mMiniPlayerBar.setTranslationX(0f);
        // GUTUBE(mini-float): back home from the corner it was dragged to (issue #125: lower while the nav is away)
        mMiniPlayerBar.setTranslationY(mMiniSwipe != null ? mMiniSwipe.homeY() : 0f);
        if (mMiniPlace != null && mMiniSwipe != null && mMiniPlaceSession == MiniPlayerBridge.session()) {
            mMiniSwipe.restorePlace(mMiniPlace); // same card, back from the app being away
        }
        mMiniPlace = null;
        com.newtube.mobile.ui.playback.MiniSize.apply(this); // the type may have changed while Home stayed alive
        mMiniPlayerBar.setVisibility(View.VISIBLE);
        mMiniControls.show();
        updateMiniPlayPauseIcon(player);

        Utils.removeCallbacks(mMiniPlayerTick);
        Utils.postDelayed(mMiniPlayerTick, MINI_TICK_MS);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return BrowseView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // GUTUBE(glass): the card sits 10dp above the floating nav pill, which floats 12dp up; the
        // morph adds its own 12dp margin, so: pill + 12 + 10 - 12.
        float density = getResources().getDisplayMetrics().density;
        View pill = findViewById(R.id.mobile_nav_glass);
        int pillPx = pill != null && pill.getHeight() > 0 ? pill.getHeight() : Math.round(68 * density);
        // GUTUBE(issue #125): lower by the card's home offset while a scroll has the nav away.
        float homeY = mMiniSwipe != null ? mMiniSwipe.homeY() : 0f;
        // GUTUBE(youtube mode): a docked bar has no 12dp under it to take back.
        int docked = com.newtube.mobile.ui.common.YouTubeMode.isOn(this) ? Math.round(12 * density) : 0;
        return Math.max(0, pillPx - docked + Math.round(10 * density - homeY));
    }

    /**
     * GUTUBE(youtube mode): YouTube's top bar styling.
     */
    private void setUpYouTubeTopBar() {
        View brand = findViewById(R.id.mobile_brand_glass);
        View actions = findViewById(R.id.mobile_actions_glass);
        if (brand instanceof com.newtube.mobile.ui.common.GlassView) {
            ((com.newtube.mobile.ui.common.GlassView) brand).setPlain();
        }
        if (actions instanceof com.newtube.mobile.ui.common.GlassView) {
            ((com.newtube.mobile.ui.common.GlassView) actions).setPlain();
        }
        if (brand != null) {
            brand.setPaddingRelative(0, brand.getPaddingTop(), brand.getPaddingEnd(), brand.getPaddingBottom());
        }
        float density = getResources().getDisplayMetrics().density;
        View mark = findViewById(R.id.mobile_brand_mark);
        if (mark != null && mark.getLayoutParams() != null) {
            mark.getLayoutParams().width = Math.round(28 * density);
            mark.getLayoutParams().height = Math.round(28 * density);
        }
        TextView name = findViewById(R.id.mobile_title_bar);
        if (name != null) {
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
            name.setLetterSpacing(-0.02f);
        }
    }

    /** GUTUBE(youtube mode): the docked bar runs under the gesture area and pads itself. */
    @Override
    protected boolean shouldInsetContentForNavigationBar() {
        return !com.newtube.mobile.ui.common.YouTubeMode.isOn(this);
    }

    /**
     * The translucent playback Activity is still on top when this runs. Make the endpoint card
     * visible now so Browse can draw it underneath before Android reorders Browse to the front.
     */
    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        if (isFinishing() || isDestroyed()) {
            return false;
        }
        syncMiniPlayer();
        if (mMiniPlayerBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        // NEWTUBE(motion): the landing video is a bare picture; the card's buttons fade in once
        // this screen is in front (onResume) rather than appearing with the card in one frame.
        setMiniButtonsAlpha(0f);
        mMiniButtonsFadePending = true;

        // A pair of frame callbacks only proves that time passed; it does not prove this paused
        // window submitted a buffer. Gate the reorder on an actual draw containing the card, then
        // wait one compositor frame before removing the playback window above it. The fallback
        // avoids stranding the player at the endpoint if an OEM suppresses draws on paused windows.
        ViewTreeObserver observer = mMiniPlayerBar.getViewTreeObserver();
        class DrawGate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDelivered;

            @Override
            public void onDraw() {
                mMiniPlayerBar.post(this);
            }

            @Override
            public void run() {
                if (mDelivered) {
                    return;
                }
                mDelivered = true;
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(this);
                }
                mMiniPlayerBar.postOnAnimation(onDrawn);
            }
        }
        DrawGate gate = new DrawGate();
        observer.addOnDrawListener(gate);
        mMiniPlayerBar.postDelayed(gate, 100);
        mMiniPlayerBar.invalidate();
        return true;
    }

    /**
     * Put a TextureView in the card and swap the player's session-long SurfaceTexture into it
     * (see MobilePlaybackActivity's persistent-surface docs). The playback activity detached its
     * own TextureView before launching us, so the texture has no other GL consumer by now. The
     * codec keeps decoding into it throughout - the card shows the LIVE stream with no surface
     * change on the player, hence no codec re-init and no playback freeze.
     */
    private void attachMiniTexture() {
        if (mMiniTexture == null) {
            // A FRESH TextureView per attach: a re-used one retains its previous SurfaceTexture
            // (the destroyed callback below returns false), which would silently be a stale,
            // already-released texture if a new playback session started since. hideMiniPlayer
            // nulls the field, so every show adopts the CURRENT session texture cleanly.
            final TextureView textureView = new TextureView(this);
            textureView.setOpaque(false); // NEWTUBE(texture-opaque): see MobilePlaybackActivity
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                /** The session texture this view adopted: the player's, never released here. */
                private SurfaceTexture mAdopted;

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                "mini tex available t=" + android.os.SystemClock.uptimeMillis()
                                        + " session=" + (session != null)
                                        + " cb=" + width + "x" + height
                                        + " view=" + textureView.getWidth() + "x" + textureView.getHeight());
                    }
                    // GUTUBE(crash 2.9.8): the session's texture can be released between the bridge
                    // handing it out and this callback (the player closed meanwhile); adopting it then
                    // throws "Cannot setSurfaceTexture to a released SurfaceTexture".
                    if (session != null && texture != session
                            && !(Build.VERSION.SDK_INT >= 26 && session.isReleased())) {
                        try {
                            textureView.setSurfaceTexture(session);
                            mAdopted = session;
                            texture.release(); // the view-created texture is never used
                        } catch (IllegalArgumentException released) {
                            // released after the check: keep the view's own texture
                        }
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                "mini tex sizeChanged t=" + android.os.SystemClock.uptimeMillis()
                                        + " cb=" + width + "x" + height
                                        + " view=" + textureView.getWidth() + "x" + textureView.getHeight());
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    // Never release the session texture - the playback activity owns it. Only a
                    // view-created texture that was never swapped out may die here. GUTUBE(issue
                    // #38): checked against the one adopted, not the bridge's - a card floated in
                    // PiP is removed after the player took the session back, when the bridge
                    // hands out nothing, and released the player's live texture (crash on expand).
                    return texture != mAdopted && texture != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    // First live frame in the card: lift the freeze frame.
                    if (mMiniFreeze != null && mMiniFreeze.getVisibility() == View.VISIBLE) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(com.liskovsoft.smartyoutubetv2.common.misc.NetPath.TAG,
                                    "mini tex first update t=" + android.os.SystemClock.uptimeMillis());
                        }
                        mMiniFreeze.setVisibility(View.GONE);
                        mMiniFreeze.setImageDrawable(null);
                    }
                }
            });
            mMiniTexture = textureView;
        }
        if (mMiniTexture.getParent() == null) {
            mMiniPlayerFrame.addView(mMiniTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * Freeze the current frame, detach the card's TextureView (freeing the session texture for
     * the expanding player - see the listener above) and fold the bar + ticker. Safe to call
     * repeatedly / when never shown.
     */
    private void hideMiniPlayer() {
        finishMiniClose();
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        detachMiniTexture();
        foldMiniCard();
    }

    /** Freeze the card's last frame and give the session texture back (the card stays as it is). */
    private void detachMiniTexture() {
        Utils.removeCallbacks(mMiniPlayerTick);
        if (mMiniTexture != null && mMiniTexture.getParent() != null) {
            if (mMiniTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap still = mMiniTexture.getBitmap();
                if (still != null) {
                    // Cover the card while detached AND hand the frame to the expanding player,
                    // which shows it over its own video box until the texture paints there.
                    mMiniFreeze.setImageBitmap(still);
                    mMiniFreeze.setVisibility(View.VISIBLE);
                    MiniPlayerBridge.setHandoffStill(still);
                }
            }
            // No throwaway-texture swap here: TextureView#setSurfaceTexture releases the
            // texture the view currently holds, which would destroy the session texture
            // (see MobilePlaybackActivity#detachVideoTexture).
            mMiniPlayerFrame.removeView(mMiniTexture);
            mMiniTexture = null; // next show builds a fresh view (see attachMiniTexture)
        }
    }

    /** NEWTUBE(haptics): see MiniCardSwipe#stop - before this host moves the card itself. */
    private void stopMiniSwipe() {
        if (mMiniSwipe != null) {
            mMiniSwipe.stop();
        }
    }

    private void foldMiniCard() {
        if (mMiniPlayerBar == null) {
            return;
        }
        stopMiniSwipe();
        mMiniPlayerBar.animate().cancel();
        mMiniPlayerBar.setVisibility(View.GONE);
        unwatchMiniScreenOn();
        setMiniKeepScreenOn(false); // no card, no video on this screen
        mMiniPlayerBar.setAlpha(1f);
        mMiniPlayerBar.setScaleX(1f);
        mMiniPlayerBar.setScaleY(1f);
        mMiniPlayerBar.setTranslationX(0f);
        mMiniPlayerBar.setTranslationY(0f); // GUTUBE(mini-float): back home from the corner it was dragged to
        mMiniButtonsFadePending = false;
        setMiniButtonsAlpha(1f);
        mMiniControls.reset();
        if (mMiniPip != null) {
            mMiniPip.update(); // no card, nothing to float
        }
    }

    private void setMiniButtonsAlpha(float alpha) {
        View close = findViewById(R.id.mobile_mini_close);
        for (View view : new View[] {mMiniPlayPause, close, mMiniProgress}) {
            if (view != null) {
                view.animate().cancel();
                view.setAlpha(alpha);
            }
        }
    }

    private void fadeInMiniButtons() {
        View close = findViewById(R.id.mobile_mini_close);
        for (View view : new View[] {mMiniPlayPause, close, mMiniProgress}) {
            if (view != null) {
                view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS)
                        .setInterpolator(Motion.STANDARD).start();
            }
        }
    }

    private void onMiniPlayerTick() {
        com.newtube.mobile.ui.playback.MiniSize.bindText(this);
        if (mMiniPlayerBar.getVisibility() != View.VISIBLE) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            // The hidden playback activity died (system kill / finished elsewhere): fold the bar.
            boolean pinned = mMiniPip != null && mMiniPip.isPinned();
            hideMiniPlayer();
            if (pinned) {
                // GUTUBE(issue #38): or it took the video back while this copy of Home floated
                // (the app reopened from the launcher): the window goes, as when it is closed.
                // Left up, it showed Home's bars in PiP and a second Home opened under it.
                finishAndRemoveTask();
            }
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            mMiniProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }
        updateMiniPlayPauseIcon(player);
        MiniPlayerBridge.fitToVideo(mMiniPlayerFrame); // an autoplayed next video may have another shape
        Utils.postDelayed(mMiniPlayerTick, MINI_TICK_MS);
    }

    private void updateMiniPlayPauseIcon(ExoPlayer player) {
        com.newtube.mobile.ui.playback.MiniPip.syncAudioButton(findViewById(R.id.mobile_mini_audio));
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mMiniPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        // SCREEN-ON (#50): the docked card is a visible video; this window holds the screen while it
        // plays (the stopped player Activity no longer does) and lets go when it pauses.
        watchMiniScreenOn(player);
        setMiniKeepScreenOn(isMiniActivelyPlaying(player));
        if (mMiniPip != null) {
            mMiniPip.update();
        }
    }

    private static boolean isMiniActivelyPlaying(Player player) {
        return player.getPlayWhenReady()
                && (player.getPlaybackState() == Player.STATE_READY
                    || player.getPlaybackState() == Player.STATE_BUFFERING);
    }

    /** Listen while the card is up (see mMiniScreenOnListener); foldMiniCard() stops it. */
    private void watchMiniScreenOn(Player player) {
        if (mMiniScreenOnPlayer == player) {
            return;
        }
        unwatchMiniScreenOn();
        player.addListener(mMiniScreenOnListener);
        mMiniScreenOnPlayer = player;
    }

    private void unwatchMiniScreenOn() {
        if (mMiniScreenOnPlayer != null) {
            mMiniScreenOnPlayer.removeListener(mMiniScreenOnListener);
            mMiniScreenOnPlayer = null;
        }
    }

    private void setMiniKeepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void setupContentGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked) {
            @Override
            public void onCurrentListChanged(@NonNull List<Video> previous, @NonNull List<Video> current) {
                super.onCurrentListChanged(previous, current);
                if (mRowsShown) {
                    mRows.submit(current);
                }
            }
        };
        mAdapter.showAvatars(); // GUTUBE(card avatars, issue #6)
        mStrip = new FeedStrip(this::onStripPicked, findViewById(R.id.mobile_blur_target));
        mStrip.setOnAllLongPressed(() -> {
            if (mSubsChannels != null && !mSubsChannels.isEmpty()) {
                SubsChannelsSheet.show(this, mSubsChannels);
            }
        });
        mStrip.setOnAvatarLongPressed(this::onStripLongPressed); // GUTUBE(issue #87): the channel's menu.
        mChipRow = findViewById(R.id.mobile_chip_row);
        mChipRow.setClipChildren(false);
        mChipRow.setClipToPadding(false);
        mChipRow.setElevation(8 * getResources().getDisplayMetrics().density);
        mChipRow.setTranslationZ(8 * getResources().getDisplayMetrics().density);
        android.view.ViewParent chipParent = mChipRow.getParent();
        while (chipParent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) chipParent).setClipChildren(false);
            ((android.view.ViewGroup) chipParent).setClipToPadding(false);
            chipParent = chipParent.getParent();
        }
        mChipRow.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        mChipRow.setItemAnimator(null);
        mChipRow.setAdapter(mStrip.items());
        mChipTrack = findViewById(R.id.mobile_chip_track);
        mChipTrack.setGlassSource(findViewById(R.id.mobile_blur_target));
        mStrip.setTrack(mChipTrack);
        mAppBar = findViewById(R.id.mobile_appbar);
        if (mAppBar != null) {
            mAppBar.addOnOffsetChangedListener((appBar, offset) -> {
                if (mContentSwipe != null) {
                    mContentSwipe.setEnabled(offset == 0);
                }
            });
        }
        mRows = new HomeRowsAdapter(this::onVideoClicked, this::onVideoLongClicked);

        // Channel rows (rare on Home, possible in some sections) span the whole grid width
        // when landscape/tablet layouts use 2+ columns; so does the strip above the cards.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int card = position - stripRows();
                return mRowsShown || card < 0 || mAdapter.isFullSpan(card) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mContentGrid.setLayoutManager(mLayoutManager);
        mSegment = new ShortsSegment(filter -> {
            submitGrid();
            mContentGrid.scrollToPosition(0);
        });
        mFeedAdapter = new androidx.recyclerview.widget.ConcatAdapter(mStrip, mSegment, mAdapter);
        mRowsFeedAdapter = new androidx.recyclerview.widget.ConcatAdapter(mStrip, mRows);
        mContentGrid.setAdapter(mFeedAdapter);
        // SCROLL-JANK FIX: the grid's own bounds never depend on item content (cards size themselves
        // to the fixed column width), so skip the full requestLayout on every adapter change; and keep
        // more offscreen holders around (default 2) so a fling-back rebinds/redecodes far fewer cards.
        //noinspection InvalidSetHasFixedSize - match_parent in the layout; lint mixes it up with the chip row
        mContentGrid.setHasFixedSize(true);
        mContentGrid.setItemViewCacheSize(8);
        // GUTUBE(#71): on History a card swiped sideways leaves the watch history, as on a playlist page.
        new androidx.recyclerview.widget.ItemTouchHelper(new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0, androidx.recyclerview.widget.ItemTouchHelper.START | androidx.recyclerview.widget.ItemTouchHelper.END) {
            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
                return mCurrentSectionId == MediaGroup.TYPE_HISTORY && holder.getBindingAdapter() == mAdapter
                        ? super.getSwipeDirs(recyclerView, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                    @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                Video video = position >= 0 && position < mAdapter.getItemCount() ? mAdapter.getCurrentList().get(position) : null;
                if (video == null || mPresenter == null) {
                    mAdapter.notifyItemChanged(position); // slide it back
                    return;
                }
                com.newtube.mobile.ui.common.Haptics.click(mContentGrid);
                mPresenter.removeItem(video);
                com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.instance(MobileBrowseActivity.this).removeFromHistory(video);
            }
        }).attachToRecyclerView(mContentGrid);
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mContentGrid, position -> {
            int card = mRowsShown ? -1 : position - stripRows();
            return card >= 0 && card < mAdapter.getItemCount() ? mAdapter.getCurrentList().get(card) : null;
        }, true);
        mContentGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy < 0) {
                    // NEWTUBE(shelf-tail): scrolling back up re-arms the near-end report, so a
                    // reader at a stalled end (a failed or refused page, nothing new to scroll to)
                    // gets another try by scrolling down again - one per gesture, never a loop.
                    mLastNearEndTriggerCount = -1;
                }
                maybeTriggerPagination();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            }
        });
        // GUTUBE(home autoplay): the centred Home card plays muted, as on YouTube.
        mPreview = new HomePreview(mContentGrid, () -> isHomeChip(mCurrentSectionId) && !mYouShowing
                && !mRowsShown && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && (mMiniPlayerBar == null || mMiniPlayerBar.getVisibility() != View.VISIBLE));
        mAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mPreview.onDataChanged();
            }

            @Override
            public void onItemRangeChanged(int start, int count) {
                mPreview.onDataChanged();
            }

            @Override
            public void onItemRangeInserted(int start, int count) {
                mPreview.onDataChanged();
            }

            @Override
            public void onItemRangeRemoved(int start, int count) {
                mPreview.onDataChanged();
            }
        });
        if (!com.newtube.mobile.ui.common.FocusRing.isTv(this)) { // a remote needs the tabs in reach
            mNavHider = new com.newtube.mobile.ui.common.NavHider(findViewById(R.id.mobile_nav_glass));
            mNavHider.setOnSlide(navY -> { // GUTUBE(issue #125): the mini card follows the nav bar down
                mNavY = navY;
                followNav();
            });
            mNavHider.setMorph(mSearchMorph); // GUTUBE(search fab)
            mContentGrid.addOnScrollListener(mNavHider);
        }
        if (com.newtube.mobile.ui.common.FocusRing.isTv(this)) {
            // GUTUBE(tv): the remote's way to the chips is up from the first row, so there the feed
            // goes back to its top and the chips come out (a touch gets them by scrolling up).
            mContentGrid.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                View item = newFocus != null ? mContentGrid.findContainingItemView(newFocus) : null;
                int position = item != null ? mContentGrid.getChildAdapterPosition(item) : RecyclerView.NO_POSITION;
                if (position != RecyclerView.NO_POSITION && mContentGrid.canScrollVertically(-1)
                        && mLayoutManager.getSpanSizeLookup().getSpanGroupIndex(position,
                                mLayoutManager.getSpanCount()) <= stripRows()) {
                    mContentGrid.smoothScrollToPosition(0);
                }
            });
        }
    }

    /**
     * Search entry point (Wave 4b). Mirrors the gear button's "drive the presenter" approach
     * (the TV Home does the exact same thing - {@code BrowseFragment} wires its search affordance
     * to {@code SearchPresenter.instance(ctx).startSearch(null)}): the presenter calls
     * {@code ViewManager.startView(SearchView.class)} - now mapped to {@code MobileSearchActivity}
     * (see {@link com.newtube.mobile.MobileMainApplication}) - then drives the freshly-created
     * touch Search view. Passing {@code null} opens an empty search field (no pre-filled query).
     */
    private void setupSearchButton() {
        if (mSearchButton != null) {
            mSearchButton.setOnClickListener(v -> SearchPresenter.instance(this).startTextSearch());
        }
    }

    // ---------------------------------------------------------------------------------
    // Casting: top-bar entry point (same flow as the player's icon) + connected-state tint.
    //
    // Connecting from here with NO video playing is fine: the session comes up (foreground
    // service + notification), nothing plays yet, and the FIRST video opened afterwards is
    // claimed by MobilePlaybackActivity's setVideo hook (maybeRouteVideoToCast) - it loads on
    // the TV while the local player stays paused underneath.
    // ---------------------------------------------------------------------------------

    private void setupCastButton() {
        // CastPickerLauncher owns the Android 16+ ACCESS_LOCAL_NETWORK gate (shared with the
        // player); Browse has no immersive window, so the plain presenter is enough.
        mCastButton.setOnClickListener(v -> CastPickerLauncher.open(this, CastPickerLauncher::presentPlain));

        mCastSessionManager = CastSessionManager.instance(this);
        mCastSessionManager.addListener(mCastListener);
        updateCastIconTint();
    }

    /** Mirrors the player's registration pattern: listen for the activity's whole lifetime. */
    private final CastSessionManager.Listener mCastListener = new CastSessionManager.Listener() {
        @Override
        public void onCastSessionStarted(CastTarget target) {
            updateCastIconTint();
        }

        @Override
        public void onCastSessionState(String videoId, long positionMs, long durationMs, boolean playing) {
            // Browse has no transport UI; only the connected/idle tint matters here.
        }

        @Override
        public void onCastSessionEnded(String reason) {
            updateCastIconTint();
        }

        @Override
        public void onCastConnectingChanged(boolean connecting) {
            updateCastIconTint();
        }
    };

    /** Guards against restarting the connecting animation on every listener event. */
    private boolean mCastIconAnimating;

    /**
     * Official-app visual states for the cast icon: while a session is being established (picker
     * tap -> connected takes seconds; the auto-fallback's mdx resolve up to 15s) the glyph's wifi
     * arcs pulse (animation-list swaps the icon); accent tint once connected; stock white idle.
     */
    private void updateCastIconTint() {
        if (mCastButton == null) {
            return;
        }
        boolean connecting = mCastSessionManager != null && mCastSessionManager.isConnecting();
        if (connecting != mCastIconAnimating) {
            mCastIconAnimating = connecting;
            mCastButton.setImageResource(connecting
                    ? R.drawable.ic_mobile_cast_connecting : R.drawable.ic_mobile_cast);
            Drawable drawable = mCastButton.getDrawable();
            if (connecting && drawable instanceof AnimationDrawable) {
                ((AnimationDrawable) drawable).start();
            }
        }
        if (mCastSessionManager != null && mCastSessionManager.isConnected()) {
            mCastButton.setColorFilter(getColorInt(R.color.mobile_color_cast_active));
        } else {
            mCastButton.clearColorFilter();
        }
    }

    /** Hardware volume keys drive the TV while casting; everything else falls through. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (CastVolumeKeys.onDispatchKeyEvent(this, event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Cast picker's local-network gate (the POST_NOTIFICATIONS request needs no follow-up).
        CastPickerLauncher.handlePermissionResult(this, requestCode, CastPickerLauncher::presentPlain);
    }

    /**
     * Settings entry point: the phone Settings screen (issue #2). Until 1.14 this rendered
     * AppDataSourceManager's TV categories through the AppDialog renderer; the tree now lives in
     * {@link com.newtube.mobile.ui.settings.SettingsPages}.
     */
    private void openSettings() {
        startActivity(com.newtube.mobile.ui.settings.MobileSettingsActivity.intent(this, null));
    }

    private void setupBottomNav() {
        com.newtube.mobile.ui.common.NavSpringPhysics.attach(mBottomNav);
        mBottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == SEARCH_ITEM_ID) {
                if (!mSuppressNavCallback) {
                    com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, 40, 255);
                    SearchPresenter.instance(this).startTextSearch();
                }
                return false; // a button, not a place: the tab you were on stays lit
            }
            if (item.getItemId() == CAST_ITEM_ID) {
                if (!mSuppressNavCallback) {
                    com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, 40, 255);
                    CastPickerLauncher.open(this, CastPickerLauncher::presentPlain);
                }
                return false; // a button, not a place: the tab you were on stays lit
            }
            if (!mSuppressNavCallback) {
                com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, Float.isNaN(mNavDragFromX) ? 40 : 30, 255); // GUTUBE(nav haptics): tap, or let go
                int itemId = item.getItemId();
                if (itemId == toMenuItemId(MediaGroup.TYPE_SHORTS) && Float.isNaN(mNavDragFromX)) {
                    // GUTUBE(shorts, issue #107): like Search, Shorts opens from the tap - no lens or
                    // wiggle to wait out (it held the switch 700 ms); the bar slides away instead.
                    onNavItemChosen(itemId);
                } else if (playNavLens(itemId)) {
                    // The page swaps a frame later: its heaviest frames land while the lens swells in place.
                    mBottomNav.postOnAnimation(() -> onNavItemChosen(itemId));
                } else {
                    View glass = findViewById(R.id.mobile_nav_glass);
                    if (glass != null && glass.getScaleX() == 1f // not while a drag's pop settles (#90)
                            && !com.newtube.mobile.ui.common.YouTubeMode.isOn(this)) { // a docked bar holds still
                        Motion.nudge(glass); // GUTUBE(motion): the bar acknowledges the switch
                    }
                    onNavItemChosen(itemId);
                }
            }
            return true;
        });
        // NEWTUBE(motion): tapping the tab you are on glides a scrolled feed back to the top, like
        // YouTube; at the top it refreshes as a pull does, the spinner showing (issue #130, as X
        // does). You, and a page with no pull to refresh, repaint as they always did.
        mBottomNav.setOnItemReselectedListener(item -> {
            if (mSuppressNavCallback) {
                return;
            }
            com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, 40, 255);
            // GUTUBE(issue #179): a tab lit over another page (a rebuilt bar lights its first tab,
            // Home, over History opened from You) is a switch: as a re-tap it refreshed History.
            boolean onItsPage = item.getItemId() == YOU_ITEM_ID ? mYouShowing
                    : !mYouShowing && item.getItemId() == toMenuItemId(mCurrentSectionId);
            if (!onItsPage) {
                onNavItemChosen(item.getItemId());
                return;
            }
            if (item.getItemId() != YOU_ITEM_ID && mContentGrid != null
                    && mContentGrid.getVisibility() == View.VISIBLE && mContentGrid.canScrollVertically(-1)) {
                smoothScrollGridToTop();
            } else if (item.getItemId() != YOU_ITEM_ID && !mYouShowing && mContentSwipe.isEnabled()
                    && !mContentSwipe.isRefreshing() && mContentGrid != null && mContentGrid.getVisibility() == View.VISIBLE) {
                mContentSwipe.setRefreshing(true);
                refreshFeed();
            } else {
                onNavItemChosen(item.getItemId());
            }
        });
    }

    /**
     * GUTUBE(shorts): the lens is gliding to a tab. The Shorts player waits for it to settle: its
     * start took the glide's frames and only the switch to Shorts stuttered.
     */
    private boolean mNavLensGliding;

    /** The lit-tab pill (bg_nav_item), off the bar while the lens is it. */
    @Nullable
    private android.graphics.drawable.Drawable mNavPill;

    /**
     * GUTUBE(nav lens, issue #46): the bar wiggles toward the picked tab and the lit pill becomes a
     * liquid glass lens that glides there and settles back into the pill. False with the setting off
     * (or no switch to show): then the bar only nudges.
     */
    private boolean playNavLens(int toItemId) {
        com.newtube.mobile.ui.common.NavLens lens = findViewById(R.id.mobile_nav_lens);
        View from = mBottomNav.findViewById(mBottomNav.getSelectedItemId());
        View to = mBottomNav.findViewById(toItemId);
        View bar = findViewById(R.id.mobile_nav_glass);
        // GUTUBE(youtube mode): no lens and no wiggle on the docked bar.
        if (!com.newtube.mobile.ui.common.NavLens.enabled(this) || com.newtube.mobile.ui.common.YouTubeMode.isOn(this)
                || from == null || to == null || from == to
                || to.getWidth() == 0 || !(bar instanceof com.newtube.mobile.ui.common.GlassView)) {
            return false;
        }
        boolean dragged = !Float.isNaN(mNavDragFromX); // GUTUBE(#90): from where the finger let go
        float fromX = dragged ? mNavDragFromX : com.newtube.mobile.ui.common.NavLens.centreInBar(from, bar);
        com.newtube.mobile.ui.common.NavLens.jiggle(bar,
                com.newtube.mobile.ui.common.NavLens.centreInBar(to, bar) > fromX ? 1 : -1);
        if (lens == null || !com.newtube.mobile.ui.common.NavLens.supported(this)) {
            return true;
        }
        if (mNavPill == null) {
            mNavPill = mBottomNav.getItemBackground();
        }
        mBottomNav.setItemBackground(null);
        mNavLensGliding = true;
        lens.play((com.newtube.mobile.ui.common.GlassView) bar, fromX, to, dragged, () -> {
            mBottomNav.setItemBackground(mNavPill);
            mNavLensGliding = false;
            mBottomNav.post(this::maybeLaunchShorts); // held while the lens glided
        });
        return true;
    }

    // GUTUBE(#90): a finger dragged along the bar - the lens follows it from wherever it started (a
    // tick and a pop per tab it crosses) and the tab it is let go on opens, the lens gliding on from there.
    private float mNavDownX = Float.NaN;
    private boolean mNavDragging;
    private boolean mNavDragLens;
    private int mNavDragOver;
    /** Where a dragged lens was let go (bar coordinates) while its switch plays; NaN otherwise. */
    private float mNavDragFromX = Float.NaN;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        return onNavDrag(event) || super.dispatchTouchEvent(event);
    }

    private boolean onNavDrag(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        View bar = findViewById(R.id.mobile_nav_glass);

        if (action == android.view.MotionEvent.ACTION_DOWN) {
            View hint = findViewById(R.id.mobile_nav_hint);
            if (hint != null && hint.getVisibility() == View.VISIBLE && !isOn(hint, event)) {
                hideNavHint(); // a touch anywhere else lets it go
            }
            mNavDragging = false;
            // GUTUBE(youtube mode): the docked bar is tapped, never dragged across, as on YouTube.
            mNavDownX = mBottomNav != null && mBottomNav.isShown() && isOn(mBottomNav, event)
                    && !com.newtube.mobile.ui.common.YouTubeMode.isOn(this) ? event.getRawX() : Float.NaN;

            return false;
        }

        if (Float.isNaN(mNavDownX)) {
            return false;
        }

        boolean end = action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL;
        if (!mNavDragging) {
            if (end) {
                mNavDownX = Float.NaN;
            }
            if (action != android.view.MotionEvent.ACTION_MOVE
                    || Math.abs(event.getRawX() - mNavDownX) < android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                return false;
            }
            // A drag now, not a tap: the tab under the finger lets go of the press.
            mNavDragging = true;
            com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, 26, 220);
            mNavDragOver = View.NO_ID; // the tab under the finger pops out (and ticks) below
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            popNavBar(bar, true);
            hideNavHint();
            mNavDragLens = com.newtube.mobile.ui.common.NavLens.enabled(this)
                    && com.newtube.mobile.ui.common.NavLens.supported(this)
                    && bar instanceof com.newtube.mobile.ui.common.GlassView;
            if (mNavDragLens) {
                if (mNavPill == null) {
                    mNavPill = mBottomNav.getItemBackground();
                }
                mBottomNav.setItemBackground(null);
            }
        }
        // The finger in the bar's coordinates, kept between the first and last tabs' centres.
        int[] at = new int[2];
        bar.getLocationOnScreen(at);
        float x = (event.getRawX() - at[0]) / bar.getScaleX(); // the bar is popped out while dragged
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int over = mNavDragOver;
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < mBottomNav.getMenu().size(); i++) {
            android.view.MenuItem item = mBottomNav.getMenu().getItem(i);
            View tab = mBottomNav.findViewById(item.getItemId());
            if (!item.isVisible() || tab == null) {
                continue;
            }
            float centre = com.newtube.mobile.ui.common.NavLens.centreInBar(tab, bar);
            min = Math.min(min, centre);
            max = Math.max(max, centre);
            if (Math.abs(centre - x) < nearest) {
                nearest = Math.abs(centre - x);
                over = item.getItemId();
            }
        }
        x = Math.max(min, Math.min(max, x));
        if (over != mNavDragOver) {
            popNavTab(mNavDragOver, false);
            popNavTab(over, true);
            mNavDragOver = over;
            com.newtube.mobile.ui.common.Haptics.nav(mBottomNav, 24, 220);
        }
        if (mBottomNav.getBackground() instanceof NavBloom) { // the glow rides under the finger too
            int[] nav = new int[2];
            mBottomNav.getLocationOnScreen(nav);
            ((NavBloom) mBottomNav.getBackground()).follow(end ? Float.NaN : x - (nav[0] - at[0]) / bar.getScaleX(), over);
        }
        com.newtube.mobile.ui.common.NavLens lens = findViewById(R.id.mobile_nav_lens);
        View lit = mBottomNav.findViewById(mBottomNav.getSelectedItemId());
        if (!end) {
            View under = mBottomNav.findViewById(over);
            if (mNavDragLens && under != null) {
                lens.drag((com.newtube.mobile.ui.common.GlassView) bar, under, x);
            }
            return true;
        }
        popNavTab(over, false);
        popNavBar(bar, false); // a switch's wiggle takes it over from wherever it is
        com.newtube.mobile.ui.common.NavSpringPhysics.resetAll(mBottomNav);
        mNavDownX = Float.NaN;
        mNavDragging = false;
        int target = action == android.view.MotionEvent.ACTION_UP ? over : mBottomNav.getSelectedItemId();
        if (target == SEARCH_ITEM_ID) {
            // Search is a button, not a place: it opens, and the lens goes back to the lit tab.
            SearchPresenter.instance(this).startTextSearch();
            target = mBottomNav.getSelectedItemId();
        } else if (target == CAST_ITEM_ID) {
            CastPickerLauncher.open(this, CastPickerLauncher::presentPlain);
            target = mBottomNav.getSelectedItemId();
        }
        if (target != mBottomNav.getSelectedItemId()) {
            mNavDragFromX = mNavDragLens ? x : Float.NaN;
            mBottomNav.setSelectedItemId(target); // the usual switch, its lens from x
            mNavDragFromX = Float.NaN;
        } else if (mNavDragLens && lit != null) {
            lens.play((com.newtube.mobile.ui.common.GlassView) bar, x, lit, true,
                    () -> mBottomNav.setItemBackground(mNavPill)); // back home
        }
        return true;
    }

    /** The tab scale remains static (1.0f) while icon view receives elastic spring physics. */
    private void popNavTab(int itemId, boolean out) {
        View tab = itemId != View.NO_ID ? mBottomNav.findViewById(itemId) : null;
        if (tab != null) {
            tab.setScaleX(1.0f);
            tab.setScaleY(1.0f);
            com.newtube.mobile.ui.common.NavSpringPhysics.setItemPressed(tab, out);
        }
    }

    /** The bottom navigation bar remains static in shape and size (scale 1.0f). */
    private static void popNavBar(View bar, boolean out) {
        if (bar != null) {
            Object running = bar.getTag(R.id.mobile_jiggle_animator);
            if (running instanceof android.animation.Animator) {
                ((android.animation.Animator) running).cancel();
            }
            bar.setScaleX(1.0f);
            bar.setScaleY(1.0f);
        }
    }

    // GUTUBE(issue #130): a held tab's glass hint over the bar. With the tab names on, the whole bar
    // offers Tabs customization; with them off a tab shows its name (lit in the glow's colour if it is
    // the open tab), and a tab Settings can swap adds "Change". Either opens Settings > Tabs after a
    // confirmation. It goes after a few seconds, on a touch elsewhere, or as a drag starts.
    private static final long NAV_HINT_MS = 3000;
    private final Runnable mHideNavHint = this::hideNavHint;

    private boolean showNavHint(View tab, int itemId) {
        View hint = findViewById(R.id.mobile_nav_hint);
        View bar = findViewById(R.id.mobile_nav_glass);
        if (hint == null || bar == null) {
            return false;
        }
        TextView name = hint.findViewById(R.id.mobile_nav_hint_name);
        View change = hint.findViewById(R.id.mobile_nav_hint_change);
        boolean labels = NavTabs.showLabels(this);
        boolean swappable = false;
        for (int section : NavTabs.CHOICES) {
            swappable |= toMenuItemId(section) == itemId;
        }
        int lit = mBottomNav.getSelectedItemId();
        int glow = NavBloom.colorOf(this, labels ? lit : itemId);
        int plain = androidx.core.content.ContextCompat.getColor(this, R.color.mobile_color_on_surface);
        if (labels) {
            name.setText(R.string.mobile_nav_hint_tabs);
            name.setTextColor(glow != 0 ? glow : plain);
        } else {
            android.view.MenuItem item = mBottomNav.getMenu().findItem(itemId);
            name.setText(item != null ? item.getTitle() : null);
            name.setTextColor(itemId == lit && glow != 0 ? glow : plain);
        }
        change.setVisibility(!labels && swappable ? View.VISIBLE : View.GONE);
        boolean opens = labels || swappable;
        hint.setOnClickListener(v -> {
            hideNavHint();
            if (opens) {
                confirmTabsCustomization();
            }
        });
        com.newtube.mobile.ui.common.Glass.blur(hint, findViewById(R.id.mobile_blur_target));
        hint.animate().cancel();
        hint.setAlpha(0f);
        hint.setVisibility(View.VISIBLE);
        // Placed once laid out with its new text: centred over the tab, kept over the bar.
        hint.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                hint.getViewTreeObserver().removeOnPreDrawListener(this);
                float centre = bar.getLeft() + com.newtube.mobile.ui.common.NavLens.centreInBar(tab, bar);
                float x = Math.max(bar.getLeft(), Math.min(bar.getRight() - hint.getWidth(), centre - hint.getWidth() / 2f));
                hint.setTranslationX(x - hint.getLeft());
                hint.setTranslationY(bar.getTop() - hint.getHeight() - 10 * getResources().getDisplayMetrics().density - hint.getTop());
                hint.setPivotX(centre - x); // it grows out of the tab
                hint.setPivotY(hint.getHeight());
                hint.setScaleX(0.8f);
                hint.setScaleY(0.8f);
                hint.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();
                return true;
            }
        });
        hint.removeCallbacks(mHideNavHint);
        hint.postDelayed(mHideNavHint, NAV_HINT_MS);
        return true;
    }

    private void hideNavHint() {
        View hint = findViewById(R.id.mobile_nav_hint);
        if (hint == null || hint.getVisibility() != View.VISIBLE) {
            return;
        }
        hint.removeCallbacks(mHideNavHint);
        hint.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> hint.setVisibility(View.GONE)).start();
    }

    private void confirmTabsCustomization() {
        com.newtube.mobile.ui.common.Haptics.watch(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_nav_hint_confirm_title)
                .setMessage(R.string.mobile_nav_hint_confirm_body)
                .setPositiveButton(R.string.mobile_nav_hint_confirm_open, (dialog, which) -> startActivity(
                        com.newtube.mobile.ui.settings.MobileSettingsActivity.intent(this,
                                com.newtube.mobile.ui.settings.SettingsPages.FEEDS_TABS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static boolean isOn(View view, android.view.MotionEvent event) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return event.getRawX() >= at[0] && event.getRawX() < at[0] + view.getWidth()
                && event.getRawY() >= at[1] && event.getRawY() < at[1] + view.getHeight();
    }

    private void onNavItemChosen(int itemId) {
        // GUTUBE(shorts): like YouTube, the Shorts tab IS the vertical player - no grid. The tab
        // loads the feed (spinner only) and opens its first unseen short; leaving the player comes
        // back to the tab you were on (onResume).
        boolean shorts = itemId == toMenuItemId(MediaGroup.TYPE_SHORTS);
        mShortsLaunchPending = shorts;
        if (!shorts) {
            mLastNonShortsNavItem = itemId;
            holdNav(false); // You never passes through showShortsTab
        }
        if (itemId == YOU_ITEM_ID) {
            showYouPanel();
            // GUTUBE(frost): the page frosts in on the panel's opaque background, which covers the
            // last tab at once - frosting the whole panel faded that tab through it for a moment.
            Motion.frostIn(((ViewGroup) mYouPanel).getChildAt(0));
        } else {
            boolean fromYou = mYouShowing;
            mSectionFromYou = false;
            hideYouPanel();
            onSectionChosen(itemId - ITEM_ID_OFFSET);
            if (fromYou && !mSectionSwapPending && mContentGrid.getVisibility() == View.VISIBLE) {
                Motion.frostIn(mContentGrid); // GUTUBE(frost): back to the tab under You - same page, same arrival
            }
        }
        maybeLaunchShorts();
    }

    /** GUTUBE(shorts): the Shorts tab is waiting to open the player. */
    private boolean mShortsLaunchPending;
    /** GUTUBE(shorts): the tab to come back to when the Shorts player closes. */
    private int mLastNonShortsNavItem = MediaGroup.TYPE_HOME + ITEM_ID_OFFSET;

    private void maybeLaunchShorts() {
        if (!mShortsLaunchPending || mCurrentSectionId != MediaGroup.TYPE_SHORTS || mYouShowing || mNavLensGliding
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            return;
        }
        // Three pages of nothing new: play what there is rather than spin forever (the YT client's
        // last pass of its freshness filter does the same).
        boolean anyShort = mShortsLaunchPages >= 3;
        for (Video video : mCurrentVideos) {
            if (isFreshShort(video) || (anyShort && video.belongsToShorts() && video.hasVideo())) {
                mShortsLaunchPending = false;
                mShortsLaunchPages = 0;
                // The player covers the tab now (its window is translucent, so this one keeps
                // drawing): a spinner left turning here redrew this window ~100 times a second
                // under every short, GPU time taken from the player's own swipes.
                View loading = findViewById(R.id.mobile_shorts_loading);
                if (loading != null) {
                    loading.setVisibility(View.GONE);
                }
                onVideoClicked(video);
                overridePendingTransition(R.anim.shorts_open_enter, R.anim.mobile_stay); // fades up from the bottom
                return;
            }
        }
        if (!mCurrentVideos.isEmpty() && mPresenter != null) {
            mShortsLaunchPages++;
            mPresenter.onScrollEnd(mCurrentVideos.get(mCurrentVideos.size() - 1)); // all seen: next page
        }
    }

    private final Runnable mLeaveShortsTab = () -> {
        if (mBottomNav.getMenu().findItem(mLastNonShortsNavItem) == null) {
            mLastNonShortsNavItem = landingItemId(); // GUTUBE(issue #213): Home may be gone from the bar
        }
        if (mCurrentSectionId == MediaGroup.TYPE_SHORTS && !mShortsLaunchPending && !mYouShowing
                && mBottomNav.getMenu().findItem(mLastNonShortsNavItem) != null) {
            // GUTUBE(issue #107): one move back - the player has slid down, the tab is just there
            // (no lens or wiggle) and the bar rises from the bottom (showShortsTab -> holdNav).
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(mLastNonShortsNavItem);
            mSuppressNavCallback = false;
            onNavItemChosen(mLastNonShortsNavItem);
        }
    };

    /**
     * GUTUBE(issue #107): set by the Shorts player as it slides itself away, so the resume that
     * follows leaves the Shorts tab at once - a resume without it (one while the player is still
     * opening) waits, and the onPause after it cancels the leave.
     */
    public static boolean sShortsClosed;

    /** Pages asked for while the Shorts tab looked for an unseen short. */
    private int mShortsLaunchPages;

    /** A short to queue: playable, not played this session, not mostly watched before. */
    private static boolean isFreshShort(Video video) {
        return video.belongsToShorts() && video.hasVideo()
                && !com.newtube.mobile.ui.common.ShortsSeen.contains(video.videoId)
                && video.percentWatched < 90;
    }

    /** GUTUBE(shorts): the grid never shows on the Shorts tab - a spinner until the player opens. */
    private void showShortsTab(boolean shorts) {
        mContentSwipe.setAlpha(shorts ? 0f : 1f);
        // A skeleton already fading out over the new tab's cards keeps its alpha: putting it back
        // to 1 flashed it over them until the fade caught up.
        if (shorts || mSkeletonLeaving != mSkeletonShows) {
            mFeedSkeleton.setAlpha(shorts ? 0f : 1f);
        }
        View loading = findViewById(R.id.mobile_shorts_loading);
        if (loading != null) {
            loading.setVisibility(shorts ? View.VISIBLE : View.GONE);
        }
        holdNav(shorts);
    }

    /** GUTUBE(shorts, issue #107): the Shorts tab slides the nav bar down; leaving it slides it back. */
    private void holdNav(boolean held) {
        mNavHeld = held;
        if (held && mSearchFabOut) { // Shorts has no Search: the bubble just goes, the bar stays down
            mSearchFabOut = false;
            cancelSearchMorph();
            mSearchFab.animate().alpha(0f).setDuration(Motion.FADE_OUT_MS)
                    .withEndAction(() -> mSearchFab.setVisibility(View.INVISIBLE)).start();
            followNav();
        }
        if (mNavHider != null) {
            mNavHider.hold(held);
        }
    }

    /**
     * GUTUBE(search fab): NavHider's Morph. A scroll down shrinks the bar into a Search bubble at its
     * end on the side Settings picked; a scroll up stretches the bubble back out into the bar. Only
     * with Search in the bottom bar and not on Shorts; otherwise the usual slide.
     */
    private final com.newtube.mobile.ui.common.NavHider.Morph mSearchMorph = new com.newtube.mobile.ui.common.NavHider.Morph() {
        @Override
        public boolean hide(float travel) {
            if (mSearchFab == null || mNavHeld || mBottomNav.getMenu().findItem(SEARCH_ITEM_ID) == null) {
                return false;
            }
            mSearchFabOut = true;
            morphSearch(true, travel);
            return true;
        }

        @Override
        public boolean show() {
            if (!mSearchFabOut) {
                return false;
            }
            mSearchFabOut = false;
            morphSearch(false, 0f);
            return true;
        }
    };

    /**
     * One glass changing shape between the bar and the Search bubble (its corners always round, so
     * it stays a capsule), on a spring that bulges a touch past the bubble and settles. The tabs dissolve as it
     * shrinks and come back as it reaches the bar's size; the Search icon the other way round. A
     * morph cut short turns round from the shape it had reached.
     */
    private void morphSearch(boolean toButton, float travel) {
        View fab = mSearchFab;
        View bar = findViewById(R.id.mobile_nav_glass);
        View icon = ((ViewGroup) fab).getChildAt(0);
        boolean shown = fab.getVisibility() == View.VISIBLE;
        cancelSearchMorph();
        fab.animate().cancel();
        android.graphics.Rect button = searchButtonRect();
        android.graphics.Rect whole = new android.graphics.Rect(bar.getLeft(), bar.getTop(), bar.getRight(), bar.getBottom());
        android.graphics.Rect from = shown ? new android.graphics.Rect(fab.getLeft(), fab.getTop(), fab.getRight(), fab.getBottom())
                : toButton ? whole : button;
        android.graphics.Rect to = toButton ? button : whole;
        float barFrom = bar.getAlpha();
        float iconFrom = shown ? icon.getAlpha() : 0f;
        fab.setAlpha(1f);
        icon.setAlpha(iconFrom);
        fab.setVisibility(View.VISIBLE);
        bar.setTranslationY(0f);
        bar.setTranslationZ(1f); // the tabs fade over the glass, not under it

        // GUTUBE(search fab): back to the bar without passing it: a bulge over the whole bar's width
        // landed ~30px out past its end, doubling the edge as the bar faded in under it.
        Motion.Spring spring = toButton ? new Motion.Spring(0f, 1f, 0f, 300f, 0.68f, 0.002f)
                : new Motion.Spring(0f, 1f, 0f, 340f, 1f, 0.002f);
        android.animation.ValueAnimator morph = android.animation.ValueAnimator.ofFloat(0f, 1f);
        morph.setDuration(Math.max(1, spring.durationMs));
        morph.setInterpolator(spring);
        morph.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            fab.layout(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            float f = a.getAnimatedFraction();
            if (toButton) {
                bar.setAlpha(barFrom * (1f - clamp01(f / 0.3f)));
                icon.setAlpha(iconFrom + (1f - iconFrom) * clamp01((f - 0.25f) / 0.4f));
            } else {
                bar.setAlpha(barFrom + (1f - barFrom) * clamp01((f - 0.35f) / 0.35f));
                icon.setAlpha(iconFrom * (1f - clamp01(f / 0.2f)));
            }
        });
        morph.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mSearchMorphAnim != animation) {
                    return; // turned round: the new morph owns the views
                }
                mSearchMorphAnim = null;
                bar.setTranslationZ(0f);
                if (toButton) {
                    bar.setAlpha(0f);
                    bar.setTranslationY(travel); // out of the way of taps, as NavHider's slide leaves it
                } else {
                    bar.setAlpha(1f);
                    fab.setVisibility(View.INVISIBLE);
                    fab.requestLayout(); // back to its own corner for next time
                }
            }
        });
        mSearchMorphAnim = morph;
        morph.start();
        followNav();
    }

    private void cancelSearchMorph() {
        android.animation.ValueAnimator morph = mSearchMorphAnim;
        mSearchMorphAnim = null;
        if (morph != null) {
            morph.cancel();
        }
    }

    /** Where the bubble rests: its corner, by its layout params (its bounds move while it morphs). */
    private android.graphics.Rect searchButtonRect() {
        View fab = mSearchFab;
        View root = (View) fab.getParent();
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
        int size = params.width;
        int bottom = root.getHeight() - params.bottomMargin;
        int left = NavTabs.searchFabLeft(this) ? params.leftMargin : root.getWidth() - params.rightMargin - size;
        return new android.graphics.Rect(left, bottom - size, left + size, bottom);
    }

    private static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * The mini card rides with the bar (issue #125); with Search out in its corner it stops above
     * the button instead: the button's height and bottom margin higher than the bar's slide.
     */
    private void followNav() {
        if (mMiniSwipe == null || (mMiniPip != null && mMiniPip.isPinned())) {
            return;
        }
        float lift = 0f;
        View mini = mMiniPlayerBar;
        if (mSearchFabOut && mSearchFab != null && mini != null) {
            android.graphics.Rect button = searchButtonRect();
            float left = mini.getLeft() + mini.getTranslationX();
            if (left < button.right && left + mini.getWidth() > button.left) {
                lift = button.height() + ((ViewGroup.MarginLayoutParams) mSearchFab.getLayoutParams()).bottomMargin;
            }
        }
        mMiniSwipe.setHomeY(mNavY - lift);
    }

    /** A long way down, jump most of it first: the glide covers the last few rows only. */
    private void smoothScrollGridToTop() {
        if (mAppBar != null) {
            mAppBar.setExpanded(true, true);
        }
        RecyclerView.LayoutManager layout = mContentGrid.getLayoutManager();
        if (layout instanceof LinearLayoutManager) {
            int first = ((LinearLayoutManager) layout).findFirstVisibleItemPosition();
            int jumpTo = 6 * Math.max(1, layout instanceof GridLayoutManager
                    ? ((GridLayoutManager) layout).getSpanCount() : 1);
            if (first > jumpTo) {
                mContentGrid.scrollToPosition(jumpTo);
            }
        }
        mContentGrid.smoothScrollToPosition(0);
    }

    // ---------------------------------------------------------------------------------
    // "You" tab: the YouTube-style personal surface that replaced the navigation drawer
    // and the top-bar settings icon. Account row on top, then a row for every enabled
    // BrowsePresenter section that didn't make the bottom nav (Playlists, Music, ...),
    // then Settings. Section rows route through the same onSectionChosen() path the nav
    // uses; back from such a section returns to the panel (mSectionFromYou).
    // ---------------------------------------------------------------------------------

    private void setupYouPanel() {
        View accountRow = findViewById(R.id.mobile_you_account_row);
        if (accountRow != null) {
            accountRow.setClickable(false);
            accountRow.setBackground(null);
        }
        View avatarGlass = findViewById(R.id.mobile_you_avatar_glass);
        if (avatarGlass != null) {
            avatarGlass.setOnClickListener(v -> AccountsSheet.show(this, this::updateAccountRow));
        }
        View accountText = findViewById(R.id.mobile_you_account_text);
        if (accountText != null) {
            accountText.setOnClickListener(this::popText);
        }
        View accountSub = findViewById(R.id.mobile_you_account_sub);
        if (accountSub != null) {
            accountSub.setOnClickListener(this::popText);
        }
        View notifBtn = findViewById(R.id.mobile_you_notifications);
        if (notifBtn != null) {
            notifBtn.setOnClickListener(v -> startActivity(
                    com.newtube.mobile.ui.settings.MobileSettingsActivity.intent(this,
                            com.newtube.mobile.ui.settings.SettingsPages.NOTIFICATIONS)));
        }
        View signinBtn = findViewById(R.id.mobile_you_signin_btn);
        if (signinBtn != null) {
            signinBtn.setOnClickListener(v -> AccountsSheet.show(this, this::updateAccountRow));
        }
        View accountsBtn = findViewById(R.id.mobile_you_accounts);
        if (accountsBtn != null) {
            accountsBtn.setOnClickListener(v -> AccountsSheet.show(this, this::updateAccountRow));
        }
        View settingsBtn = findViewById(R.id.mobile_you_settings);
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v -> openSettings());
        }

        // Library section tiles
        View libSubs = findViewById(R.id.mobile_you_lib_subscriptions);
        if (libSubs != null) {
            libSubs.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_SUBSCRIPTIONS));
        }
        View libDownloads = findViewById(R.id.mobile_you_lib_downloads);
        if (libDownloads != null) {
            libDownloads.setOnClickListener(v -> openFromYou(VideoDownloads.SECTION_ID));
        }
        View libWatchLater = findViewById(R.id.mobile_you_lib_watch_later);
        if (libWatchLater != null) {
            libWatchLater.setOnClickListener(v -> openWatchLater());
        }
        View libLiked = findViewById(R.id.mobile_you_lib_liked);
        if (libLiked != null) {
            libLiked.setOnClickListener(v -> openLikedVideos());
        }
        View libPlaylists = findViewById(R.id.mobile_you_lib_playlists);
        if (libPlaylists != null) {
            libPlaylists.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_USER_PLAYLISTS));
        }
        View libMyVideos = findViewById(R.id.mobile_you_lib_my_videos);
        if (libMyVideos != null) {
            libMyVideos.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_MY_VIDEOS));
        }

        // Explore section tiles
        View expKids = findViewById(R.id.mobile_you_explore_kids);
        if (expKids != null) {
            expKids.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_KIDS_HOME));
        }
        View expHyped = findViewById(R.id.mobile_you_explore_hyped);
        if (expHyped != null) {
            expHyped.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_TRENDING));
        }
        View expSports = findViewById(R.id.mobile_you_explore_sports);
        if (expSports != null) {
            expSports.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_SPORTS));
        }
        View expLive = findViewById(R.id.mobile_you_explore_live);
        if (expLive != null) {
            expLive.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_LIVE));
        }
        View expGaming = findViewById(R.id.mobile_you_explore_gaming);
        if (expGaming != null) {
            expGaming.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_GAMING));
        }
        View expNews = findViewById(R.id.mobile_you_explore_news);
        if (expNews != null) {
            expNews.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_NEWS));
        }
        View expMusic = findViewById(R.id.mobile_you_explore_music);
        if (expMusic != null) {
            expMusic.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_MUSIC));
        }

        View historyTitle = findViewById(R.id.mobile_you_history_title);
        if (historyTitle != null) {
            historyTitle.setOnClickListener(v -> openFromYou(MediaGroup.TYPE_HISTORY));
        }
        if (avatarGlass instanceof ViewGroup && mYouAvatar != null) {
            com.newtube.mobile.ui.settings.TiltParallax.attach((ViewGroup) avatarGlass, 0.4f, mYouAvatar);
        }

        // GUTUBE(you page): History's latest videos, two and a bit across like YouTube's You page.
        int cardWidth = Math.round(getResources().getDisplayMetrics().widthPixels / 2.4f);
        mYouHistoryAdapter = new VideoCardAdapter(video -> {
            mYouHistoryTap = true;
            try {
                onVideoClicked(video);
            } finally {
                mYouHistoryTap = false;
            }
        }, this::onVideoLongClicked) {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                RecyclerView.ViewHolder holder = super.onCreateViewHolder(parent, viewType);
                holder.itemView.getLayoutParams().width = cardWidth;
                return holder;
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                View avatar = holder.itemView.findViewById(R.id.video_avatar);
                if (avatar != null) {
                    avatar.setVisibility(View.GONE); // a small card: the title takes the width
                }
            }
        };
        RecyclerView historyList = findViewById(R.id.mobile_you_history_list);
        if (historyList != null) {
            historyList.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            historyList.setAdapter(mYouHistoryAdapter);
            historyList.setItemAnimator(null);
            historyList.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        mYouHistoryToStart = false; // the user's own scroll wins over the landing
                    }
                }
            });
        }
    }

    private void openWatchLater() {
        mSectionFromYou = true;
        hideYouPanel();
        Video playlist = new Video();
        playlist.playlistId = "WL";
        playlist.title = getString(R.string.mobile_you_watch_later);
        com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(this)
                .openChannel(playlist);
        onYouPanelToggled();
    }

    private void openLikedVideos() {
        mSectionFromYou = true;
        hideYouPanel();
        Video playlist = new Video();
        playlist.playlistId = "LL";
        playlist.title = getString(R.string.mobile_you_liked_videos);
        com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(this)
                .openChannel(playlist);
        onYouPanelToggled();
    }

    private void startTypewriter() {
        if (mTypewriterRunning || mYouAccountSub == null) {
            return;
        }
        mTypewriterRunning = true;
        final String[] phrases = new String[] {
                getString(R.string.mobile_you_phrase_sync),
                getString(R.string.mobile_you_phrase_playlists),
                getString(R.string.mobile_you_phrase_history)
        };
        mTypewriterPhraseIndex = 0;
        mTypewriterCharIndex = phrases[0].length();
        mTypewriterDeleting = true;

        mTypewriterRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mTypewriterRunning || mYouAccountSub == null || isDestroyed()) {
                    return;
                }
                String currentPhrase = phrases[mTypewriterPhraseIndex];
                if (mTypewriterDeleting) {
                    mTypewriterCharIndex = Math.max(0, mTypewriterCharIndex - 1);
                    mYouAccountSub.setText(currentPhrase.substring(0, mTypewriterCharIndex));
                } else {
                    mTypewriterCharIndex = Math.min(currentPhrase.length(), mTypewriterCharIndex + 1);
                    mYouAccountSub.setText(currentPhrase.substring(0, mTypewriterCharIndex));
                }

                long delay = mTypewriterDeleting ? 35 : 65;
                if (!mTypewriterDeleting && mTypewriterCharIndex >= currentPhrase.length()) {
                    delay = 2200;
                    mTypewriterDeleting = true;
                } else if (mTypewriterDeleting && mTypewriterCharIndex <= 0) {
                    mTypewriterDeleting = false;
                    mTypewriterPhraseIndex = (mTypewriterPhraseIndex + 1) % phrases.length;
                    delay = 450;
                }
                mTypewriterHandler.postDelayed(this, delay);
            }
        };
        mTypewriterHandler.postDelayed(mTypewriterRunnable, 2200);
    }

    private void stopTypewriter() {
        mTypewriterRunning = false;
        if (mTypewriterRunnable != null) {
            mTypewriterHandler.removeCallbacks(mTypewriterRunnable);
            mTypewriterRunnable = null;
        }
    }

    /** GUTUBE(you page): a quick springy pop of the touched text, nothing else. */
    private void popText(View text) {
        text.setPivotX(0f); // grows from its start, so it stays lined up with the avatar
        text.setPivotY(text.getHeight() / 2f);
        text.animate().cancel();
        text.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> text.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start())
                .start();
        com.newtube.mobile.ui.common.Haptics.tick(text);
    }

    /** GUTUBE(you page): History's videos for its row - the last ones seen at once, then fresh ones. */
    private void loadYouHistory() {
        BrowseSection history = null;
        for (BrowseSection section : mSections) {
            if (section.getId() == MediaGroup.TYPE_HISTORY && section.isEnabled()) {
                history = section;
            }
        }
        View block = findViewById(R.id.mobile_you_history);
        block.setVisibility(history != null ? View.VISIBLE : View.GONE);
        if (history == null) {
            return;
        }
        ((TextView) findViewById(R.id.mobile_you_history_title)).setText(history.getTitle());
        // GUTUBE(issue #168): the row opens at its start; it kept the last visit's scroll for a frame.
        ((RecyclerView) findViewById(R.id.mobile_you_history_list)).scrollToPosition(0);
        mYouHistoryToStart = true;
        // GUTUBE(you refresh): with nothing cached the row keeps its cards until the fresh ones land;
        // an empty list hid it and the fetch brought it back, a blink on every visit.
        List<Video> cached = FeedCache.get(MediaGroup.TYPE_HISTORY);
        if (cached != null || mYouHistoryAdapter.getItemCount() == 0) {
            showYouHistory(cached);
        }
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mYouHistoryAction);
        mYouHistoryAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().getHistoryObserve(),
                group -> runOnUiThread(() -> showYouHistory(group != null ? VideoGroup.from(group).getVideos() : null)),
                error -> android.util.Log.w("MobileBrowse", "You history: " + error));
    }

    private void showYouHistory(@Nullable List<Video> videos) {
        if (isDestroyed()) {
            return;
        }
        List<Video> shown = new ArrayList<>();
        if (videos != null) {
            for (Video video : videos) {
                if (video.videoId != null && shown.size() < 20) {
                    shown.add(video);
                }
            }
        }
        RecyclerView list = findViewById(R.id.mobile_you_history_list);
        list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        // GUTUBE(issue #215): decide before the swap. A card inserted in front kept the old first card
        // anchored at the left edge, so the row came back one video in, the same one each time, and
        // the post-swap check saw a row that could scroll left and left it there.
        boolean toStart = mYouHistoryToStart || !list.canScrollHorizontally(-1);
        mYouHistoryAdapter.submitList(shown, () -> {
            if (toStart) {
                list.scrollToPosition(0); // a video just watched lands in front, not off the left edge
            }
        });
    }

    /** Show the You panel over the content area (the panel is opaque; the grid stays put). */
    private void showYouPanel() {
        mYouShowing = true;
        mSectionFromYou = false;
        rebuildYouRows();
        updateAccountRow();
        loadYouHistory();
        mContentSwipe.setRefreshing(false);
        mYouPanel.setVisibility(View.VISIBLE);
        View top = findViewById(R.id.mobile_top_glass);
        if (top != null) {
            top.setVisibility(View.GONE);
        }
        onYouPanelToggled();
    }

    private void hideYouPanel() {
        stopTypewriter();
        mYouShowing = false;
        mYouPanel.setVisibility(View.GONE);
        View top = findViewById(R.id.mobile_top_glass);
        if (top != null) {
            top.setVisibility(View.VISIBLE);
        }
        onYouPanelToggled();
    }

    /**
     * NEWTUBE(you-subscreen): state that follows the You panel and the section-from-You flag.
     * (1) The feed stays laid out under the opaque panel, so TalkBack kept walking its cards; hide
     * it (and its skeleton/error overlays) from accessibility while the panel covers it. (2) The
     * top bar: a section opened from a You row - one without a bottom-nav tab of its own - is a
     * You sub-screen with a back arrow and its name.
     */
    private void onYouPanelToggled() {
        showNav();
        int a11y = mYouShowing
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        mContentSwipe.setImportantForAccessibility(a11y);
        mFeedSkeleton.setImportantForAccessibility(a11y);
        mErrorContainer.setImportantForAccessibility(a11y);

        boolean subScreen = mSectionFromYou && !mYouShowing
                && mBottomNav.getMenu().findItem(toMenuItemId(mCurrentSectionId)) == null;
        mTopBar.show(subScreen, mYouShowing ? getString(R.string.mobile_nav_you) : getCurrentSectionTitle());
        updateChipRow();
    }

    /** GUTUBE(you page): Subscriptions and Downloads stay in Library while on the bottom bar too. */
    private static boolean isAlwaysInLibrary(int sectionId) {
        return sectionId == MediaGroup.TYPE_SUBSCRIPTIONS || sectionId == VideoDownloads.SECTION_ID;
    }

    /**
     * GUTUBE(you order): Library follows NavTabs; Explore follows the presenter's sidebar order.
     * Sections on the bottom bar are excluded except Subscriptions and Downloads. Pinned channels
     * and playlists have their own group. Long-press on a section opens its management menu.
     */
    private void rebuildYouRows() {
        mYouRows.removeAllViews();
        mYouUpdateRow = null;

        if (AppUpdates.instance(this).hasUpdate()) {
            addYouUpdateRow();
        }

        List<BrowseSection> navSections = selectNavSections();
        List<String> libraryOrder = NavTabs.libraryOrder(this);
        Map<String, BrowseSection> personal = new HashMap<>();
        List<BrowseSection> pinned = new ArrayList<>();
        List<BrowseSection> savedPlaylists = new ArrayList<>();
        List<BrowseSection> explore = new ArrayList<>();

        for (BrowseSection section : mSections) {
            if (!section.isEnabled()
                    || (navSections.contains(section) && !isAlwaysInLibrary(section.getId()))
                    || section.getId() == MediaGroup.TYPE_SETTINGS
                    || section.getId() == MediaGroup.TYPE_SHORTS
                    || section.getId() == MediaGroup.TYPE_HOME
                    || section.getId() == MediaGroup.TYPE_HISTORY // GUTUBE(you page): its own row up top
                    || section.getId() == MediaGroup.TYPE_CHANNEL_UPLOADS) { // GUTUBE: Subscriptions' channels again
                continue;
            }
            // NEWTUBE(menu): channels/playlists pinned from a card menu ("Pin to You") carry the
            // pinned Video as their data; they get their own group instead of mixing into Explore.
            if (section.getData() instanceof Video) {
                // GUTUBE(issue #242): playlists saved from a playlist page or a card menu get their own group.
                (((Video) section.getData()).playlistId != null ? savedPlaylists : pinned).add(section);
                continue;
            }
            String key = String.valueOf(section.getId());
            if (libraryOrder.contains(key)) {
                personal.put(key, section);
            } else {
                explore.add(section);
            }
        }

        // GUTUBE(you order): sections and account playlists follow the same saved Library order.
        List<View> library = new ArrayList<>();
        for (String key : libraryOrder) {
            if ("WL".equals(key) || "LL".equals(key)) {
                if (NavTabs.showLibraryPlaylist(this, key)) {
                    library.add(youLibraryPlaylistRow(key));
                }
            } else if (personal.containsKey(key)) {
                library.add(youSectionRow(personal.get(key), true));
            }
        }
        // GUTUBE(issue #242): saved playlists and pinned channels right under History, on a glass
        // card like Library's; two tiles a line so a playlist's name has room.
        if (!savedPlaylists.isEmpty()) {
            addYouGroupLabel(getString(R.string.mobile_you_saved_playlists));
            addYouSectionRows(savedPlaylists, 2);
        }

        if (!pinned.isEmpty()) {
            boolean channels = true;
            for (BrowseSection section : pinned) {
                channels &= pinnedChannel(section) != null;
            }
            addYouGroupLabel(getString(channels ? R.string.mobile_you_pinned_channels : R.string.mobile_you_pinned));
            addYouSectionRows(pinned, 2);
        }
    }

    /**
     * GUTUBE(you page): Pinned is full-width glass pills (icon, name, chevron), one to a line;
     * Library and Explore are glass tiles, the icon over its name, {@code perLine} to a line.
     */
    private void addYouSectionRows(List<BrowseSection> sections, int perLine) {
        List<View> rows = new ArrayList<>();
        for (BrowseSection section : sections) {
            View row = youSectionRow(section, perLine > 1);
            if (perLine == 2) {
                ((TextView) row.findViewById(R.id.mobile_you_row_label)).setMaxLines(2);
                Video channel = pinnedChannel(section);
                String avatar = channel == null ? null : com.newtube.mobile.ui.common.ChannelAvatars.get(this, channel.channelId, channel.getAuthor());
                if (channel != null && avatar == null) {
                    avatar = section.getIconUrl();
                }
                if (avatar != null) { // GUTUBE(you page): a pinned channel shows its avatar, not the pin
                    ImageView icon = row.findViewById(R.id.mobile_you_row_icon);
                    icon.setImageTintList(null);
                    int size = Math.round(32 * getResources().getDisplayMetrics().density);
                    icon.getLayoutParams().width = size;
                    icon.getLayoutParams().height = size;
                    com.bumptech.glide.Glide.with(icon).load(avatar).circleCrop()
                            .placeholder(section.getResId()).into(icon);
                }
            }
            rows.add(row);
        }
        addYouLines(rows, perLine);
    }

    private void addYouLines(List<View> rows, int perLine) {
        float density = getResources().getDisplayMetrics().density;
        // GUTUBE(you page): tiles sit together on one glass card lit by the accent
        boolean card = perLine > 1;
        LinearLayout target = mYouRows;
        if (card) {
            target = new LinearLayout(this);
            target.setOrientation(LinearLayout.VERTICAL);
            target.setBackground(new com.newtube.mobile.ui.common.AccentCard(this,
                    com.newtube.mobile.ui.common.AccentCard.YOU_GLASS));
            int pad = Math.round(7 * density);
            target.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Math.round(14 * density), 0, Math.round(14 * density), 0);
            mYouRows.addView(target, lp);
        }
        for (int i = 0; i < rows.size(); i += perLine) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + perLine; j++) {
                if (j < rows.size()) {
                    line.addView(rows.get(j));
                } else {
                    LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, 1, 1f); // keeps the tiles' width
                    int margin = Math.round(5 * density); // the tiles' own margins, so a short line's tiles match the full ones
                    gap.setMargins(margin, 0, margin, 0);
                    line.addView(new View(this), gap);
                }
            }
            if (!card) {
                line.setPadding(Math.round(11 * density), 0, Math.round(11 * density), 0);
            }
            target.addView(line);
        }
    }

    /** The channel a pinned section opens, or null when it is a playlist or something else. */
    @Nullable
    private static Video pinnedChannel(BrowseSection section) {
        Object data = section.getData();
        return data instanceof Video && ((Video) data).channelId != null && ((Video) data).playlistId == null
                ? (Video) data : null;
    }

    private View youSectionRow(BrowseSection section, boolean tile) {
        int sectionId = section.getId();
        View row = addYouRow(tile ? R.layout.item_mobile_you_tile : R.layout.item_mobile_you_pill,
                section.getResId(), section.getTitle(), () -> openFromYou(sectionId));
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                Math.round((tile ? 88 : 64) * density), 1f);
        int margin = Math.round(5 * density);
        lp.setMargins(margin, margin, margin, margin);
        row.setLayoutParams(lp);
        row.setOnLongClickListener(v -> {
            if (mPresenter != null) {
                mPresenter.onSectionLongPressed(sectionId);
            }
            return true;
        });
        return row;
    }

    /** GUTUBE(you page): the account's Watch later and Liked videos open like any playlist. */
    private View youLibraryPlaylistRow(String playlistId) {
        boolean watchLater = "WL".equals(playlistId);
        String title = getString(watchLater ? R.string.mobile_you_watch_later : R.string.mobile_you_liked_videos);
        int icon = watchLater ? R.drawable.ic_menu_watch_later : R.drawable.ic_watch_thumb_up_outline;
        View row = addYouRow(R.layout.item_mobile_you_tile, icon, title, () -> {
            Video playlist = new Video();
            playlist.playlistId = playlistId;
            playlist.title = title;
            com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(this)
                    .openChannel(playlist);
        });
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(88 * density), 1f);
        int margin = Math.round(5 * density);
        lp.setMargins(margin, margin, margin, margin);
        row.setLayoutParams(lp);
        return row;
    }

    private void openFromYou(int sectionId) {
        mSectionFromYou = true;
        hideYouPanel();
        onSectionChosen(sectionId);
        onYouPanelToggled(); // now that the chosen section is current: its name in the top bar
    }

    // ---------------------------------------------------------------------------------
    // Updates (NEWTUBE update-flow)
    // ---------------------------------------------------------------------------------

    private static final long UPDATED_NOTICE_DELAY_MS = 1_000;
    private final AppUpdates.Listener mUpdatesListener = this::onUpdatesChanged;
    /** Home back in front for a few seconds: the quiet update check, if the last one is old. */
    private final Runnable mUpdateCheckIfDue = () -> AppUpdates.instance(this).checkIfDue("resume");
    /** After the first paint of a return, and not for a screen the user only passes through. */
    private static final long UPDATE_CHECK_DELAY_MS = 3_000;
    private final Runnable mAnnounceUpdateInstalled = this::announceUpdateInstalled;
    private final Runnable mPromptUpdate = this::maybePromptUpdate;
    /** The You list's update row while it is built, so progress updates it in place. */
    @Nullable private View mYouUpdateRow;

    /**
     * GUTUBE(update pop-up): a freshly found update opens its sheet - only over Home in front, at
     * most once a day per version, never while the user is watching (Home isn't resumed then).
     */
    private void maybePromptUpdate() {
        AppUpdates updates = AppUpdates.instance(this);
        if (getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && !isFinishing() && !MobileUpdateActivity.isShowing() && updates.shouldPrompt()) {
            updates.markPrompted();
            MobileUpdateActivity.startPrompt(this);
        }
    }

    private void onUpdatesChanged() {
        refreshUpdateBadge();
        maybePromptUpdate();

        if (!mYouShowing) {
            return;
        }

        if (AppUpdates.instance(this).hasUpdate() != (mYouUpdateRow != null)) {
            rebuildYouRows();
        } else if (mYouUpdateRow != null) {
            bindYouUpdateRow(mYouUpdateRow); // a progress tick: no list rebuild
        }
    }

    /** A dot on the You tab while there is an update the user hasn't opened yet. */
    private void refreshUpdateBadge() {
        if (mBottomNav.getMenu().findItem(YOU_ITEM_ID) == null) {
            return;
        }

        if (AppUpdates.instance(this).hasUnseenUpdate()) {
            BadgeDrawable badge = mBottomNav.getOrCreateBadge(YOU_ITEM_ID);
            badge.setBackgroundColor(getColorInt(R.color.mobile_color_primary));
            badge.setContentDescriptionNumberless(getString(R.string.mobile_update_row_available));
            badge.setVisible(true);
        } else {
            mBottomNav.removeBadge(YOU_ITEM_ID);
        }
    }

    private void addYouUpdateRow() {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mobile_you_update_row, mYouRows, false);
        row.setOnClickListener(v -> MobileUpdateActivity.start(this));
        bindYouUpdateRow(row);
        mYouRows.addView(row);
        mYouUpdateRow = row;
        addYouDivider();
    }

    private void bindYouUpdateRow(View row) {
        AppUpdates updates = AppUpdates.instance(this);
        TextView title = row.findViewById(R.id.mobile_you_update_title);
        TextView subtitle = row.findViewById(R.id.mobile_you_update_subtitle);
        String version = updates.getUpdateVersionName();
        String appVersion = getString(R.string.mobile_update_app_version, version);

        switch (updates.getPhase()) {
            case DOWNLOADING:
                long total = updates.getDownloadTotal();
                title.setText(R.string.mobile_update_row_downloading);
                subtitle.setText(total > 0 ? getString(R.string.mobile_update_row_downloading_percent, version,
                        (int) Math.min(100, updates.getDownloadedBytes() * 100 / total)) : appVersion);
                break;
            case READY:
                title.setText(R.string.mobile_update_row_ready);
                subtitle.setText(appVersion);
                break;
            default:
                title.setText(R.string.mobile_update_row_available);
                subtitle.setText(appVersion);
                break;
        }
    }

    /** First launch after an update this app installed: confirm it, with the notes one tap away. */
    private void announceUpdateInstalled() {
        AppUpdates updates = AppUpdates.instance(this);

        if (isFinishing() || !updates.takeJustUpdated()) {
            return;
        }

        boolean hasNotes = !updates.getWhatsNew().isEmpty();
        MobileSnackbar.show(this, getString(R.string.mobile_update_updated, updates.getInstalledVersionName()),
                hasNotes ? getString(R.string.mobile_update_whats_new) : null,
                hasNotes ? () -> MobileUpdateActivity.startWhatsNew(this) : null, MobileSnackbar.NOTICE_DURATION_MS);
    }

    /** A group's heading, as YouTube's You page has them ("Library"). */
    private void addYouGroupLabel(CharSequence text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColorInt(R.color.mobile_color_on_surface));
        label.setTextSize(24);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        float density = getResources().getDisplayMetrics().density;
        label.setPadding(Math.round(16 * density), Math.round(24 * density), Math.round(16 * density),
                Math.round(10 * density));
        mYouRows.addView(label);
    }

    private void addYouDivider() {
        View divider = new View(this);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        int margin = Math.round(20 * density);
        int marginV = Math.round(8 * density);
        lp.setMargins(margin, marginV, margin, marginV);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(getColorInt(R.color.mobile_color_divider));
        mYouRows.addView(divider);
    }

    private View addYouRow(int layoutRes, int iconRes, CharSequence title, Runnable action) {
        View row = LayoutInflater.from(this).inflate(layoutRes, mYouRows, false);

        ImageView icon = row.findViewById(R.id.mobile_you_row_icon);
        if (iconRes > 0) {
            icon.setImageResource(iconRes);
        }
        com.newtube.mobile.ui.common.Glass.blur(row.findViewById(R.id.mobile_you_row_glass), findViewById(R.id.mobile_you_glow_source));
        TextView label = row.findViewById(R.id.mobile_you_row_label);
        label.setText(title);

        row.setOnClickListener(v -> action.run());
        return row;
    }

    /**
     * Single entry point for "user picked section X" - whether from the bottom nav, the
     * drawer, or (indirectly) the presenter's boot selection. Drives the standard
     * {@link BrowsePresenter#onSectionFocused} path and keeps both nav surfaces in sync.
     */
    private void onSectionChosen(int sectionId) {
        // The Settings section has no video-grid renderer on the mobile shell (its content
        // arrives as a SettingsGroup, which updateSection(SettingsGroup) intentionally
        // ignores). Route it to the working AppDialog settings screen instead of a blank grid.
        if (sectionId == MediaGroup.TYPE_SETTINGS) {
            syncNavHighlight(mCurrentSectionId); // keep highlight on the real current section
            openSettings();
            return;
        }

        boolean switched = mCurrentSectionId != sectionId;
        if (switched || mSubsChannel != null) {
            clearSubsChannel(); // GUTUBE(feed-strip): a filter belongs to the visit that set it
        }
        mCurrentSectionId = sectionId;
        showNav();
        updateStrip();
        applyVideoLayout();
        paintCachedSnapshot(sectionId, switched);
        syncNavHighlight(sectionId);
        onYouPanelToggled(); // GUTUBE: the large title names the tab on screen
        updateGridSpanCount(computeSpanCount());
        showShortsTab(sectionId == MediaGroup.TYPE_SHORTS);

        if (mPresenter != null) {
            mPresenter.onSectionFocused(sectionId);
        }
    }

    /**
     * Stale-while-revalidate: repaint the section's last-known content instantly (activity
     * recreation and section switches otherwise stare at a skeleton while the presenter
     * refetches - the single biggest "app feels slow" moment). The presenter's refetch is
     * already on its way; {@link #mAwaitingFreshContent} makes its result replace this.
     */
    private void paintCachedSnapshot(int sectionId, boolean switched) {
        // The error overlay (e.g. the previous section's sign-in gate) belongs to the section
        // that showed it - a switch repaints, so drop it here. updateSection can't be relied on
        // for this: a fresh-within-TTL section skips the refetch and never emits a group.
        hideError();
        cancelFeedSwap(); // NEWTUBE(feed-swap): the previous section's swap is moot now

        // Falls back to the persisted snapshot on the process's first paint of this section,
        // so even a cold start shows cards instead of the skeleton (display-only until the
        // refetch replaces it — see FeedCache class doc).
        List<Video> cached = sectionId == VideoDownloads.SECTION_ID ? null : FeedCache.getOrRestore(sectionId);
        // NEWTUBE(motion): last session's cards wait behind the skeleton (FeedCache
        // .isFromLastSession) - only a failed first load shows them (showError).
        mLastSessionFallback = null;
        if (cached != null && FeedCache.isFromLastSession(sectionId)) {
            mLastSessionFallback = cached;
            cached = null;
        }

        mCurrentVideos.clear();
        mAwaitingFreshContent = cached != null;
        if (cached != null) {
            mCurrentVideos.addAll(visibleFeedItems(cached));
        }

        mLastPaginationTriggerCount = -1;
        mLastNearEndTriggerCount = -1;
        markLiveChannels();
        mContentGrid.animate().cancel();
        mContentGrid.setAlpha(1f);
        if (switched) {
            // NEWTUBE(motion): another section is a new page, not an edit of the old one. Through
            // the item animator it played as a shuffle (Pixel 9, History -> Home: the old cards
            // faded out, the grid sat empty ~200 ms, then a card both lists shared slid up from the
            // bottom while the rest faded in - ~400 ms). The new list now replaces the old one in
            // one frame with the animator parked, pinned to the top, and fades in.
            parkItemAnimator();
            mSectionSwapPending = true;
            mAdapter.submitList(segmented(mCurrentVideos), this::onSectionSwapCommitted);
        } else if (mSectionSwapPending) {
            // A re-paint landing before the switch's own commit supersedes it: it finishes the switch.
            mAdapter.submitList(segmented(mCurrentVideos), this::onSectionSwapCommitted);
        } else {
            mAdapter.submitList(segmented(mCurrentVideos));
        }
        if (!mCurrentVideos.isEmpty()) {
            setSkeletonVisible(false);
            mContentGrid.scrollToPosition(0);
            com.newtube.mobile.LaunchMilestones.onFeedSnapshotPainted(sectionId, mCurrentVideos.size());
        } else if (mLastSessionFallback != null) {
            setSkeletonVisible(true);
        }
    }

    /**
     * Highlight the given section's bottom-nav tab when it has one. A section opened from a
     * You-panel row has no tab of its own; the You tab simply stays selected (YouTube-style).
     */
    private void syncNavHighlight(int sectionId) {
        int itemId = toMenuItemId(sectionId);

        if (mBottomNav.getMenu().findItem(itemId) != null && mBottomNav.getSelectedItemId() != itemId) {
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(itemId);
            mSuppressNavCallback = false;
        }
    }

    private void setupErrorAction() {
        mErrorAction.setOnClickListener(v -> {
            // ErrorFragmentData instance is captured per-call in showError(); re-read it from the tag.
            Object data = mErrorAction.getTag();
            if (data instanceof ErrorFragmentData) {
                ((ErrorFragmentData) data).onAction();
            } else if (mPresenter != null) {
                // Offline empty state (see showError): the button is a plain retry. Pull-to-refresh
                // does the same thing, but it is invisible on a screen with no rows to pull.
                mPresenter.refresh(false);
            }
        });
    }

    private void onVideoClicked(Video video) {
        if (mPresenter == null) {
            return;
        }

        // NEWTUBE(downloads): a card that is still downloading (or paused, or failed) has no file to
        // play yet - its tap opens the download's own menu (pause / resume / cancel / retry / delete). A finished one
        // carries its local file and goes through the ordinary play route below.
        DownloadItem download = DownloadsBridge.instance(this).itemFor(video);
        if (download != null && !download.isDone()) {
            DownloadMenu.show(this, download);
            return;
        }

        mPresenter.onVideoItemSelected(video);

        // GUTUBE(shorts): a short opens the vertical player with the tab's shorts as its queue,
        // so a swipe up plays the next one (and down the previous) - see ShortsOverlay.
        List<Video> shelf = shelfOf(video);
        if (shelf != null) {
            ShortsFilter.queueAround(shelf, video); // GUTUBE(issue #43): Home pages add theirs (extendShortsQueue)
            com.newtube.mobile.ui.common.ShortsFeedTail.arm(); // GUTUBE(issue #227): then the Shorts feed, without end
        } else if (video.belongsToShorts() && video.hasVideo()) {
            queueShorts(video);
        } else if ((video.belongsToHistory() || mYouHistoryTap) && video.hasVideo() && ShortsFilter.isShort(video)) {
            video = queueHistoryShorts(video);
        } else if (isSegmentSection() && video.hasVideo() && ShortsFilter.isShort(video)) {
            // GUTUBE(feed segment): a Short here plays in the Shorts player, the page's Shorts its queue
            Video queued = queueHistoryShorts(video, mCurrentVideos);
            if (queued != null) {
                video = queued;
            }
        }

        // Cards without a videoId (playlists, mixes, channels) don't open the player - they kick
        // an async channel-rows fetch (VideoActionPresenter -> chooseChannelPresenter) that can
        // take seconds before any screen change, and this screen's showProgressBar deliberately
        // shows nothing over a non-empty grid - the tap read as dead. Use the swipe-refresh
        // spinner as tap feedback; it clears when the destination opens (onPause) or the fetch
        // ends/fails (LoadingManager -> showProgressBar(false)). Guarded on the routable shapes
        // so the "doesn't contain needed data" toast case can't leave it spinning.
        if (!video.hasVideo()
                && (video.hasChannel() || video.hasPlaylist() || video.hasNestedItems() || video.hasReloadPageKey())) {
            mContentSwipe.setRefreshing(true);
        }

        // Wave 2: real route. BrowsePresenter.onVideoItemClicked() -> VideoActionPresenter.apply()
        // -> PlaybackPresenter.openVideo() -> ViewManager.startView(PlaybackView.class) for plain
        // videos (now mapped to MobilePlaybackActivity - see MobileMainApplication); channel/
        // playlist items are routed elsewhere by the same presenter, which is fine here too.
        mPresenter.onVideoItemClicked(video);
    }

    /** GUTUBE(shorts): a new Shorts page joins the end of a Shorts queue that is playing. */
    private void extendShortsQueue(List<Video> page) {
        com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist playlist =
                com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance();
        List<Video> queue = playlist.getAll();
        if (page == null || queue.isEmpty() || !queue.get(queue.size() - 1).belongsToShorts()) {
            return;
        }
        java.util.Set<String> queued = new java.util.HashSet<>();
        for (Video video : queue) {
            queued.add(video.videoId);
        }
        List<Video> more = new ArrayList<>();
        for (Video video : visibleFeedItems(page)) {
            if (isFreshShort(video) && queued.add(video.videoId)) { // the reel feed repeats itself
                more.add(video);
            }
        }
        if (!more.isEmpty()) {
            playlist.addAll(more);
            mShortsEmptyPages = 0;
        } else if (mShortsEmptyPages++ < 3 && mPresenter != null && !mCurrentVideos.isEmpty()) {
            // A page of only watched shorts: the next one, or the swipe would stop at the queue's end.
            mPresenter.onScrollEnd(mCurrentVideos.get(mCurrentVideos.size() - 1));
        }
    }

    /** Shorts pages in a row that added nothing to the playing queue. */
    private int mShortsEmptyPages;

    /** GUTUBE(shorts): the Shorts tab's cards around {@code tapped} become the playback queue. */
    /**
     * GUTUBE(shorts): History lists Shorts as ordinary cards; a tapped one plays in the Shorts
     * player with History's other Shorts as its queue. Marked copies, so the grid's cards stay.
     */
    /** True during a tap on the You page's History row (its cards may have lost their group). */
    private boolean mYouHistoryTap;

    private Video queueHistoryShorts(Video tapped) {
        Video opened = queueHistoryShorts(tapped, mCurrentVideos);
        // The You page's History row is not the grid on screen: its own History list is the queue.
        if (opened == null && mYouHistoryTap) {
            opened = queueHistoryShorts(tapped, mYouHistoryAdapter.getCurrentList());
        }
        if (opened == null && tapped.getGroup() != null) {
            opened = queueHistoryShorts(tapped, tapped.getGroup().getVideos());
        }
        return opened != null ? opened : tapped;
    }

    /** The marked copy of {@code tapped} (the same object, not an equal card) queued in {@code source}'s Shorts, or null. */
    @Nullable
    private static Video queueHistoryShorts(Video tapped, List<Video> source) {
        List<Video> shorts = new ArrayList<>();
        Video opened = null;
        for (Video video : source) {
            if (video.hasVideo() && ShortsFilter.isShort(video)) {
                Video copy = video.copy();
                copy.isShorts = true;
                copy.author = video.author; // copy() leaves it, and the overlay names the channel
                copy.markShorts();
                shorts.add(copy);
                if (video == tapped) {
                    opened = copy;
                }
            }
        }
        if (opened != null) {
            ShortsFilter.queueAround(shorts, opened);
        }
        return opened;
    }

    private void queueShorts(Video tapped) {
        java.util.LinkedHashMap<String, Video> unique = new java.util.LinkedHashMap<>();
        for (Video video : mCurrentVideos) {
            if (video.videoId != null && (video == tapped || isFreshShort(video))) {
                unique.putIfAbsent(video.videoId, video);
            }
        }
        List<Video> shorts = new ArrayList<>(unique.values());
        int at = shorts.indexOf(tapped);
        if (at < 0) {
            return;
        }
        // Playlist keeps 50 (300 on roomy phones); later pages join as the swipe nears the end.
        List<Video> window = shorts.subList(Math.max(0, at - 10), Math.min(shorts.size(), at + 40));
        com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist playlist =
                com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance();
        playlist.clear();
        playlist.addAll(new ArrayList<>(window));
        playlist.setCurrent(tapped);
        tapped.fromQueue = true;
    }

    /**
     * Wave 3: long-press = the touch equivalent of the TV D-pad OK-long-press context-menu
     * shortcut. {@code BrowsePresenter.onVideoItemLongClicked()} builds the "..." menu
     * (add to playlist / share / subscribe / etc.) via {@code VideoMenuPresenter} and shows it
     * through {@code AppDialogPresenter} - now rendered by {@code MobileAppDialogActivity}.
     */
    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }

        // NEWTUBE(downloads): the Downloads card menu is about the file (play, share, delete),
        // not about the feed (not interested, block channel...).
        DownloadItem download = DownloadsBridge.instance(this).itemFor(video);
        if (download != null) {
            DownloadMenu.show(this, download);
            return true;
        }

        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    // ---------------------------------------------------------------------------------
    // GUTUBE(feed-strip, 2.9.8): Home's topic chips (issue #4) and Subscriptions' channel
    // avatars (issue #2), the grid's first row (FeedStrip).
    // ---------------------------------------------------------------------------------

    private int stripRows() {
        return (mStrip != null ? mStrip.getItemCount() : 0) + (mSegment != null ? mSegment.getItemCount() : 0);
    }

    /** GUTUBE(feed segment): Subscriptions, or a channel pinned to You: they keep their Shorts, behind the pill. */
    private boolean isSegmentSection() {
        if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
            return true;
        }
        BrowseSection section = findKnownSection(mCurrentSectionId);
        Object data = section != null ? section.getData() : null;
        return data instanceof Video && ((Video) data).channelId != null && ((Video) data).playlistId == null;
    }

    private void updateSegment() {
        if (mSegment == null) {
            return;
        }
        boolean switched = mSegmentSectionId != mCurrentSectionId;
        mSegmentSectionId = mCurrentSectionId;
        mSegment.show(isSegmentSection(), switched);
    }

    /** The cards the pill lets through. */
    private List<Video> segmented(List<Video> videos) {
        List<Video> shown = new ArrayList<>(videos.size());
        int filter = mSegment != null && isSegmentSection() ? mSegment.filter() : ShortsSegment.ALL;
        for (Video video : videos) {
            boolean isShort = video != null && video.hasVideo() && ShortsFilter.isShort(video);
            if (filter == ShortsSegment.ALL || (filter == ShortsSegment.SHORTS) == isShort) {
                shown.add(video);
            }
        }
        return shown;
    }

    private static boolean isHomeChip(int sectionId) {
        for (int chip : HOME_CHIPS) {
            if (chip == sectionId) {
                return true;
            }
        }
        return false;
    }

    /** The last feed card on screen (rows: the last card of the last row showing), -1 for none. */
    private int lastVisibleCard() {
        int last = mLayoutManager.findLastVisibleItemPosition() - stripRows();
        return mRowsShown && last >= 0 ? mRows.cardsThrough(last) : last;
    }

    /** Home and its topics as shelves when Settings says Rows; every other section stays a grid. */
    private void applyHomeLayout() {
        boolean rows = isHomeChip(mCurrentSectionId) && HomeLayout.isRows(this);
        if (rows == mRowsShown || mContentGrid == null) {
            return;
        }
        mRowsShown = rows;
        if (rows) {
            mRows.submit(mAdapter.getCurrentList());
        }
        mContentGrid.setAdapter(rows ? mRowsFeedAdapter : mFeedAdapter);
    }

    /** Which strip the current section shows: chips on Home and its topics, avatars on Subscriptions. */
    private void updateStrip() {
        if (mStrip == null) {
            return;
        }
        applyHomeLayout();
        updateSegment();
        if (isHomeChip(mCurrentSectionId)) {
            List<FeedStrip.Item> chips = new ArrayList<>();
            if (mHomeChips == null) {
                loadHomeChips();
            }
            long selected = mCurrentSectionId;
            for (int chip : HOME_CHIPS) {
                BrowseSection section = findKnownSection(chip);
                CharSequence title = null;
                if (chip == MediaGroup.TYPE_HOME) {
                    title = getString(R.string.mobile_feed_all);
                } else if (section != null) {
                    title = section.getTitle();
                } else {
                    if (chip == MediaGroup.TYPE_MUSIC) {
                        title = getString(R.string.header_music);
                    } else if (chip == MediaGroup.TYPE_GAMING) {
                        title = getString(R.string.header_gaming);
                    } else if (chip == MediaGroup.TYPE_NEWS) {
                        title = getString(R.string.header_news);
                    } else if (chip == MediaGroup.TYPE_SPORTS) {
                        title = getString(R.string.header_sports);
                    } else if (chip == MediaGroup.TYPE_LIVE) {
                        title = getString(R.string.header_live);
                    }
                }
                if (title != null) {
                    chips.add(new FeedStrip.Item(chip, title, null, null));
                }
                // GUTUBE(issue #79): right after All, the account's own channels, then the topics
                for (int i = 0; chip == MediaGroup.TYPE_HOME && mHomeChips != null && i < mHomeChips.size(); i++) {
                    Video channel = mHomeChips.get(i);
                    chips.add(new FeedStrip.Item(HOME_CHIP_ID + i, channel.getAuthor(), null, channel));
                    if (channel == mHomeChip) {
                        selected = HOME_CHIP_ID + i;
                    }
                }
            }
            mStrip.show(chips.size() > 1 && com.newtube.mobile.ui.common.HomeBar.chipsShown(this) ? FeedStrip.MODE_CHIPS : FeedStrip.MODE_NONE, chips, selected);
            updateChipRow();
        } else if (mCurrentSectionId == MediaGroup.TYPE_TRENDING && mHypedGroups.size() > 1) {
            // GUTUBE(hyped): the chart's categories, as on YouTube's Charts: All (Top 100), Gaming...
            List<FeedStrip.Item> chips = new ArrayList<>();
            long selected = HYPED_CHIP_ID;
            int i = 0;
            for (String title : mHypedGroups.keySet()) {
                chips.add(new FeedStrip.Item(HYPED_CHIP_ID + i, i == 0 ? getString(R.string.mobile_feed_all) : title, null, null));
                if (title.equals(hypedShown())) {
                    selected = HYPED_CHIP_ID + i;
                }
                i++;
            }
            mStrip.show(FeedStrip.MODE_CHIPS, chips, selected);
            updateChipRow();
        } else if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
            if (mSubsChannels == null) {
                loadSubsChannels();
            }
            List<FeedStrip.Item> avatars = new ArrayList<>();
            if (mSubsChannels != null && !mSubsChannels.isEmpty()) {
                avatars.add(new FeedStrip.Item(-1, getString(R.string.mobile_feed_all), null, null));
                for (int i = 0; i < mSubsChannels.size(); i++) {
                    Video channel = mSubsChannels.get(i);
                    avatars.add(new FeedStrip.Item(i, channel.getTitle(), channel.getCardImageUrl(), channel));
                }
            }
            mStrip.show(FeedStrip.MODE_AVATARS, avatars,
                    mSubsChannel != null ? mSubsChannels.indexOf(mSubsChannel) : -1);
            updateChipRow();
        } else {
            mStrip.show(FeedStrip.MODE_NONE, null, 0);
            updateChipRow();
        }
    }

    /** An enabled section the presenter has (a hidden topic gets no chip). */
    @Nullable
    private BrowseSection findKnownSection(int sectionId) {
        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId && section.isEnabled()) {
                return section;
            }
        }
        return null;
    }

    /** The category the Hyped grid shows. */
    @Nullable
    private String hypedShown() {
        return mHypedChip != null && mHypedGroups.containsKey(mHypedChip) ? mHypedChip
                : mHypedGroups.isEmpty() ? null : mHypedGroups.keySet().iterator().next();
    }

    /** GUTUBE(hyped): files a chart group under its category; true when it is the one on screen. */
    private boolean keepHyped(VideoGroup group) {
        int action = group.getAction();
        if (action == VideoGroup.ACTION_REPLACE) {
            mHypedGroups.clear();
        }
        List<Video> videos = group.getVideos();
        if ((action != VideoGroup.ACTION_APPEND && action != VideoGroup.ACTION_REPLACE) || videos == null || videos.isEmpty()) {
            return true;
        }
        String title = group.getTitle() != null ? group.getTitle() : "";
        List<Video> list = mHypedGroups.get(title);
        if (list == null) {
            mHypedGroups.put(title, list = new ArrayList<>());
            updateStrip();
        }
        list.addAll(videos);
        return title.equals(hypedShown());
    }

    private void onStripPicked(FeedStrip.Item item) {
        if (item.id >= HYPED_CHIP_ID) { // GUTUBE(hyped): a chart category, from what is already here
            List<String> titles = new ArrayList<>(mHypedGroups.keySet());
            int index = (int) (item.id - HYPED_CHIP_ID);
            if (index >= titles.size()) {
                return;
            }
            mHypedChip = titles.get(index);
            mStrip.select(item.id);
            mCurrentVideos.clear();
            mCurrentVideos.addAll(visibleFeedItems(mHypedGroups.get(mHypedChip)));
            // Emptied first: a diff against the old list stays anchored on a card both share (a
            // Top 100 podcast sat at #11, so Podcast opened there). From empty both are synchronous.
            mAdapter.submitList(null);
            submitGrid();
            mContentGrid.scrollToPosition(0);
        } else if (item.id >= HOME_CHIP_ID) { // GUTUBE(issue #79): a personal chip
            mStrip.select(item.id);
            loadHomeChip((Video) item.data);
        } else if (mHomeChip != null && item.id == mCurrentSectionId) {
            // the section's own chip from a personal one: its feed again, from its snapshot while the presenter refreshes
            clearSubsChannel();
            mStrip.select(item.id);
            paintCachedSnapshot(mCurrentSectionId, true);
            if (mPresenter != null) {
                mPresenter.onSectionFocused(mCurrentSectionId);
            }
        } else if (mStrip.mode() == FeedStrip.MODE_CHIPS) {
            // A topic opens in Home's place: Home's tab stays lit (no tab of its own), Back = Home.
            mSectionFromYou = false;
            onSectionChosen((int) item.id);
        } else if (item.data instanceof Video) {
            // GUTUBE(issue #87): tapping the picked channel widens to All, like Back.
            if (mSubsChannel != null && item.id == mStrip.selected()) {
                onStripPicked(new FeedStrip.Item(-1, getString(R.string.mobile_feed_all), null, null));
                return;
            }
            mStrip.select(item.id);
            loadSubsChannel((Video) item.data);
        } else {
            // "All": the all-channels feed again, from its snapshot while the presenter refreshes.
            clearSubsChannel();
            mStrip.select(item.id);
            paintCachedSnapshot(MediaGroup.TYPE_SUBSCRIPTIONS, true);
            if (mPresenter != null) {
                mPresenter.onSectionFocused(MediaGroup.TYPE_SUBSCRIPTIONS);
            }
        }
    }

    // GUTUBE(issue #87): TV subscription tabs carry only a reload token; resolve their channel for the menu.
    private void onStripLongPressed(FeedStrip.Item item) {
        if (!(item.data instanceof Video) || mPresenter == null) {
            return;
        }
        Video channel = (Video) item.data;
        int request = ++mSubsMenuRequest;
        com.liskovsoft.sharedutils.rx.OnResult<String> openMenu = channelId -> runOnUiThread(() -> {
            if (channelId != null && request == mSubsMenuRequest && !isDestroyed()
                    && mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
                channel.channelId = channelId;
                // GUTUBE(issue #87): menu context for this channel; All's sheet keeps the other tabs' context.
                mSubsMenuGroup = VideoGroup.from(channel);
                mSubsMenuGroup.setType(MediaGroup.TYPE_SUBSCRIPTIONS);
                channel.setGroup(mSubsMenuGroup);
                onVideoLongClicked(channel);
            }
        });
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsMenuAction);
        if (channel.channelId != null) {
            openMenu.onResult(channel.channelId);
            return;
        }
        mSubsMenuAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService()
                        .getGroupObserve(channel.mediaItem),
                group -> {
                    if (group == null) {
                        return;
                    }
                    if (group.getChannelId() != null) {
                        openMenu.onResult(group.getChannelId());
                        return;
                    }
                    List<MediaItem> videos = group.getMediaItems();
                    // only a video by this channel ("Name • @handle"), not a collab's: another channel's id
                    // would aim Unsubscribe at it
                    String author = channel.getAuthor();
                    MediaItem first = author == null ? null : Helpers.findFirst(videos,
                            video -> author.equals(video.getAuthor()) || Helpers.startsWith(video.getAuthor(), author + " •"));
                    if (first != null) {
                        if (first.getChannelId() != null) {
                            openMenu.onResult(first.getChannelId());
                        } else {
                            MediaServiceManager.instance().loadMetadata(first,
                                    metadata -> openMenu.onResult(metadata.getChannelId()));
                        }
                    }
                },
                error -> android.util.Log.w("MobileBrowse", "Channel menu: " + error));
    }

    private void loadSubsChannels() {
        if (com.liskovsoft.sharedutils.rx.RxHelper.isAnyActionRunning(mSubsChannelsAction)) {
            return;
        }
        mSubsChannelsAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService()
                        .getSubscribedChannelsObserve(),
                group -> runOnUiThread(() -> {
                    mSubsChannelsGroup = VideoGroup.from(group);
                    List<Video> channels = mSubsChannelsGroup.getVideos();
                    mSubsChannels = channels != null ? new ArrayList<>(channels) : new ArrayList<>();
                    for (Video channel : mSubsChannels) { // GUTUBE(card avatars): the feed cards' avatars
                        channel.itemType = MediaItem.TYPE_CHANNEL; // GUTUBE(issue #87): these are subscribed channels.
                        channel.isSubscribed = true;
                        com.newtube.mobile.ui.common.ChannelAvatars.put(this, channel.channelId,
                                Helpers.firstNonNull(channel.getAuthor(), channel.getTitle()), channel.getCardImageUrl());
                    }
                    if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS) {
                        updateStrip();
                    }
                }),
                error -> android.util.Log.w("MobileBrowse", "Subscribed channels: " + error));
    }

    private void loadHomeChips() {
        if (mHomeChips == null) {
            mHomeChips = new ArrayList<>(); // asked once per visit (refresh asks again); none until it answers
        }
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mHomeChipsAction);
        mHomeChipsAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().getHistoryObserve(),
                group -> runOnUiThread(() -> {
                    List<Video> chips = topChannels(VideoGroup.from(group).getVideos(), HOME_CHIPS_MAX);
                    if (sameChannels(chips, mHomeChips)) {
                        return; // the strip stays as it is
                    }
                    mHomeChips = chips;
                    if (mHomeChip == null && isHomeChip(mCurrentSectionId) && !mYouShowing) {
                        updateStrip();
                    }
                }),
                error -> android.util.Log.w("MobileBrowse", "Home chips: " + error));
    }

    private static boolean sameChannels(List<Video> a, @Nullable List<Video> b) {
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Objects.equals(a.get(i).channelId, b.get(i).channelId)) {
                return false;
            }
        }
        return true;
    }

    /** The {@code max} channels with the most videos in {@code history}; a tie goes to the newer one. */
    static List<Video> topChannels(@Nullable List<Video> history, int max) {
        java.util.Map<String, Video> newest = new java.util.LinkedHashMap<>(); // history comes newest first
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (Video video : history != null ? history : new ArrayList<Video>()) {
            if (video.channelId != null && video.getAuthor() != null) {
                newest.putIfAbsent(video.channelId, video);
                count.merge(video.channelId, 1, Integer::sum);
            }
        }
        List<Video> channels = new ArrayList<>(newest.values());
        java.util.Collections.sort(channels, (a, b) -> count.get(b.channelId) - count.get(a.channelId)); // stable
        return new ArrayList<>(channels.subList(0, Math.min(max, channels.size())));
    }

    /** GUTUBE(issue #79): Home narrowed to a personal chip: that channel's uploads replace Home's feed. */
    private void loadHomeChip(Video chip) {
        loadSubsChannel(chip);
        mHomeChip = chip; // after: the filter's reset clears it
    }

    /** Subscriptions narrowed to one channel: its uploads replace the feed, newest first. */
    private void loadSubsChannel(Video channel) {
        resetFilteredFeed(channel);
        Video target = channel;
        mSubsAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                target.mediaItem != null
                        ? com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().getGroupObserve(target.mediaItem)
                        : com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().getGroupObserve(target.getReloadPageKey()),
                group -> runOnUiThread(() -> onSubsChannelPage(target, group)),
                error -> runOnUiThread(() -> {
                    if (mSubsChannel == target) {
                        setSkeletonVisible(false);
                        android.util.Log.w("MobileBrowse", "Channel feed: " + error);
                    }
                }));
    }

    /** An empty feed with its skeleton, waiting for {@code target}'s first page. */
    private void resetFilteredFeed(Video target) {
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsAction);
        mSubsChannel = target;
        mSubsChannelPage = null;
        mHomeChip = null;
        hideError();
        mCurrentVideos.clear();
        mAwaitingFreshContent = false;
        cancelFeedSwap();
        mAdapter.submitList(new ArrayList<>());
        showReloadSkeleton();
        mContentSwipe.setRefreshing(false);
    }

    private void loadSubsChannelPage() {
        MediaGroup page = mSubsChannelPage;
        Video target = mSubsChannel;
        if (page == null || target == null || page.getNextPageKey() == null
                || com.liskovsoft.sharedutils.rx.RxHelper.isAnyActionRunning(mSubsAction)) {
            return;
        }
        mSubsChannelPage = null; // one request per page
        mSubsAction = com.liskovsoft.sharedutils.rx.RxHelper.execute(
                com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().getContentService().continueGroupObserve(page),
                group -> runOnUiThread(() -> onSubsChannelPage(target, group)),
                error -> runOnUiThread(() -> {
                    if (mSubsChannel == target) {
                        setSkeletonVisible(false);
                    }
                    android.util.Log.w("MobileBrowse", "Channel feed page: " + error);
                }));
    }

    private void onSubsChannelPage(Video target, MediaGroup group) {
        if (mSubsChannel != target || isDestroyed()) {
            return;
        }
        mSubsChannelPage = group;
        List<Video> videos = group != null ? VideoGroup.from(group).getVideos() : null;
        appendNew(videos);
        submitGrid();
        setSkeletonVisible(false); // an empty channel feed stays an empty grid: the strip keeps "All" in reach
    }

    private void clearSubsChannel() {
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsAction);
        mSubsChannel = null;
        mSubsChannelPage = null;
        mHomeChip = null;
    }

    private void maybeTriggerPagination() {
        if (mCurrentVideos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = lastVisibleCard();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible < 0 || itemCount == 0) {
            return;
        }

        if (mSubsChannel != null) {
            // GUTUBE(feed-strip): one channel's feed pages itself, not through the presenter.
            if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS) {
                loadSubsChannelPage();
            }
            return;
        }

        if (com.newtube.mobile.ui.common.FeedRunway.isShort(lastVisible, itemCount, NEAR_END_LOOKAHEAD_ITEMS)
                && itemCount != mLastNearEndTriggerCount) {
            mLastNearEndTriggerCount = itemCount;
            mPresenter.onScrollNearEnd(mCurrentVideos.size());
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(mCurrentVideos.get(mCurrentVideos.size() - 1));
        }
    }

    /**
     * NEWTUBE(lazy-home): after every grid update, ask for more when fewer than a screen plus
     * {@link #NEAR_END_LOOKAHEAD_ITEMS} cards are left below the viewport - including an EMPTY grid.
     * The scroll listener alone cannot cover this: a page whose rows were all filtered out (Shorts,
     * channel shelves, duplicates) leaves the grid unchanged, so nothing scrolls and no new size is
     * ever reported, and the walk would sit waiting for a demand while usable sections exist.
     * Home's paced walk acts on it, and once a row section's section list is done, its shelf tail
     * (the next shelf page; the grid size tells it whether the last page added any card - see
     * BrowsePresenter.onScrollNearEnd).
     */
    private void checkFeedRunway() {
        // Posted, and once per burst: a page arrives as one updateSection per shelf, all inside
        // one main-thread message, and judging the grid after its FIRST shelf would ask for a
        // page the rest of this one is about to make unnecessary.
        if (!mRunwayCheckPosted) {
            mRunwayCheckPosted = true;
            mNavHandler.post(mRunwayCheck);
        }
    }

    private boolean mRunwayCheckPosted;
    private final Runnable mRunwayCheck = () -> {
        mRunwayCheckPosted = false;
        if (isDestroyed() || mPresenter == null || mYouShowing || isDownloadsSectionShowing()
                || mSubsChannel != null) {
            return;
        }
        // Before the new list is laid out this is the old layout's last card (or NO_POSITION = -1
        // for an empty grid): a lower bound of what will be visible, which is what the check needs.
        int lastVisible = mLayoutManager.findLastVisibleItemPosition() == RecyclerView.NO_POSITION
                ? RecyclerView.NO_POSITION : lastVisibleCard();
        if (com.newtube.mobile.ui.common.FeedRunway.isShort(lastVisible, mCurrentVideos.size(), NEAR_END_LOOKAHEAD_ITEMS)) {
            mPresenter.onScrollNearEnd(mCurrentVideos.size());
        }
    };

    private int computeSpanCount() {
        return computeSpanCount(com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this));
    }

    /** GUTUBE(shorts): the Shorts tab is a grid of tall 9:16 cards, two to a phone row. */
    private int computeSpanCount(int span) {
        return mCurrentSectionId == MediaGroup.TYPE_SHORTS ? span * 2 : span;
    }

    private static int toMenuItemId(int sectionId) {
        return sectionId + ITEM_ID_OFFSET;
    }

    /**
     * Rebuild both nav surfaces (bottom nav = the main tabs + You; You panel = the rest).
     *
     * <p>NEWTUBE(startup): coalesced. BrowsePresenter.refreshSections() reports the sections one
     * by one - removeAllSections, then an addSection/removeSection per pinned section (~16 on a
     * default install) - all inside this Activity's onCreate, and the bar was rebuilt for each of
     * them. Material's NavigationBarMenuView rebuilds every tab view on each menu.add() and
     * discards its item-view pool whenever the item count changes, so one clear-and-refill of k
     * tabs inflates 1+2+...+k NavigationBarItemViews: 89 per cold start (Robolectric count of the
     * default section order) against 15 for a single rebuild. Now a burst of section
     * changes posts ONE rebuild with the final section list; onCreate flushes it synchronously
     * once the presenter has reported its sections, so the first frame has its tabs.</p>
     */
    private void rebuildNavigation() {
        if (mNavRebuildPosted) {
            return;
        }
        mNavRebuildPosted = true;
        mNavHandler.post(mNavRebuild);
    }

    /** Run a pending coalesced rebuild now instead of on the next loop. */
    private void flushNavRebuild() {
        if (mNavRebuildPosted) {
            mNavHandler.removeCallbacks(mNavRebuild);
            mNavRebuild.run();
        }
    }

    private final android.os.Handler mNavHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mNavRebuildPosted;
    private final Runnable mNavRebuild = () -> {
        mNavRebuildPosted = false;
        if (isDestroyed()) {
            return;
        }
        rebuildBottomNav();
        updateTopBarActions();
        if (mYouShowing) {
            rebuildYouRows();
        }
        openPendingDownloads();
        openPendingShortcutSection();
    };

    /**
     * What the bottom nav currently shows (ids, titles, icons, in order), so an unchanged section
     * list - e.g. BrowsePresenter.updateSections() after a profile/account refresh - does not
     * tear down and re-inflate the whole bar.
     */
    private String mNavSignature;

    // ---------------------------------------------------------------------------------
    // Downloads section
    // ---------------------------------------------------------------------------------

    /** A notification tap asked for the Downloads tab before the sections had arrived. */
    private boolean mPendingDownloadsOpen;

    private final DownloadRegistry.Listener mDownloadsListener = this::onDownloadsChanged;

    private void handleOpenDownloads(Intent intent) {
        if (intent == null || !ACTION_OPEN_DOWNLOADS.equals(intent.getAction())) {
            return;
        }
        intent.setAction(null); // consume: a rotation must not re-open the tab
        mPendingDownloadsOpen = true;
        openPendingDownloads();
    }

    private int mPendingShortcutSection = -1;

    private void handleShortcutIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_SEARCH.equals(action)) {
            intent.setAction(null);
            if (mBottomNav != null) {
                mBottomNav.post(() -> SearchPresenter.instance(this).startTextSearch());
            }
        } else if (ACTION_SHORTS.equals(action)) {
            intent.setAction(null);
            openShortcutSection(MediaGroup.TYPE_SHORTS);
        } else if (ACTION_SUBSCRIPTIONS.equals(action)) {
            intent.setAction(null);
            openShortcutSection(MediaGroup.TYPE_SUBSCRIPTIONS);
        } else if (ACTION_HISTORY.equals(action)) {
            intent.setAction(null);
            openShortcutSection(MediaGroup.TYPE_HISTORY);
        }
    }

    private void openShortcutSection(int sectionId) {
        hideYouPanel();
        int menuItemId = toMenuItemId(sectionId);
        if (mBottomNav != null && mBottomNav.getMenu().findItem(menuItemId) != null) {
            mBottomNav.setSelectedItemId(menuItemId);
        } else {
            mPendingShortcutSection = sectionId;
            openPendingShortcutSection();
        }
    }

    private void openPendingShortcutSection() {
        if (mPendingShortcutSection < 0) {
            return;
        }
        for (BrowseSection section : mSections) {
            if (section.getId() == mPendingShortcutSection) {
                int id = mPendingShortcutSection;
                mPendingShortcutSection = -1;
                hideYouPanel();
                int menuItemId = toMenuItemId(id);
                if (mBottomNav != null && mBottomNav.getMenu().findItem(menuItemId) != null) {
                    mBottomNav.setSelectedItemId(menuItemId);
                } else {
                    onSectionChosen(id);
                }
                return;
            }
        }
    }

    private void openPendingDownloads() {
        if (!mPendingDownloadsOpen) {
            return;
        }
        for (BrowseSection section : mSections) {
            if (section.getId() == VideoDownloads.SECTION_ID) {
                mPendingDownloadsOpen = false;
                hideYouPanel();
                onSectionChosen(VideoDownloads.SECTION_ID);
                return;
            }
        }
    }

    private boolean isDownloadsSectionShowing() {
        return mCurrentSectionId == VideoDownloads.SECTION_ID && !mYouShowing;
    }

    /**
     * Registry change while the Downloads grid is on screen. Progress on the cards already
     * shown is an in-place sync (payloaded partial rebind, no thumbnail reload, no blink); a
     * download appearing or leaving the list needs the presenter to rebuild the grid.
     */
    private void onDownloadsChanged() {
        if (!isDownloadsSectionShowing() || mPresenter == null) {
            return;
        }

        List<DownloadItem> items = DownloadRegistry.instance(this).itemsNewestFirst();
        boolean sameSet = items.size() == mCurrentVideos.size();
        if (sameSet) {
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).id.equals(mCurrentVideos.get(i).downloadId)) {
                    sameSet = false;
                    break;
                }
            }
        }

        if (sameSet) {
            VideoGroup sync = VideoGroup.from(DownloadsBridge.instance(this).syncCards());
            sync.setAction(VideoGroup.ACTION_SYNC);
            updateSection(sync);
        } else {
            mPresenter.refresh(false);
        }
    }

    /** The Downloads tab with nothing in it: say what the tab is for instead of a blank grid. */
    private void showEmptyAfterRemoval() {
        setSkeletonVisible(false);
        mContentSwipe.setRefreshing(false);
        mContentGrid.setVisibility(View.GONE);
        mErrorContainer.setVisibility(View.VISIBLE);
        mErrorIcon.setVisibility(View.VISIBLE);
        mErrorMessage.setText(mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS
                ? R.string.mobile_empty_subscriptions : R.string.mobile_empty_generic);
        mErrorAction.setVisibility(View.GONE);
        mErrorAction.setTag(null);
    }

    private void showDownloadsEmptyState() {
        setSkeletonVisible(false);
        mContentSwipe.setRefreshing(false);
        mContentGrid.setVisibility(View.GONE);
        mErrorContainer.setVisibility(View.VISIBLE);
        mErrorIcon.setVisibility(View.VISIBLE);
        mErrorMessage.setText(R.string.mobile_downloads_empty);
        mErrorAction.setVisibility(View.GONE);
        mErrorAction.setTag(null);
    }

    private void rebuildBottomNav() {
        Menu menu = mBottomNav.getMenu();
        List<Integer> bottomItems = NavTabs.getBottomBarItems(this);
        List<BrowseSection> enabledSections = new ArrayList<>();
        for (BrowseSection section : mSections) {
            if (section.isEnabled() && section.getId() != MediaGroup.TYPE_SETTINGS) {
                enabledSections.add(section);
            }
        }

        StringBuilder signature = new StringBuilder();
        for (int item : bottomItems) {
            signature.append(item).append(',');
        }
        signature.append(NavTabs.showHome(this)).append(';');
        for (BrowseSection s : enabledSections) {
            signature.append(s.getId()).append(':').append(s.getTitle()).append(':').append(s.getResId()).append(';');
        }
        String newSignature = signature.toString();

        int expectedCount = 0;
        for (int id : bottomItems) {
            if (id == NavTabs.NONE || (id == MediaGroup.TYPE_HOME && !NavTabs.showHome(this))) continue;
            expectedCount++;
            if (expectedCount == MAX_NAV_ITEMS) break;
        }

        if (newSignature.equals(mNavSignature) && menu.size() == expectedCount) {
            // Same tabs as on screen: only re-assert the highlight (the section may have moved).
            mSuppressNavCallback = true;
            reassertNavHighlight(menu, false);
            mSuppressNavCallback = false;
            return;
        }
        mNavSignature = newSignature;

        mSuppressNavCallback = true;

        menu.clear();

        int order = 0;
        for (int itemId : bottomItems) {
            if (menu.size() >= MAX_NAV_ITEMS) {
                break;
            }
            if (itemId == NavTabs.NONE) {
                continue;
            }
            if (itemId == MediaGroup.TYPE_HOME && !NavTabs.showHome(this)) {
                continue;
            }
            if (itemId == NavTabs.ITEM_SEARCH) {
                menu.add(Menu.NONE, SEARCH_ITEM_ID, order++, R.string.mobile_search_button)
                        .setIcon(R.drawable.ic_mobile_search);
            } else if (itemId == NavTabs.ITEM_CAST) {
                menu.add(Menu.NONE, CAST_ITEM_ID, order++, R.string.mobile_cast_button)
                        .setIcon(R.drawable.ic_mobile_cast);
            } else if (itemId == NavTabs.ITEM_YOU) {
                menu.add(Menu.NONE, YOU_ITEM_ID, order++, R.string.mobile_nav_you)
                        .setIcon(R.drawable.ic_nav_you);
            } else {
                BrowseSection section = null;
                for (BrowseSection s : enabledSections) {
                    if (s.getId() == itemId) {
                        section = s;
                        break;
                    }
                }
                CharSequence title;
                int iconRes;
                if (section != null) {
                    title = section.getId() == MediaGroup.TYPE_SUBSCRIPTIONS
                            ? getString(R.string.mobile_nav_subs) : section.getTitle();
                    iconRes = navIconOrResFor(section);
                } else {
                    title = defaultSectionTitle(itemId);
                    iconRes = navIconFor(itemId);
                }
                if (title != null) {
                    android.view.MenuItem item = menu.add(Menu.NONE, toMenuItemId(itemId), order++, title);
                    if (iconRes > 0) {
                        item.setIcon(iconRes);
                    }
                }
            }
        }

        // Re-assert the highlight after clear()/add() wiped it, so the current tab stays lit.
        reassertNavHighlight(menu, true);

        mSuppressNavCallback = false;

        refreshUpdateBadge();

        mBottomNav.post(() -> {
            for (int i = 0; i < mBottomNav.getMenu().size(); i++) {
                int id = mBottomNav.getMenu().getItem(i).getItemId();
                View itemView = mBottomNav.findViewById(id);
                if (itemView != null) {
                    fitNavLabel(itemView);
                    itemView.setOnLongClickListener(v -> showNavHint(v, id));
                }
            }
            com.newtube.mobile.ui.common.NavSpringPhysics.attachToChildren(mBottomNav);
        });
    }

    private CharSequence defaultSectionTitle(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_HOME:
                return getString(R.string.header_home);
            case MediaGroup.TYPE_SHORTS:
                return getString(R.string.header_shorts);
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return getString(R.string.mobile_nav_subs);
            case VideoDownloads.SECTION_ID:
                return getString(R.string.header_downloads);
            case MediaGroup.TYPE_HISTORY:
                return getString(R.string.header_history);
            case MediaGroup.TYPE_TRENDING:
                return getString(R.string.header_trending);
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return getString(R.string.mobile_settings_menu_playlists);
            default:
                return null;
        }
    }

    /**
     * GUTUBE(nav tabs): a label too long for its fifth of the bar ("Subscriptions") gets a smaller
     * text size instead of ending in "...". Both labels (idle, selected) share one style.
     */
    private static void fitNavLabel(View itemView) {
        if (itemView.getWidth() == 0) {
            itemView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    v.post(() -> fitNavLabel(v));
                }
            });
            return;
        }
        float room = itemView.getWidth() - itemView.getPaddingLeft() - itemView.getPaddingRight()
                - 8 * itemView.getResources().getDisplayMetrics().density;
        for (int id : new int[] {com.google.android.material.R.id.navigation_bar_item_small_label_view,
                com.google.android.material.R.id.navigation_bar_item_large_label_view}) {
            TextView label = itemView.findViewById(id);
            if (label == null || label.getText() == null) {
                continue;
            }
            float width = label.getPaint().measureText(label.getText().toString());
            if (width > room && room > 0) {
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, label.getTextSize() * room / width);
            }
        }
    }

    /** Callers hold {@link #mSuppressNavCallback}. {@code force}: the menu was just rebuilt. */
    private void reassertNavHighlight(Menu menu, boolean force) {
        int itemId;
        if (mYouShowing) {
            itemId = menu.findItem(YOU_ITEM_ID) != null ? YOU_ITEM_ID : -1;
        } else if (mCurrentSectionId >= 0 && menu.findItem(toMenuItemId(mCurrentSectionId)) != null) {
            itemId = toMenuItemId(mCurrentSectionId);
        } else if (mSectionFromYou) {
            itemId = menu.findItem(YOU_ITEM_ID) != null ? YOU_ITEM_ID : -1; // GUTUBE(issue #179): a You sub-screen (History...); clear()/add() lit the first tab
        } else {
            return;
        }
        if (itemId >= 0 && (force || mBottomNav.getSelectedItemId() != itemId)) {
            mBottomNav.setSelectedItemId(itemId);
        }
    }

    /** The icon a nav tab shows for this section: its outline/filled pair, else the stock icon. */
    private static int navIconOrResFor(BrowseSection section) {
        int navIcon = navIconFor(section.getId());
        return navIcon != 0 ? navIcon : Math.max(section.getResId(), 0);
    }

    /**
     * State-list icons (outline when idle, filled when selected - the YouTube bar's
     * active-tab signal, since both states tint plain white) for the curated bottom-nav
     * sections. Sections without a pair (drawer-only ones) keep their stock
     * {@link BrowseSection#getResId()} icon.
     */
    private static int navIconFor(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_HOME:
                return R.drawable.ic_nav_home;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.drawable.ic_nav_subscriptions;
            case MediaGroup.TYPE_HISTORY:
                return R.drawable.ic_nav_history;
            case MediaGroup.TYPE_SHORTS:
                return R.drawable.ic_nav_shorts;
            case VideoDownloads.SECTION_ID:
                return R.drawable.ic_nav_downloads;
            case MediaGroup.TYPE_TRENDING:
                return R.drawable.ic_nav_hyped;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.drawable.ic_menu_folder;
            default:
                return 0;
        }
    }

    /**
     * The bottom nav shows ONLY Home and the two {@link NavTabs} picks (when present and
     * enabled) — no backfill from the other delivered sections; everything else lives in the
     * You panel. BrowsePresenter can deliver
     * many more sections, and the {@link BottomNavigationView} itself hard-caps at
     * {@link #MAX_NAV_ITEMS}.
     */
    private List<BrowseSection> selectNavSections() {
        List<BrowseSection> enabledSections = new ArrayList<>();

        for (BrowseSection section : mSections) {
            if (section.isEnabled() && section.getId() != MediaGroup.TYPE_SETTINGS) {
                enabledSections.add(section);
            }
        }

        List<BrowseSection> chosen = new ArrayList<>();

        List<Integer> bottomItems = NavTabs.getBottomBarItems(this);
        for (int id : bottomItems) {
            if (id == NavTabs.ITEM_CAST || id == NavTabs.ITEM_SEARCH || id == NavTabs.ITEM_YOU || id == NavTabs.NONE) {
                continue;
            }
            if (id == MediaGroup.TYPE_HOME && !NavTabs.showHome(this)) {
                continue;
            }
            BrowseSection match = findPreferredSection(enabledSections, chosen, id,
                    id == MediaGroup.TYPE_HOME ? R.string.header_home : 0);

            if (match != null) {
                chosen.add(match);
            }
        }

        return chosen;
    }

    private void updateTopBarActions() {
        LinearLayout actions = findViewById(R.id.mobile_actions_glass);
        if (actions == null) {
            return;
        }
        List<Integer> topItems = NavTabs.getTopBarItems(this);
        actions.removeAllViews();
        if (topItems.isEmpty()) {
            actions.setVisibility(View.GONE);
            return;
        }
        int pad = (int) (11 * getResources().getDisplayMetrics().density);
        int size = (int) (44 * getResources().getDisplayMetrics().density);

        for (int item : topItems) {
            if (item == NavTabs.ITEM_CAST) {
                if (mCastButton == null) {
                    mCastButton = createTopBarButton(R.drawable.ic_mobile_cast, R.string.mobile_cast_button, size, pad);
                    mCastButton.setId(R.id.mobile_cast_button);
                    mCastButton.setOnClickListener(v -> CastPickerLauncher.open(this, CastPickerLauncher::presentPlain));
                }
                updateCastIconTint();
                if (mCastButton.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mCastButton.getParent()).removeView(mCastButton);
                }
                mCastButton.setVisibility(View.VISIBLE);
                actions.addView(mCastButton);
            } else if (item == NavTabs.ITEM_SEARCH) {
                if (mSearchButton == null) {
                    mSearchButton = createTopBarButton(R.drawable.ic_mobile_search, R.string.mobile_search_button, size, pad);
                    mSearchButton.setId(R.id.mobile_search_button);
                    mSearchButton.setOnClickListener(v -> SearchPresenter.instance(this).startTextSearch());
                }
                if (mSearchButton.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mSearchButton.getParent()).removeView(mSearchButton);
                }
                mSearchButton.setVisibility(View.VISIBLE);
                actions.addView(mSearchButton);
            } else if (item == NavTabs.ITEM_YOU) {
                ImageButton btn = createTopBarButton(R.drawable.ic_nav_you, R.string.mobile_nav_you, size, pad);
                btn.setOnClickListener(v -> onNavItemChosen(YOU_ITEM_ID));
                actions.addView(btn);
            } else if (item != NavTabs.NONE) {
                int icon = navIconFor(item);
                if (icon == 0) icon = R.drawable.ic_nav_home;
                int descRes = sectionTitleRes(item);
                ImageButton btn = createTopBarButton(icon, descRes, size, pad);
                int sectionItemId = toMenuItemId(item);
                btn.setOnClickListener(v -> onNavItemChosen(sectionItemId));
                actions.addView(btn);
            }
        }
        actions.setVisibility(actions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private int sectionTitleRes(int item) {
        switch (item) {
            case NavTabs.ITEM_CAST:
                return R.string.mobile_cast_button;
            case NavTabs.ITEM_SEARCH:
                return R.string.mobile_search_button;
            case NavTabs.ITEM_YOU:
                return R.string.mobile_nav_you;
            case MediaGroup.TYPE_HOME:
                return R.string.header_home;
            case MediaGroup.TYPE_SHORTS:
                return R.string.header_shorts;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.string.mobile_nav_subs;
            case VideoDownloads.SECTION_ID:
                return R.string.header_downloads;
            case MediaGroup.TYPE_HISTORY:
                return R.string.header_history;
            case MediaGroup.TYPE_TRENDING:
                return R.string.header_trending;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.string.mobile_settings_menu_playlists;
            default:
                return 0;
        }
    }

    private ImageButton createTopBarButton(int iconRes, int descRes, int size, int pad) {
        ImageButton btn = new ImageButton(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        btn.setLayoutParams(lp);
        btn.setPadding(pad, pad, pad, pad);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btn.setImageResource(iconRes);
        btn.setImageTintList(ContextCompat.getColorStateList(this, R.color.mobile_color_on_surface));
        if (descRes != 0) {
            btn.setContentDescription(getString(descRes));
        }
        return btn;
    }

    /** Matches primarily by section id; falls back to a title-string match ({@code titleResId} 0: none). */
    private BrowseSection findPreferredSection(
            List<BrowseSection> candidates, List<BrowseSection> alreadyChosen, int id, int titleResId) {
        String fallbackTitle = titleResId != 0 ? getString(titleResId) : null;

        for (BrowseSection section : candidates) {
            if (alreadyChosen.contains(section)) {
                continue;
            }

            if (section.getId() == id || (fallbackTitle != null && Helpers.equals(section.getTitle(), fallbackTitle))) {
                return section;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        com.newtube.mobile.update.GitHubAppUpdater.resumePendingInstall(this);
        mPreview.schedule();
        mTopBar.syncMark(this);
        mBottomNav.setLabelVisibilityMode(NavTabs.showLabels(this) // GUTUBE(nav labels)
                ? BottomNavigationView.LABEL_VISIBILITY_LABELED : BottomNavigationView.LABEL_VISIBILITY_UNLABELED);
        // GUTUBE(top bar actions): sync top bar action buttons from customization
        boolean searchOnTop = NavTabs.searchOnTop(this);
        updateTopBarActions();
        updateStrip();
        // GUTUBE(search fab): its corner, as Settings > Customization says.
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams fabParams =
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) mSearchFab.getLayoutParams();
        boolean fabLeft = NavTabs.searchFabLeft(this);
        if (fabLeft != (fabParams.startToStart == androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID)) {
            fabParams.startToStart = fabLeft ? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    : androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
            fabParams.endToEnd = fabLeft ? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                    : androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
            mSearchFab.setLayoutParams(fabParams);
        }
        if (searchOnTop && mSearchFabOut) {
            showNav(); // Search went up top: the bubble opens back into the bar
        }
        if (mMiniPip != null) {
            mMiniPip.onResume();
        }
        // GUTUBE(update pop-up): an update found while the player was in front asks now, once
        // Home has painted.
        mBottomNav.removeCallbacks(mPromptUpdate);
        mBottomNav.postDelayed(mPromptUpdate, UPDATED_NOTICE_DELAY_MS);

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
        // GUTUBE(nav tabs): the tabs may have been changed in Settings (a no-op when they weren't).
        rebuildNavigation();
        if (!NavTabs.showHome(this) && !mYouShowing && mCurrentSectionId == MediaGroup.TYPE_HOME) {
            mBottomNav.post(() -> { // GUTUBE(issue #213): Home was just removed in Settings; after the rebuild
                int landing = landingItemId();
                mSuppressNavCallback = true;
                mBottomNav.setSelectedItemId(landing);
                mSuppressNavCallback = false;
                onNavItemChosen(landing);
            });
        }

        // GUTUBE(shorts): back from the Shorts player - return to the tab before Shorts once the
        // player's close (player_close_exit, 200 ms) is over. Posted, not run: a resume while the
        // player is still opening is cancelled by the onPause that follows it.
        mBottomNav.removeCallbacks(mLeaveShortsTab);
        boolean shortsClosed = sShortsClosed;
        sShortsClosed = false;
        if (mCurrentSectionId == MediaGroup.TYPE_SHORTS && !mShortsLaunchPending && !mYouShowing) {
            if (shortsClosed) {
                mLeaveShortsTab.run();
            } else {
                mBottomNav.postDelayed(mLeaveShortsTab, 240);
            }
        } else {
            maybeLaunchShorts();
        }

        updateAccountRow();
        applyHomeLayout(); // GUTUBE(home-rows): back from Settings with another Home layout
        applyVideoLayout(); // GUTUBE(list layout, issues #82, #92)
        getWindow().getDecorView().postDelayed(mUpdateCheckIfDue, UPDATE_CHECK_DELAY_MS);
        // GUTUBE(background): not over a live mini player - the dialog would push it into PiP.
        if (!MiniPlayerBridge.isActive()) {
            com.newtube.mobile.ui.playback.BackgroundPlay.askOnce(this);
        }
        // Cheap re-sync; listener callbacks already cover changes while resumed.
        updateCastIconTint();
        // Last-resumed host wins: while this screen is (or is about to be) the one under the
        // player, minimize docks its card here.
        MiniPlayerBridge.registerMiniHost(this);
        MiniPlayerBridge.clearPendingCardFold(mMiniCardFold);
        syncMiniPlayer();
        if (mMiniButtonsFadePending) {
            mMiniButtonsFadePending = false;
            if (mMiniPlayerBar.getVisibility() == View.VISIBLE) {
                fadeInMiniButtons();
            } else {
                setMiniButtonsAlpha(1f);
            }
        }
        // Home may have been paused while the player crossed landscape/PiP configurations. Those
        // callbacks can arrive while DisplayMetrics still describe the player window, so always
        // reconcile the retained GridLayoutManager against Home's current configuration on resume.
        updateGridSpanCount(computeSpanCount());
    }

    /**
     * Launcher tap while the player floats in a pinned PiP task: expand the exact live player
     * instead of showing Browse under a stale PiP. Android may satisfy that tap by merely bringing
     * this existing Browse task to the front (no Splash onCreate/onNewIntent callback at all), so
     * a Browse lifecycle signal is the only reliable hook. It must be focus, NOT onResume: the
     * home-gesture auto-PiP briefly resumes Browse while the player is re-parented into its pinned
     * task (Android 16+), and restoring from there re-expands the player the instant it minimizes -
     * the app looked impossible to leave. Focus never lands on Browse during that hand-off (it goes
     * to the launcher), but a real reopen always gains it.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            SystemPipBridge.restoreFromLauncher(this);
        }
    }

    /**
     * You-tab account header: name + email + circular avatar when signed in (official-app
     * style), the "Sign in" entry otherwise.
     */
    private void updateAccountRow() {
        if (mYouAccountText == null) {
            return;
        }

        TextView signinLabel = findViewById(R.id.mobile_you_signin_label);
        Account account = MediaServiceManager.instance().getSelectedAccount();
        if (account != null) {
            stopTypewriter();
            String name = account.getName() != null ? account.getName() : account.getEmail();
            mYouAccountText.setText(name != null ? name : getString(R.string.settings_accounts));

            String email = account.getEmail();
            boolean showEmail = email != null && !email.equals(name);
            mYouAccountSub.setText(showEmail ? email : "");
            mYouAccountSub.setVisibility(showEmail ? View.VISIBLE : View.GONE);

            if (signinLabel != null) {
                signinLabel.setText(R.string.settings_accounts);
            }

            String avatarUrl = account.getAvatarImageUrl();
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                mYouAvatar.setImageTintList(null);
                com.bumptech.glide.Glide.with(this)
                        .load(avatarUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_you_avatar)
                        .into(mYouAvatar);
            } else {
                mYouAvatar.setImageTintList(null);
                mYouAvatar.setImageResource(R.drawable.ic_you_avatar);
            }
        } else {
            mYouAccountText.setText(R.string.mobile_you_welcome_back);
            mYouAccountSub.setVisibility(View.VISIBLE);
            startTypewriter();
            if (signinLabel != null) {
                signinLabel.setText(R.string.mobile_you_signin_now);
            }
            mYouAvatar.setImageResource(R.drawable.ic_you_signin_user);
            mYouAvatar.setImageTintList(ColorStateList.valueOf(Color.parseColor("#E6FFFFFF")));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTypewriter();
        mPreview.stop();
        mBottomNav.removeCallbacks(mLeaveShortsTab);

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }

        getWindow().getDecorView().removeCallbacks(mUpdateCheckIfDue);

        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video), and a paused
        // Browse must never hold a stale TextureView on the live player. Audio is unaffected.
        // NEWTUBE(motion): a docked card stays up, frozen on that last frame, until the player
        // covers it (see MiniPlayerBridge.setPendingCardFold) - hiding it here was a blink.
        if (mMiniPip != null && mMiniPip.isPinned()) {
            // GUTUBE(mini-pip): paused into PiP - the card IS the window now, keep it live.
        } else {
            finishMiniClose();
            detachMiniTexture();
            if (mMiniPlayerBar.getVisibility() == View.VISIBLE && MiniPlayerBridge.isActive()) {
                // GUTUBE(issue #99): where the card really is (it may have been dragged to another
                // corner): the expanding player grows from there, not from the home corner.
                int[] at = new int[2];
                mMiniPlayerBar.getLocationOnScreen(at);
                MiniPlayerBridge.setMiniBounds(new android.graphics.Rect(at[0], at[1],
                        at[0] + mMiniPlayerBar.getWidth(), at[1] + mMiniPlayerBar.getHeight()));
                MiniPlayerBridge.setPendingCardFold(mMiniCardFold);
            } else {
                hideMiniPlayer();
            }
        }

        // Tap-feedback spinner (see onVideoClicked): the destination screen is opening (or the
        // user left) - never keep it spinning under the returning grid.
        mContentSwipe.setRefreshing(false);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mMiniPip != null && !getViewManager().isNewViewPending()) {
            mMiniPip.onUserLeaveHint();
        }
    }

    @Override
    public void onPictureInPictureUiStateChanged(@androidx.annotation.NonNull android.app.PictureInPictureUiState state) {
        super.onPictureInPictureUiStateChanged(state);
        if (mMiniPip != null && Build.VERSION.SDK_INT >= 35) {
            mMiniPip.onTransitioningToPip(state.isTransitioningToPip());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (mMiniPip != null) {
            mMiniPip.onModeChanged(isInPictureInPictureMode);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPip != null) {
            mMiniPip.onStop();
        }
        // Covered: a card still waiting for the player to fold it can go now.
        if (mMiniTexture == null) {
            if (mMiniSwipe != null && mMiniPlayerBar.getVisibility() == View.VISIBLE
                    && MiniPlayerBridge.isActive()) {
                // The app went away with the card still docked (not expanded): it comes back
                // where it was dragged to, not at home.
                mMiniPlace = mMiniSwipe.place();
                mMiniPlaceSession = MiniPlayerBridge.session();
            }
            hideMiniPlayer();
        }

        // Snapshot the feeds to disk so the NEXT cold start paints cards instantly. onStop fires
        // once per backgrounding — the last reliable moment before most process deaths.
        FeedCache.persist();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenDownloads(intent);
        handleShortcutIntent(intent);
        int tab = intent.getIntExtra(EXTRA_NAV_TAB, 0);
        if (tab != 0 && mBottomNav.getMenu().findItem(tab) != null) {
            mBottomNav.setSelectedItemId(tab);
        }
    }

    /** GUTUBE(search nav): the open Browse, whose tabs another screen's bar copies. */
    private static java.lang.ref.WeakReference<MobileBrowseActivity> sLive = new java.lang.ref.WeakReference<>(null);
    private static final String EXTRA_NAV_TAB = "gutube_nav_tab";

    /**
     * GUTUBE(search nav): fills {@code bar} with Browse's tabs (Search lit) and wires a tap on one
     * to go back to Browse on that tab. False when no Browse is open: the screen keeps no bar.
     */
    public static boolean copyNavTabs(android.app.Activity screen, BottomNavigationView bar, Runnable onSearchReselected) {
        return copyNavTabs(screen, bar, true, onSearchReselected);
    }

    /**
     * {@code searchLit} false (a channel page): the tab Browse is on stays lit and Search is a tab
     * like the others, opening Search back in Browse.
     */
    public static boolean copyNavTabs(android.app.Activity screen, BottomNavigationView bar, boolean searchLit,
                                      Runnable onSearchReselected) {
        MobileBrowseActivity browse = sLive.get();
        if (browse == null || browse.isFinishing()) {
            return false;
        }
        Menu source = browse.mBottomNav.getMenu();
        Menu menu = bar.getMenu();
        menu.clear();
        for (int i = 0; i < source.size(); i++) {
            android.view.MenuItem item = source.getItem(i);
            android.graphics.drawable.Drawable icon = item.getIcon();
            if (icon != null && icon.getConstantState() != null) {
                icon = icon.getConstantState().newDrawable(screen.getResources()).mutate();
            }
            menu.add(Menu.NONE, item.getItemId(), item.getOrder(), item.getTitle()).setIcon(icon);
        }
        bar.setLabelVisibilityMode(browse.mBottomNav.getLabelVisibilityMode());
        com.newtube.mobile.ui.common.NavSpringPhysics.attach(bar);
        NavBloom.attach(bar);
        // Search in the top bar (GUTUBE(search place)): the tab Browse is on stays lit, and a tap on it goes back too.
        boolean hasSearch = searchLit && source.findItem(SEARCH_ITEM_ID) != null;
        bar.setSelectedItemId(hasSearch ? SEARCH_ITEM_ID : browse.mBottomNav.getSelectedItemId());
        com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener goBack = item -> {
            Object playback = PlaybackPresenter.instance(screen).getPlayer();
            Activity player = playback instanceof Activity ? (Activity) playback : null;
            boolean keepPlayer = player != null && !player.isFinishing() && !player.isDestroyed()
                    && player.getTaskId() == screen.getTaskId();
            // GUTUBE(issue #171): CLEAR_TOP also destroys the docked player between Browse and this screen.
            screen.startActivity(new Intent(screen, MobileBrowseActivity.class)
                    .addFlags((keepPlayer ? Intent.FLAG_ACTIVITY_REORDER_TO_FRONT : Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_NAV_TAB, item.getItemId()));
            if (keepPlayer) {
                // GUTUBE(issue #171): close the other pages too (a channel under Search), and their stack entries.
                for (MobileActivity open : ThemeMode.liveScreens()) {
                    if (open != browse && open != player && open.getTaskId() == screen.getTaskId()
                            && !open.isFinishing() && !open.isDestroyed()) {
                        open.finishReally(false);
                    }
                }
            }
            return false; // Search stays lit while this screen closes
        };
        bar.setOnItemSelectedListener(goBack);
        bar.setOnItemReselectedListener(item -> {
            if (hasSearch) {
                onSearchReselected.run();
            } else {
                goBack.onNavigationItemSelected(item);
            }
        });
        return true;
    }

    @Override
    protected void onDestroy() {
        stopTypewriter();
        unwatchMiniScreenOn(); // the shared player outlives this Activity
        mPreview.release();
        mNavHandler.removeCallbacks(mNavRebuild);
        mNavHandler.removeCallbacks(mRunwayCheck);
        com.liskovsoft.sharedutils.rx.RxHelper.disposeActions(mSubsAction, mSubsChannelsAction, mYouHistoryAction,
                mHomeChipsAction, mSubsMenuAction);
        cancelFeedSwap(); // NEWTUBE(feed-swap)
        mContentGrid.removeCallbacks(mRestoreItemAnimator);
        MiniPlayerBridge.unregisterMiniHost(this);
        DownloadRegistry.instance(this).removeListener(mDownloadsListener);
        AppUpdates.instance(this).removeListener(mUpdatesListener);
        mBottomNav.removeCallbacks(mAnnounceUpdateInstalled);

        // Stop observing the cast session; the session itself outlives this screen by design.
        if (mCastSessionManager != null) {
            mCastSessionManager.removeListener(mCastListener);
        }

        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // GUTUBE(fullscreen): the fullscreen player turns Home with it, under its window. Following
        // it there re-laid the grid twice out of sight (two columns, then one), so Home came back
        // scrolled elsewhere with its cards rebinding as the player minimized. onResume squares
        // the grid with Home's own configuration when Home is next on screen. Once Home is
        // resumed it is on screen: minimizing from landscape delivers portrait here after
        // onResume but before the player stops, and skipping it then left two columns.
        if (com.newtube.mobile.ui.playback.MobilePlaybackActivity.isCoveringScreens()
                && !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            return;
        }
        updateGridSpanCount(computeSpanCount(com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig)));
    }

    // NEWTUBE(ui-mode): a recreated Browse (any config change the manifest doesn't absorb, or
    // process restore) got its section back from the presenter, but BottomNavigationView restored
    // its own "You" highlight over that feed and the You panel itself was lost. Carry the panel
    // state across; the presenter still owns which section is current.
    private static final String STATE_YOU_SHOWING = "newtube:you_showing";
    private static final String STATE_SECTION_FROM_YOU = "newtube:section_from_you";
    private static final String STATE_SECTION_ID = "newtube:section_id";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_YOU_SHOWING, mYouShowing);
        outState.putBoolean(STATE_SECTION_FROM_YOU, mSectionFromYou);
        outState.putInt(STATE_SECTION_ID, mCurrentSectionId);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState); // restores the nav's own highlight

        if (savedInstanceState.getBoolean(STATE_YOU_SHOWING)) {
            showYouPanel();
            mSuppressNavCallback = true;
            mBottomNav.setSelectedItemId(YOU_ITEM_ID);
            mSuppressNavCallback = false;
        } else {
            // "Opened from You" belongs to the section it was saved with. After a process restore
            // the presenter reselects the boot section (usually Home): with the flag carried over,
            // Back on Home would open You instead of leaving.
            mSectionFromYou = savedInstanceState.getBoolean(STATE_SECTION_FROM_YOU)
                    && savedInstanceState.getInt(STATE_SECTION_ID, -1) == mCurrentSectionId;
            // A section with its own tab re-lights that tab; one opened from a You row has none,
            // so the restored You highlight is the right one to keep.
            syncNavHighlight(mCurrentSectionId);
            if (!mSectionFromYou && mBottomNav.getMenu().findItem(toMenuItemId(mCurrentSectionId)) == null) {
                // A section without a tab that is no longer a You sub-screen: nothing may stay lit
                // on You over it; fall back to Home's tab like a fresh start.
                syncNavHighlight(MediaGroup.TYPE_HOME);
            }
            onYouPanelToggled();
        }
    }

    private void updateGridSpanCount(int spanCount) {
        if (mLayoutManager != null && mLayoutManager.getSpanCount() != spanCount) {
            mLayoutManager.setSpanCount(spanCount);
        }
    }

    private void handleBack() {
        // GUTUBE(tv): Back from the page goes to the bottom bar first - a remote has no other way
        // down to it (Down keeps scrolling the feed). From the bar, Back does what it always did.
        if (com.newtube.mobile.ui.common.FocusRing.isTv(this) && !mBottomNav.hasFocus()) {
            View tab = mBottomNav.findViewById(mBottomNav.getSelectedItemId());
            if (tab != null && tab.requestFocus()) {
                return;
            }
        }
        // A grid section opened from a You-panel row: back returns to the panel (its natural
        // parent surface), like YouTube's You-tab sub-screens.
        if (mSectionFromYou && !mYouShowing) {
            showYouPanel();
            return;
        }
        // GUTUBE(issue #86): Subscriptions narrowed to one channel - Back widens it to All first.
        if (!mYouShowing && mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS && mSubsChannel != null
                && mStrip != null && mStrip.mode() == FeedStrip.MODE_AVATARS) {
            onStripPicked(new FeedStrip.Item(-1, getString(R.string.mobile_feed_all), null, null));
            return;
        }
        // GUTUBE(issue #79): Home narrowed to a chip - Back goes to All first, like a channel filter.
        if (!mYouShowing && mHomeChip != null && mStrip != null && mStrip.mode() == FeedStrip.MODE_CHIPS) {
            onStripPicked(new FeedStrip.Item(mCurrentSectionId, getString(R.string.mobile_feed_all), null, null));
            return;
        }

        // On the You tab itself, back goes Home (YouTube behavior) instead of exiting.
        // NEWTUBE(back-home): so does back on any other tab (Subscriptions, History, Downloads) -
        // it used to exit the app from there, dropping the mini-player and the Home scroll. Only
        // Home itself exits.
        // GUTUBE(issue #213): with Home removed, the first tab (or You) is where Back lands.
        int landing = landingItemId();
        boolean onLanding = landing == YOU_ITEM_ID ? mYouShowing
                : !mYouShowing && toMenuItemId(mCurrentSectionId) == landing;
        if (!onLanding && mBottomNav.getMenu().findItem(landing) != null) {
            mBottomNav.setSelectedItemId(landing); // listener hides the panel + loads the tab
            return;
        }

        finish();
    }

    /**
     * GUTUBE(issue #213): the tab the app opens on and Back returns to - Home, or with Home
     * removed the first bar tab that is a feed (Shorts is a player), else You.
     */
    private int landingItemId() {
        int id = NavTabs.landingSectionId(this);
        return id == NavTabs.NONE ? YOU_ITEM_ID : toMenuItemId(id);
    }

    // ---------------------------------------------------------------------------------
    // BrowseView
    // ---------------------------------------------------------------------------------

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        runOnUiThread(() -> {
            Helpers.removeIf(mSections, existing -> existing.getId() == section.getId());

            if (index < 0 || index > mSections.size()) {
                mSections.add(section);
            } else {
                mSections.add(index, section);
            }

            rebuildNavigation();
            updateStrip(); // GUTUBE(feed-strip): a topic section arriving adds its chip
        });
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        runOnUiThread(() -> {
            Helpers.removeIf(mSections, existing -> existing.getId() == section.getId());
            rebuildNavigation();
        });
    }

    @Override
    public void removeAllSections() {
        runOnUiThread(() -> {
            mSections.clear();
            rebuildNavigation();
        });
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        runOnUiThread(() -> {
            if (index < 0 || index >= mSections.size()) {
                return;
            }

            // GUTUBE(you order): a presenter refresh after Settings or a section move keeps You open.
            if (mYouShowing && !focusOnContent) {
                rebuildNavigation();
                return;
            }

            BrowseSection section = mSections.get(index);

            // Same guard as onSectionChosen(): Settings has no video-grid renderer on the mobile
            // shell (its content arrives as a SettingsGroup, which updateSection(SettingsGroup)
            // ignores). If the presenter's boot/fallback selection lands on Settings, route it to
            // the working AppDialog settings screen instead of driving an empty grid.
            if (section.getId() == MediaGroup.TYPE_SETTINGS) {
                syncNavHighlight(mCurrentSectionId); // keep highlight on the real current section
                openSettings();
                return;
            }

            // GUTUBE(issue #213): the boot selection of a removed Home opens the landing tab instead.
            if (section.getId() == MediaGroup.TYPE_HOME && !NavTabs.showHome(this)) {
                int landing = landingItemId();
                mSuppressNavCallback = true;
                mBottomNav.setSelectedItemId(landing);
                mSuppressNavCallback = false;
                onNavItemChosen(landing);
                return;
            }

            hideYouPanel();
            boolean switched = mCurrentSectionId != section.getId();
            if (switched) {
                clearSubsChannel();
            }
            mCurrentSectionId = section.getId();
            updateStrip();
            applyVideoLayout();
            paintCachedSnapshot(section.getId(), switched);

            // The section may not be one of the sections shown in the bottom nav - e.g. the
            // sign-out boot fallback can select Music, which the preferred-tab selection
            // doesn't include (it lives in the You panel). Still drive the presenter so the
            // grid loads; syncNavHighlight no-ops when the section has no tab.
            // GUTUBE(start screen): a start section without a tab of its own (History, a pinned
            // channel) opens as a You row does: You lit, its title in the top bar, Back to You.
            // The bar may still be a posted rebuild at boot; build it first so the check is real.
            flushNavRebuild();
            Menu menu = mBottomNav.getMenu();
            mSectionFromYou = menu.size() > 0 && menu.findItem(toMenuItemId(section.getId())) == null;
            if (mSectionFromYou) {
                mSuppressNavCallback = true;
                mBottomNav.setSelectedItemId(YOU_ITEM_ID);
                mSuppressNavCallback = false;
            } else {
                syncNavHighlight(section.getId());
            }
            onYouPanelToggled();

            if (mPresenter != null) {
                mPresenter.onSectionFocused(section.getId());
            }

            if (focusOnContent) {
                focusOnContent();
            }
        });
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            if (mSubsChannel != null && group.getAction() != VideoGroup.ACTION_SYNC
                    && group.getAction() != VideoGroup.ACTION_REMOVE_AUTHOR) {
                return; // GUTUBE(feed-strip): the all-channels feed; "All" brings it back
            }
            hideError();

            // ACTION_SYNC = an item was MUTATED in place (e.g. DeArrow / unlocalized-title
            // overrides set video.deArrowTitle / video.altCardImageUrl on the existing Video
            // instance). submitList()'s DiffUtil can't see in-place mutations (same object
            // reference on both sides), so force a targeted re-bind instead - that's how the
            // crowd-sourced titles/thumbnails actually reach the cards.
            if (group.getAction() == VideoGroup.ACTION_SYNC) {
                syncVideos(group.getVideos());
                mAdapter.refreshItems(group.getVideos());
                return;
            }
            if (mCurrentSectionId == MediaGroup.TYPE_TRENDING && !keepHyped(group)) {
                return; // another category's videos: kept for its chip
            }

            // NEWTUBE(feed-swap): this update replaces a FeedCache snapshot with fresh content.
            boolean staleSwap = false;
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    boolean emptyReplace = group.getVideos() == null || group.getVideos().isEmpty();
                    boolean swapPending = mFeedSwap != null && mFeedSwap.isPending();
                    if (emptyReplace && swapPending) {
                        // NEWTUBE(feed-swap): a reload (pull-to-refresh...) started while the fresh
                        // page was still warming up. The grid still shows the snapshot, so this is
                        // again "a snapshot awaiting fresh content": drop the swap, keep the screen.
                        cancelFeedSwap();
                        mCurrentVideos.clear();
                        mCurrentVideos.addAll(mAdapter.getCurrentList());
                        mAwaitingFreshContent = true;
                    }
                    if (emptyReplace && FeedSwapWarmup.keepScreenOnClear(mAwaitingFreshContent, swapPending)) {
                        // The presenter's clear-before-load. The grid is painting a FeedCache
                        // snapshot - keep it on screen; the fresh result replaces it below.
                        return;
                    }
                    staleSwap = mAwaitingFreshContent;
                    mCurrentVideos.clear();
                    if (!emptyReplace) {
                        mCurrentVideos.addAll(visibleFeedItems(withShortsShelf(group.getVideos())));
                    }
                    mAwaitingFreshContent = false;
                    if (emptyReplace) {
                        showReloadSkeleton();
                    }
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mCurrentVideos.addAll(0, visibleFeedItems(withShortsShelf(group.getVideos())));
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mCurrentVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE_AUTHOR:
                    removeByAuthor(group.getVideos());
                    // GUTUBE(issue #87): an unsubscribed strip channel leaves the strip as well as the feed.
                    if (mCurrentSectionId == MediaGroup.TYPE_SUBSCRIPTIONS && mSubsChannels != null) {
                        boolean picked = false;
                        for (Video video : group.getVideos()) {
                            mSubsChannels.removeIf(channel -> Helpers.equals(channel.getAuthor(), video.getAuthor()));
                            picked |= mSubsChannel != null && Helpers.equals(mSubsChannel.getAuthor(), video.getAuthor());
                        }
                        updateStrip();
                        if (picked) {
                            onStripPicked(new FeedStrip.Item(-1, getString(R.string.mobile_feed_all), null, null));
                            return;
                        }
                    }
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    if (mAwaitingFreshContent) {
                        // First fresh group after a cached repaint: swap the stale snapshot
                        // out instead of appending fresh rows below it.
                        mCurrentVideos.clear();
                        mAwaitingFreshContent = false;
                        staleSwap = true;
                    }
                    appendNew(withShortsShelf(group.getVideos()));
                    extendShortsQueue(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to trigger again on the new size
            mLastNearEndTriggerCount = -1;
            // Grid growth per update (how deep a feed scrolls, and which pages added nothing).
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-grid section=" + mCurrentSectionId
                    + " action=" + group.getAction() + " in=" + (group.getVideos() != null ? group.getVideos().size() : 0)
                    + " size=" + mCurrentVideos.size());
            submitFeed(staleSwap);
            checkFeedRunway();
            maybeLaunchShorts();

            if (isDownloadsSectionShowing()) {
                // Local cards carry their file and registry entry - not something a persisted
                // snapshot can restore, so the section never goes through FeedCache.
                if (mCurrentVideos.isEmpty()) {
                    showDownloadsEmptyState();
                } else {
                    setSkeletonVisible(false);
                }
                return;
            }

            if (mCurrentVideos.isEmpty() && (group.getAction() == VideoGroup.ACTION_REMOVE
                    || group.getAction() == VideoGroup.ACTION_REMOVE_AUTHOR)) {
                // NEWTUBE(feed): the last rows were removed (e.g. unsubscribing from the only
                // channel on Subscriptions) - say so instead of leaving a blank tab.
                showEmptyAfterRemoval();
                return;
            }

            if (!mCurrentVideos.isEmpty()) {
                mLastSessionFallback = null;
                setSkeletonVisible(false);
                com.newtube.mobile.LaunchMilestones.onFeedFreshBound(mCurrentSectionId, mCurrentVideos.size());
                FeedCache.put(mCurrentSectionId, mCurrentVideos);
                // First FRESH feed content is on screen -> the launch-critical /browse chain is
                // done; now the heavy one-time session warmup can run without racing it.
                SessionWarmup.start(this);
            }
        });
    }

    /**
     * NEWTUBE(feed-swap): hand {@link #mCurrentVideos} to the grid. {@code staleSwap}: this update
     * replaces a FeedCache snapshot that is ON SCREEN with the first fresh page. That swap used to go
     * through the item animator like any other change: every stale card faded out, the grid sat
     * EMPTY, then the new cards faded in - ~180 ms of blank Home on 4 of 6 Wi-Fi cold launches on the
     * Pixel, and again ~1 s after minimizing onto a freshly created Home. A stale card that survived
     * into the fresh list could also drag the viewport down to wherever it moved. Now the fresh
     * first screen's pictures warm up first (FeedSwapWarmup, capped at a few hundred ms, the
     * snapshot stays up meanwhile), then the list is swapped in ONE frame with no item animation,
     * pinned to the top. Only a grid resting at the top waits and is pinned: a scrolled or moving
     * one swaps at once (the first screen's pictures are not what it shows) and is never moved.
     * A swap that arrives before the snapshot was ever laid out (the fresh page beat the first
     * frame) needs none of this and goes straight in.
     */
    private void submitFeed(boolean staleSwap) {
        if (staleSwap && isShowingCards()) {
            beginFeedSwap();
            return;
        }
        if (mFeedSwap != null && mFeedSwap.isPending()) {
            if (!mCurrentVideos.isEmpty()) {
                return; // the rest of the page (next shelves): the pending swap submits the newest list
            }
            cancelFeedSwap(); // cleared under a pending swap: there is nothing left to swap to
        }
        submitGrid();
    }

    private boolean isShowingCards() {
        return mAdapter.getItemCount() > 0 && mContentGrid.getChildCount() > 0 && mContentGrid.isShown();
    }

    private void beginFeedSwap() {
        cancelFeedSwap();
        if (!isGridAtTopAndIdle()) {
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-swap section=" + mCurrentSectionId
                    + " warmed=0/0 waitMs=0 scrolled +" + com.newtube.mobile.LaunchMilestones.sinceProcessStartMs());
            commitFeedSwap();
            return;
        }
        int first = mLayoutManager.findFirstVisibleItemPosition();
        int last = mLayoutManager.findLastVisibleItemPosition();
        int visible = first >= 0 && last >= first ? last - first + 1 : 1;
        long startMs = android.os.SystemClock.uptimeMillis();
        int sectionId = mCurrentSectionId;
        FeedSwapWarmup swap = FeedSwapWarmup.create(this);
        mFeedSwap = swap; // before begin(): it may finish synchronously (memory-cache hits)
        swap.begin(FeedSwapWarmup.firstScreen(mCurrentVideos, visible), (warmed, total, timedOut) -> {
            if (mFeedSwap == swap) {
                mFeedSwap = null;
            }
            if (isDestroyed()) {
                return;
            }
            com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log("feed-swap section=" + sectionId
                    + " warmed=" + warmed + "/" + total
                    + " waitMs=" + (android.os.SystemClock.uptimeMillis() - startMs)
                    + (timedOut ? " timeout" : "")
                    + " +" + com.newtube.mobile.LaunchMilestones.sinceProcessStartMs());
            commitFeedSwap();
        });
    }

    private void cancelFeedSwap() {
        if (mFeedSwap != null) {
            mFeedSwap.cancel();
            mFeedSwap = null;
        }
    }

    /** At the very top and not being dragged or flung: the only state a swap may pin. */
    private boolean isGridAtTopAndIdle() {
        return FeedSwapWarmup.warmAndPin(!mContentGrid.canScrollVertically(-1),
                mContentGrid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
    }

    /** Swap the whole list in one layout: animator parked, top pinned if the grid rests at the top. */
    private void commitFeedSwap() {
        if (!mCurrentVideos.isEmpty()) {
            mFeedSwapPinTop = isGridAtTopAndIdle();
            parkItemAnimator();
        }
        submitGrid();
    }

    /**
     * NEWTUBE(motion): a section switch waits for its first commit (onSectionSwapCommitted). Kept as
     * a flag, not only as the snapshot's commit callback: the section's fresh page often lands right
     * behind the snapshot, and AsyncListDiffer drops a superseded submission's callback (Pixel 9:
     * History's switch then skipped the fade and the pin).
     */
    private boolean mSectionSwapPending;

    private void parkItemAnimator() {
        if (mParkedItemAnimator == null && mContentGrid.getItemAnimator() != null) {
            mParkedItemAnimator = mContentGrid.getItemAnimator();
            mContentGrid.setItemAnimator(null);
        }
        if (mParkedItemAnimator != null) {
            // Safety net for a commit that never lands (superseded by a section switch), counted
            // from the LATEST park: an earlier swap's timer must not bring the animator back in the
            // middle of a newer one.
            mContentGrid.removeCallbacks(mRestoreItemAnimator);
            mContentGrid.postDelayed(mRestoreItemAnimator, 1_000);
        }
    }

    /** NEWTUBE(motion): a section switch landed (see paintCachedSnapshot) - top of the list, fade in. */
    private void onSectionSwapCommitted() {
        if (isDestroyed() || !mSectionSwapPending) {
            return;
        }
        mSectionSwapPending = false;
        // DiffUtil keeps the viewport anchored on a card both sections share; a new section starts
        // at its top.
        mLayoutManager.scrollToPositionWithOffset(0, 0);
        if (mAdapter.getItemCount() > 0) {
            Motion.frostIn(mContentGrid); // GUTUBE(frost): the new tab clears out of frosted glass
        }
        restoreItemAnimatorAfterLayout();
    }

    /** GUTUBE(issue #73): the Subscriptions feed says which channels are live; their strip avatars glow. */
    private void markLiveChannels() {
        boolean added = false;
        for (Video video : mCurrentVideos) {
            if (video.isLive) {
                added |= com.newtube.mobile.ui.common.LiveRingView.markLive(video.channelId);
            }
        }
        if (added && mStrip != null && mStrip.mode() == FeedStrip.MODE_AVATARS) {
            mStrip.select(mStrip.selected()); // rebinds the avatars
        }
    }

    private void submitGrid() {
        markLiveChannels();
        mAdapter.setRanked(mCurrentSectionId == MediaGroup.TYPE_TRENDING);
        List<Video> list = segmented(mCurrentVideos);
        if (mParkedItemAnimator == null && !mSectionSwapPending) {
            mAdapter.submitList(list);
        } else {
            // Every submission while parked carries the callback: a later one supersedes the
            // earlier diff (and drops its callback), and the last one must still restore.
            mAdapter.submitList(list, this::onFeedSwapCommitted);
        }
    }

    private void onFeedSwapCommitted() {
        if (isDestroyed()) {
            return;
        }
        if (mSectionSwapPending) {
            mFeedSwapPinTop = false;
            onSectionSwapCommitted(); // this commit superseded the switch's own (see the flag)
            return;
        }
        boolean pin = mFeedSwapPinTop && isGridAtTopAndIdle(); // a finger may have landed since
        mFeedSwapPinTop = false;
        if (pin) {
            // DiffUtil keeps the viewport anchored on a surviving card; pin the fresh top instead.
            mLayoutManager.scrollToPositionWithOffset(0, 0);
        }
        restoreItemAnimatorAfterLayout();
    }

    private void restoreItemAnimatorAfterLayout() {
        // Item animations are decided when the update is LAID OUT (the next frame), so the animator
        // may only come back once RecyclerView has consumed the swap.
        mContentGrid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (mContentGrid.hasPendingAdapterUpdates()) {
                    return true;
                }
                ViewTreeObserver observer = mContentGrid.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                restoreItemAnimator();
                return true;
            }
        });
    }

    private void restoreItemAnimator() {
        mContentGrid.removeCallbacks(mRestoreItemAnimator);
        if (mParkedItemAnimator != null) {
            mContentGrid.setItemAnimator(mParkedItemAnimator);
            mParkedItemAnimator = null;
        }
    }

    private void appendNew(List<Video> videos) {
        if (videos == null) {
            return;
        }
        // NEWTUBE(perf): membership through a hash set instead of List.contains - every page of a
        // long feed used to scan the whole grid per new item on the main thread (Video.equals
        // recomputes both hashCodes per comparison). Same answer: Video.equals is "same hashCode
        // and same isMix()", so equal videos always share a bucket.
        java.util.Set<Video> present = new java.util.HashSet<>(mCurrentVideos);
        java.util.Set<Video> seen = isHomeChip(mCurrentSectionId) && mSubsChannel == null ? seenOnRefresh() : null;
        boolean skipped = false;
        for (Video video : videos) {
            if (!isVisibleFeedItem(video)) {
                continue;
            }
            if (seen != null && seen.contains(video)) {
                skipped = true;
            } else if (present.add(video)) {
                mCurrentVideos.add(video);
            }
        }
        if (skipped && mCurrentVideos.isEmpty()) {
            // A topic with a short, fixed page (Gaming): all of it was seen - start over, never an empty feed.
            seen.clear();
            appendNew(videos);
        }
    }

    /**
     * Browse sections are video feeds, not discovery results. YouTube occasionally injects a
     * channel-shaped recommendation into Home/Trending; rendering that with the shared search
     * adapter creates an avatar-only row in the middle of otherwise full-thumbnail cards. NewTube
     * has no Shorts, so none are shown in any section ({@link ShortsFilter}). Keep true playlists
     * (which use a similar service shape), and leave channel discovery to Search.
     */
    private List<Video> visibleFeedItems(List<Video> videos) {
        List<Video> visible = new ArrayList<>();
        if (videos == null) {
            return visible;
        }
        for (Video video : videos) {
            if (isVisibleFeedItem(video)) {
                visible.add(video);
            }
        }
        return visible;
    }

    /**
     * GUTUBE(issue #43): on Home and its topics a shelf's Shorts (the TV Home's Shorts rows) fold
     * into ONE full-width card at the first one's place, a row you swipe sideways (Settings > Shorts
     * on Home). Fewer than two make no shelf; they stay out, like every other stray Short.
     */
    private List<Video> withShortsShelf(List<Video> videos) {
        if (videos == null || !isHomeChip(mCurrentSectionId) || !HomeLayout.isShortsShelf(this)) {
            return videos;
        }
        // A Short an earlier shelf already holds stays out: News sends "National news" and "Top news"
        // with mostly the same clips, and each became a shelf of its own - the row twice over.
        java.util.Set<Video> shelved = new java.util.HashSet<>();
        for (Video card : mCurrentVideos) {
            if (card.shortsShelf != null) {
                shelved.addAll(card.shortsShelf);
            }
        }
        List<Video> shorts = new ArrayList<>();
        int at = -1;
        List<Video> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            if (video != null && video.hasVideo() && ShortsFilter.isShort(video)) {
                if (!shelved.add(video)) {
                    continue;
                }
                if (at < 0) {
                    at = result.size();
                }
                shorts.add(video);
            } else {
                result.add(video);
            }
        }
        if (shorts.size() < 2) {
            return result; // no shelf; a lone Short stays out like any stray one, a repeat too
        }
        for (Video video : shorts) {
            video.markShorts(); // plays in the Shorts player, and drawn as a tile
        }
        Video shelf = shorts.get(0).copy(); // equal to its first short: a reload finds the same card
        shelf.isShorts = true; // a disk snapshot (no shelf field) brings it back as a Short: left out
        shelf.shortsShelf = shorts;
        result.add(at, shelf);
        return result;
    }

    /** GUTUBE(issue #43): the Home shelf {@code video} is on, or null. */
    @Nullable
    private List<Video> shelfOf(Video video) {
        for (Video card : mCurrentVideos) {
            if (card.shortsShelf != null && card.shortsShelf.contains(video)) {
                return card.shortsShelf;
            }
        }
        return null;
    }

    private boolean isVisibleFeedItem(Video video) {
        return video != null
                // GUTUBE(shorts): Shorts still stay out of every feed but their own tab, and
                // History: it lists what you watched, Shorts included. And a Home shelf (issue #43).
                // And Subscriptions and pinned channels, behind their All | Videos | Shorts pill.
                && (!ShortsFilter.isShort(video) || video.belongsToShorts() || video.belongsToHistory()
                    || video.shortsShelf != null || isSegmentSection())
                && (!video.isChannel() || video.isPlaylistAsChannel())
                && !isSearchQueryTile(video);
    }

    /**
     * NEWTUBE(feed): the TV Home feed mixes in search-suggestion tiles - a thumbnail plus a query
     * ("Rivian owner experience"), no video, playlist or channel behind it. On the phone they
     * rendered as video cards whose meta line repeated the title, and tapping one opened Search
     * instead of playing: a card that lies about what it is. Dropped from the grids.
     */
    private static boolean isSearchQueryTile(Video video) {
        return video.searchQuery != null
                && video.videoId == null && video.playlistId == null && video.channelId == null;
    }

    private void syncVideos(List<Video> videos) {
        for (Video video : videos) {
            int idx = mCurrentVideos.indexOf(video);
            if (idx >= 0 && mCurrentVideos.get(idx).shortsShelf == null) { // a shelf equals its first short
                mCurrentVideos.set(idx, video);
            }
        }
    }

    private void removeByAuthor(List<Video> videos) {
        for (Video video : videos) {
            String author = video.getAuthor();
            for (int i = mCurrentVideos.size() - 1; i >= 0; i--) {
                String cardAuthor = mCurrentVideos.get(i).getAuthor();
                // GUTUBE(issue #87): a strip channel is "Name", its feed cards "Name • @handle"
                if (Helpers.equals(cardAuthor, author) || author != null && Helpers.startsWith(cardAuthor, author + " •")) {
                    mCurrentVideos.remove(i);
                }
            }
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        // Settings rendering goes through the AppDialog renderer (ROADMAP Wave 1 -
        // universal OptionCategory/OptionItem sheet), not the video grid. Out of scope
        // for this slice; presenter already calls showProgressBar(false) right after.
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (section == null || section.getId() != mCurrentSectionId) {
            return;
        }

        runOnUiThread(() -> {
            cancelFeedSwap(); // NEWTUBE(feed-swap)
            mCurrentVideos.clear();
            mAdapter.submitList(new ArrayList<>());
            showReloadSkeleton();
        });
    }

    /**
     * NEWTUBE(motion): the presenter's clear-before-load (pull-to-refresh, a reload) emptied the
     * grid. showProgressBar(true) usually came first, when the grid still had cards, so it put up
     * no skeleton and a refresh sat on a blank page with only the pull indicator. The skeleton now
     * takes the pull indicator's place until the fresh cards arrive.
     */
    private void showReloadSkeleton() {
        if (!mProgressShowing || !mCurrentVideos.isEmpty() || isDownloadsSectionShowing()
                || mErrorContainer.getVisibility() == View.VISIBLE) {
            return;
        }
        setSkeletonVisible(true);
        mContentSwipe.setRefreshing(false);
        // The old cards leave with the item animator's fade, which showed through the skeleton's
        // gaps: they go under it - once it is on screen (hidden in the same frame, the grid left a
        // blank frame while the skeleton drew its first). The grid is back when the skeleton leaves.
        mGridHiddenForSkeleton = true;
        FrameGate.afterNextFrame(mFeedSkeleton, 50, () -> {
            if (mGridHiddenForSkeleton && mFeedSkeleton.getVisibility() == View.VISIBLE) {
                mContentGrid.animate().cancel();
                mContentGrid.setAlpha(0f);
            }
        });
    }

    @Override
    public void selectSectionItem(int index) {
        runOnUiThread(() -> {
            if (index >= 0 && index < mAdapter.getItemCount()) {
                mContentGrid.scrollToPosition(index + stripRows());
            }
        });
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item == null) {
            return;
        }

        runOnUiThread(() -> {
            int index = mCurrentVideos.indexOf(item);
            if (index >= 0) {
                mContentGrid.scrollToPosition(index + stripRows());
            }
        });
    }

    /**
     * Graceful empty state for an empty / sign-in-gated / errored section (ROADMAP polish).
     * {@code BrowsePresenter} routes ALL of these here: signed-out auth-only sections (Subscriptions,
     * Playlists) via {@code authCheck()} -> {@link com.liskovsoft.smartyoutubetv2.common.app.models.errors.SignInError},
     * and genuinely-empty / failed loads via {@code handleLoadError()} ->
     * {@link com.liskovsoft.smartyoutubetv2.common.app.models.errors.CategoryEmptyError}. We render a
     * centered icon + message (and, when the error carries a sign-in action, a "Sign in" button that
     * runs {@code ErrorFragmentData.onAction()} -> {@code YTSignInPresenter.start()} -> the mobile
     * SignIn screen). The message is section-aware for the sign-in case ("Sign in to see your
     * Subscriptions") and a clean generic line otherwise - we deliberately do NOT surface
     * {@code CategoryEmptyError.getMessage()} verbatim because for real errors it returns a raw stack
     * trace, which is not something to show a phone user.
     */
    @Override
    public void showError(ErrorFragmentData data) {
        runOnUiThread(() -> {
            String actionText = data != null ? data.getActionText() : null;
            boolean hasSignInAction = actionText != null && !actionText.isEmpty();
            // What the action button will carry. Cleared by the offline branch below, where the
            // button means "retry" rather than "run this error's own action".
            ErrorFragmentData actionData = data;

            // NEWTUBE(motion): the first load after a cold start failed - last session's cards
            // beat an error page (the pre-skeleton behaviour); pull-to-refresh is the retry.
            if (!hasSignInAction && mCurrentVideos.isEmpty() && mLastSessionFallback != null) {
                mCurrentVideos.addAll(visibleFeedItems(mLastSessionFallback));
                mLastSessionFallback = null;
                mAwaitingFreshContent = true;
                mAdapter.submitList(segmented(mCurrentVideos));
            }

            setSkeletonVisible(false);
            mContentSwipe.setRefreshing(false);

            // A failed refresh over a FeedCache repaint: stale content beats a full-screen
            // error. Keep the grid; pull-to-refresh is the retry. Sign-in gating still takes
            // the full-screen path - stale rows from another signed-in state would mislead.
            if (!hasSignInAction && !mCurrentVideos.isEmpty()) {
                return;
            }

            mContentGrid.setVisibility(View.GONE);
            mErrorContainer.setVisibility(View.VISIBLE);
            mErrorIcon.setVisibility(View.VISIBLE);

            if (hasSignInAction) {
                mErrorMessage.setText(SignInCopy.forSection(this, mCurrentSectionId, getCurrentSectionTitle()));
                mErrorAction.setText(actionText);
                mErrorAction.setVisibility(View.VISIBLE);
            } else if (!hasValidatedNetwork()) {
                // NEWTUBE(offline-feed): a load that failed because the phone has no working
                // network used to render as "Nothing to show here yet" with no affordance - the
                // app blaming YouTube for the user's tunnel, on the screen where "nothing loads"
                // is the whole complaint. The section really being empty and the request never
                // leaving the device are indistinguishable at this seam, so ask the device
                // instead of guessing from the error.
                mErrorMessage.setText(R.string.mobile_empty_no_connection);
                mErrorAction.setText(R.string.mobile_signin_retry_button);
                mErrorAction.setVisibility(View.VISIBLE);
                // Null tag = "plain retry" for the click listener; this state has no sign-in action
                // to carry, and running the section's own onAction here would be the wrong thing.
                actionData = null;
            } else {
                // Issue #19: TV, the one client that takes GuTube's sign-in, never lists private or
                // unlisted uploads, not even to their owner, so an empty My videos needs saying why.
                mErrorMessage.setText(mCurrentSectionId == MediaGroup.TYPE_MY_VIDEOS
                        ? R.string.mobile_empty_my_videos : R.string.mobile_empty_generic);
                mErrorAction.setVisibility(View.GONE);
            }

            mErrorAction.setTag(actionData);
        });
    }

    /** Title of the section the user is currently viewing, for a section-aware empty message. */
    /**
     * Does the device currently have a working default network? Used only to word the empty state:
     * the feed's failure paths all collapse to "no rows", so the error object itself cannot tell an
     * offline failure from a genuinely empty section.
     */
    private boolean hasValidatedNetwork() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // can't tell - keep the neutral wording
        }

        android.net.Network active = cm.getActiveNetwork();
        android.net.NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String getCurrentSectionTitle() {
        for (BrowseSection section : mSections) {
            if (section.getId() == mCurrentSectionId) {
                return section.getTitle();
            }
        }
        return null;
    }

    private void hideError() {
        if (mErrorContainer.getVisibility() != View.VISIBLE) {
            return;
        }

        mErrorContainer.setVisibility(View.GONE);
        mContentGrid.setVisibility(View.VISIBLE);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgressShowing = show;
        runOnUiThread(() -> {
            // Empty grid = first load: card-skeleton ghosts (the old centered spinner over a
            // black void read as "the app hangs"). Grid with content (FeedCache repaint or
            // pagination): no overlay at all - the pull-to-refresh indicator covers the
            // user-initiated case, and background refreshes just swap content in when ready.
            if (show || mFeedSkeleton.getVisibility() != View.VISIBLE) {
                setSkeletonVisible(show && mCurrentVideos.isEmpty());
            } else {
                // NEWTUBE(motion): the presenter says "done" just BEFORE it hands over the rows (in
                // the same callback): decide once they are in, so the skeleton fades over them
                // instead of vanishing onto an empty grid.
                mFeedSkeleton.post(() -> {
                    if (!mProgressShowing) {
                        setSkeletonVisible(false);
                    }
                });
            }
            if (show && mCurrentVideos.isEmpty()) {
                mContentSwipe.setRefreshing(false); // the skeleton says it
            }
            if (!show) {
                mContentSwipe.setRefreshing(false);
            }
        });
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressShowing;
    }

    @Override
    public void focusOnContent() {
        runOnUiThread(() -> mContentGrid.requestFocus());
    }

    @Override
    public boolean isEmpty() {
        return mCurrentVideos.isEmpty();
    }

    @Override
    public void updateBadge() {
        // Account/bridge badge icon (top corner on TV). No equivalent surface yet on
        // the mobile shell; revisit alongside the account/sign-in screens (Wave 5).
    }

    @Override
    public void onSectionContentCurrent(int sectionId) {
        runOnUiThread(() -> {
            if (sectionId != mCurrentSectionId) {
                return;
            }

            // The presenter skipped the refetch (section fresh within TTL): the snapshot painted
            // by paintCachedSnapshot IS the current content. Clear the awaiting flag so a later
            // scroll-end APPEND extends the grid instead of swap-replacing it through the
            // stale-snapshot path, and make sure no loading affordance lingers.
            mAwaitingFreshContent = false;
            if (mCurrentVideos.isEmpty() && mLastSessionFallback != null) {
                // No fetch is coming for a section held back behind the skeleton: its cards it is.
                mCurrentVideos.addAll(visibleFeedItems(mLastSessionFallback));
                mAdapter.submitList(segmented(mCurrentVideos));
            }
            mLastSessionFallback = null;
            setSkeletonVisible(false);
            mContentSwipe.setRefreshing(false);
        });
    }
}
