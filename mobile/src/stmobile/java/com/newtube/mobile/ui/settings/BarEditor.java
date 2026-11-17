package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.NavTabs;
import com.newtube.mobile.ui.common.Haptics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUTUBE(bar editor): Settings > Customization > Tabs shows the bars as a stack you drag: a live
 * preview of the top and bottom bars, then a tile per item.
 * All tabs (including Cast, Search, Home, and You) are completely unlocked and can be freely dragged
 * between the Top Bar and Bottom Bar sections and reordered at will.
 */
final class BarEditor extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int TOP_HEADER = -100;
    public static final int BOTTOM_HEADER = -101;
    public static final int SEARCH = NavTabs.ITEM_SEARCH;
    public static final int YOU = NavTabs.ITEM_YOU;
    public static final int CAST = NavTabs.ITEM_CAST;
    public static final int ITEM_ADD = -105;

    private static final int ACTION_MOVE_TOP = -200;
    private static final int ACTION_MOVE_BOTTOM = -201;
    private static final int ACTION_REMOVE = -202;

    private static final int VIEW_HEADER = 0;
    private static final int VIEW_TILE = 1;
    private static final int VIEW_ADD = 2;

    private final Context mContext;
    private final SettingsAdapter.Listener mPage;
    private final List<Integer> mItems = new ArrayList<>();
    private final LinearLayout mPreviewTop;
    private final LinearLayout mPreviewBottom;
    private ItemTouchHelper mTouchHelper;

    static SettingsRow row(Context context) {
        return SettingsRow.custom(context.getString(R.string.mobile_settings_tabs_bottom_bar), BarEditor::create);
    }

    private static View create(ViewGroup parent, SettingsAdapter.Listener page) {
        Context context = parent.getContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout preview = new FrameLayout(context);
        preview.setBackgroundResource(R.drawable.bg_bar_tile);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 148));
        int inset = context.getResources().getDimensionPixelSize(R.dimen.mobile_settings_row_inset);
        previewParams.setMargins(inset, dp(context, 8), inset, 0);
        root.addView(preview, previewParams);
        LinearLayout top = pill(context);
        preview.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40),
                Gravity.TOP | Gravity.END));
        LinearLayout bottom = pill(context);
        preview.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) top.getLayoutParams()).setMargins(0, dp(context, 12), dp(context, 12), 0);
        ((FrameLayout.LayoutParams) bottom.getLayoutParams()).bottomMargin = dp(context, 14);

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        root.setClipChildren(false);
        BarEditor editor = new BarEditor(context, page, top, bottom);
        list.setAdapter(editor);
        editor.mTouchHelper = new ItemTouchHelper(editor.new Drag());
        editor.mTouchHelper.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private BarEditor(Context context, SettingsAdapter.Listener page, LinearLayout previewTop, LinearLayout previewBottom) {
        mContext = context;
        mPage = page;
        mPreviewTop = previewTop;
        mPreviewBottom = previewBottom;

        List<Integer> top = NavTabs.getTopBarItems(context);
        List<Integer> bottom = NavTabs.getBottomBarItems(context);

        // Ensure CAST is present if not already in either bar
        if (!top.contains(CAST) && !bottom.contains(CAST)) {
            top.add(0, CAST);
        }
        // Ensure SEARCH is present
        if (!top.contains(SEARCH) && !bottom.contains(SEARCH)) {
            bottom.add(SEARCH);
        }
        // Ensure YOU is present
        if (!top.contains(YOU) && !bottom.contains(YOU)) {
            bottom.add(YOU);
        }
        // Ensure HOME is present if enabled
        if (!top.contains(MediaGroup.TYPE_HOME) && !bottom.contains(MediaGroup.TYPE_HOME) && NavTabs.showHome(context)) {
            bottom.add(0, MediaGroup.TYPE_HOME);
        }

        List<Integer> cleanTop = new ArrayList<>();
        for (int id : top) {
            if (id != NavTabs.NONE && !cleanTop.contains(id)) {
                cleanTop.add(id);
            }
        }
        List<Integer> cleanBottom = new ArrayList<>();
        for (int id : bottom) {
            if (id != NavTabs.NONE && !cleanTop.contains(id) && !cleanBottom.contains(id)) {
                cleanBottom.add(id);
            }
        }

        mItems.add(TOP_HEADER);
        mItems.addAll(cleanTop);
        mItems.add(BOTTOM_HEADER);
        mItems.addAll(cleanBottom);
        mItems.add(ITEM_ADD);

        syncPreview();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        int item = mItems.get(position);
        if (item == TOP_HEADER || item == BOTTOM_HEADER) {
            return VIEW_HEADER;
        }
        if (item == ITEM_ADD) {
            return VIEW_ADD;
        }
        return VIEW_TILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout = viewType == VIEW_HEADER ? R.layout.item_mobile_settings_header : R.layout.item_mobile_bar_tile;
        View view = inflater.inflate(layout, parent, false);
        if (viewType != VIEW_HEADER) {
            com.newtube.mobile.ui.common.SpotlightTouchHelper.attach(view, 16f);
        }
        return new RecyclerView.ViewHolder(view) {};
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int item = mItems.get(position);
        View view = holder.itemView;
        if (item == TOP_HEADER || item == BOTTOM_HEADER) {
            ((TextView) view).setText(item == TOP_HEADER ? R.string.mobile_settings_bar_top : R.string.mobile_settings_tabs_bottom_bar);
            return;
        }

        ImageView iconView = view.findViewById(R.id.bar_tile_icon);
        TextView titleView = view.findViewById(R.id.bar_tile_title);
        TextView summaryView = view.findViewById(R.id.bar_tile_summary);
        ImageView grip = view.findViewById(R.id.bar_tile_grip);

        if (item == ITEM_ADD) {
            iconView.setImageResource(R.drawable.ic_mobile_add);
            boolean canAdd = hasAvailableButtonsToAdd();
            titleView.setText(canAdd ? R.string.mobile_settings_bar_add_button : R.string.mobile_settings_bar_all_added);
            summaryView.setText(canAdd ? R.string.mobile_settings_bar_add_summary : R.string.mobile_settings_bar_all_added_summary);
            grip.setVisibility(View.GONE);
            view.setAlpha(canAdd ? 1f : 0.6f);
            view.setOnClickListener(v -> {
                if (hasAvailableButtonsToAdd()) {
                    showAddDialog();
                }
            });
            view.setClickable(true);
            return;
        }

        grip.setVisibility(View.VISIBLE);
        iconView.setImageResource(icon(item));
        titleView.setText(title(item));
        summaryView.setText(summary(item));
        grip.setImageResource(R.drawable.ic_bar_drag);

        boolean homeOff = item == MediaGroup.TYPE_HOME && !NavTabs.showHome(mContext);
        view.setAlpha(item == NavTabs.NONE || homeOff ? 0.6f : 1f);

        // Grip starts drag on touch
        grip.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mTouchHelper.startDrag(holder);
                return true;
            }
            return false;
        });

        view.setOnClickListener(v -> pick(holder.getBindingAdapterPosition()));
        view.setClickable(true);
    }

    private boolean hasAvailableButtonsToAdd() {
        for (int candidate : NavTabs.ALL_BUTTON_CHOICES) {
            if (!mItems.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void showAddDialog() {
        Context c = mContext;
        List<Integer> unadded = new ArrayList<>();
        for (int candidate : NavTabs.ALL_BUTTON_CHOICES) {
            if (!mItems.contains(candidate)) {
                unadded.add(candidate);
            }
        }
        if (unadded.isEmpty()) {
            return;
        }
        SettingsRow.Choice<Integer> choice = SettingsRow.choice(c.getString(R.string.mobile_settings_bar_add_button));
        for (int candidate : unadded) {
            choice.option(title(candidate), icon(candidate), candidate);
        }
        mPage.onRowClicked(choice.bind(() -> unadded.get(0), selected -> {
            int addPos = mItems.indexOf(ITEM_ADD);
            if (addPos < 0) {
                addPos = mItems.size();
            }
            mItems.add(addPos, selected);
            notifyItemInserted(addPos);
            syncPreview();
            save();
            notifyDataSetChanged();
            if (selected >= 0 && selected != MediaGroup.TYPE_HOME) {
                BrowsePresenter.instance(c).enableSection(selected, true);
            }
        }));
    }

    /** A tap: customize what any tab or button shows, move it, or remove it. All buttons unlocked. */
    private void pick(int position) {
        if (position == RecyclerView.NO_POSITION || position >= mItems.size()) {
            return;
        }
        int item = mItems.get(position);
        if (item == TOP_HEADER || item == BOTTOM_HEADER || item == ITEM_ADD) {
            return;
        }
        Context c = mContext;
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        boolean inTop = bottomHeader >= 0 && position < bottomHeader;

        SettingsRow.Choice<Integer> choice = SettingsRow.choice(title(item));

        // 1. Move between bars option
        if (inTop) {
            choice.option(c.getString(R.string.mobile_settings_bar_move_bottom), R.drawable.ic_menu_arrow_down, ACTION_MOVE_BOTTOM);
        } else {
            choice.option(c.getString(R.string.mobile_settings_bar_move_top), R.drawable.ic_menu_arrow_up, ACTION_MOVE_TOP);
        }

        // 2. Change what this slot represents (all buttons unlocked)
        for (int candidate : NavTabs.ALL_BUTTON_CHOICES) {
            choice.option(title(candidate), icon(candidate), candidate);
        }

        // 3. Remove option
        choice.option(c.getString(R.string.mobile_settings_bar_remove), R.drawable.ic_sheet_close, ACTION_REMOVE);

        mPage.onRowClicked(choice.bind(() -> item, selected -> {
            int currentPos = mItems.indexOf(item);
            if (currentPos < 0) {
                return;
            }
            if (selected == ACTION_MOVE_TOP) {
                int bHeader = mItems.indexOf(BOTTOM_HEADER);
                if (currentPos > bHeader) {
                    move(currentPos, bHeader);
                    save();
                    notifyDataSetChanged();
                }
            } else if (selected == ACTION_MOVE_BOTTOM) {
                int bHeader = mItems.indexOf(BOTTOM_HEADER);
                int addPos = mItems.indexOf(ITEM_ADD);
                int target = addPos > 0 ? addPos - 1 : mItems.size() - 1;
                if (currentPos < bHeader) {
                    move(currentPos, target);
                    save();
                    notifyDataSetChanged();
                }
            } else if (selected == ACTION_REMOVE) {
                mItems.remove(currentPos);
                notifyItemRemoved(currentPos);
                syncPreview();
                save();
                notifyDataSetChanged();
            } else {
                // Assign new identity
                int existingPos = mItems.indexOf(selected);
                if (existingPos >= 0 && existingPos != currentPos) {
                    // Swap the two slots
                    mItems.set(existingPos, item);
                    notifyItemChanged(existingPos);
                }
                mItems.set(currentPos, selected);
                notifyItemChanged(currentPos);
                syncPreview();
                save();
                if (selected >= 0 && selected != MediaGroup.TYPE_HOME) {
                    BrowsePresenter.instance(c).enableSection(selected, true);
                }
            }
        }));
    }

    /** Moves the dragged item; instantly updates list and preview. */
    private boolean move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= mItems.size() || to >= mItems.size()) {
            return false;
        }
        int item = mItems.get(from);
        if (item == TOP_HEADER || item == BOTTOM_HEADER || item == ITEM_ADD) {
            return false;
        }
        int targetItem = mItems.get(to);
        if (targetItem == TOP_HEADER || targetItem == ITEM_ADD) {
            return false;
        }
        if (to == 0) {
            return false;
        }
        int moved = mItems.remove(from);
        mItems.add(to, moved);
        notifyItemMoved(from, to);
        syncPreview();
        return true;
    }

    private void save() {
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        List<Integer> top = new ArrayList<>();
        List<Integer> bottom = new ArrayList<>();
        for (int i = 0; i < mItems.size(); i++) {
            int item = mItems.get(i);
            if (item == TOP_HEADER || item == BOTTOM_HEADER || item == ITEM_ADD || item == NavTabs.NONE) {
                continue;
            }
            if (bottomHeader >= 0 && i < bottomHeader) {
                top.add(item);
            } else {
                bottom.add(item);
            }
        }
        NavTabs.saveBarItems(mContext, top, bottom);
        for (int item : bottom) {
            if (item >= 0 && item != MediaGroup.TYPE_HOME) {
                BrowsePresenter.instance(mContext).enableSection(item, true);
            }
        }
        for (int item : top) {
            if (item >= 0 && item != MediaGroup.TYPE_HOME) {
                BrowsePresenter.instance(mContext).enableSection(item, true);
            }
        }
    }

    private final class Drag extends ItemTouchHelper.Callback {
        private boolean mMoved;

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) {
                return 0;
            }
            int item = mItems.get(position);
            boolean draggable = item != TOP_HEADER && item != BOTTOM_HEADER && item != ITEM_ADD;
            return makeMovementFlags(draggable ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current,
                                  @NonNull RecyclerView.ViewHolder target) {
            int targetPos = target.getBindingAdapterPosition();
            if (targetPos <= 0 || targetPos >= mItems.size()) {
                return false;
            }
            int targetItem = mItems.get(targetPos);
            return targetItem != TOP_HEADER && targetItem != ITEM_ADD;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = holder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false;
            }
            int targetItem = mItems.get(to);
            if (targetItem == TOP_HEADER || targetItem == ITEM_ADD) {
                return false;
            }
            boolean moved = move(from, to);
            if (moved) {
                mMoved = true;
                Haptics.tick(recyclerView);
            }
            return moved;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                Haptics.longPress(holder.itemView);
                holder.itemView.animate().scaleX(1.05f).scaleY(1.05f)
                        .translationZ(dp(mContext, 14))
                        .setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start();
                View grip = holder.itemView.findViewById(R.id.bar_tile_grip);
                if (grip instanceof ImageView) {
                    ((ImageView) grip).setColorFilter(
                            ContextCompat.getColor(mContext, R.color.mobile_color_primary));
                }
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            Haptics.click(holder.itemView);
            holder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0)
                    .setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
            View grip = holder.itemView.findViewById(R.id.bar_tile_grip);
            if (grip instanceof ImageView) {
                ((ImageView) grip).clearColorFilter();
            }
            if (mMoved) {
                mMoved = false;
                save();
                recyclerView.post(() -> notifyDataSetChanged());
            }
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }
    }

    // ---- the preview -------------------------------------------------------------------------

    private void syncPreview() {
        List<Integer> top = new ArrayList<>();
        List<Integer> bottom = new ArrayList<>();
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        for (int i = 0; i < mItems.size(); i++) {
            int item = mItems.get(i);
            if (item == TOP_HEADER || item == BOTTOM_HEADER || item == ITEM_ADD || item == NavTabs.NONE
                    || (item == MediaGroup.TYPE_HOME && !NavTabs.showHome(mContext))) {
                continue;
            }
            if (bottomHeader >= 0 && i < bottomHeader) {
                top.add(item);
            } else {
                bottom.add(item);
            }
        }
        sync(mPreviewTop, top);
        sync(mPreviewBottom, bottom);
    }

    /**
     * Puts the bar's icons in {@code want}'s order and slides each from where it was to where it
     * lands; one that just arrived pops in.
     */
    private void sync(LinearLayout bar, List<Integer> want) {
        Map<Integer, Integer> lefts = new HashMap<>();
        Map<Integer, View> views = new HashMap<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            lefts.put((Integer) child.getTag(), child.getLeft() + Math.round(child.getTranslationX()));
            views.put((Integer) child.getTag(), child);
        }
        boolean laidOut = bar.isLaidOut();
        bar.removeAllViews();
        for (int item : want) {
            View view = views.get(item);
            if (view == null) {
                view = previewIcon(item);
            }
            bar.addView(view);
        }
        if (!laidOut) {
            return;
        }
        bar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                bar.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    Integer old = lefts.get((Integer) child.getTag());
                    child.animate().cancel();
                    if (old == null) {
                        child.setScaleX(0f);
                        child.setScaleY(0f);
                        child.animate().scaleX(1f).scaleY(1f).setDuration(320)
                                .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    } else {
                        child.setTranslationX(old - child.getLeft());
                        child.animate().translationX(0f).setDuration(280)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    }
                }
                return true;
            }
        });
    }

    private ImageView previewIcon(int item) {
        ImageView icon = new ImageView(mContext);
        icon.setTag(item);
        icon.setImageResource(icon(item));
        icon.setImageTintList(ContextCompat.getColorStateList(mContext, R.color.mobile_color_on_surface));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(mContext, 9);
        icon.setPadding(pad, pad, pad, pad);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(mContext, 44), ViewGroup.LayoutParams.MATCH_PARENT));
        return icon;
    }

    private static LinearLayout pill(Context context) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(context, 6), 0, dp(context, 6), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 24));
        background.setColor(ContextCompat.getColor(context, R.color.mobile_color_card));
        background.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.mobile_color_divider));
        pill.setBackground(background);
        return pill;
    }

    // ---- what each item looks like -------------------------------------------------------------

    private static int icon(int item) {
        switch (item) {
            case CAST:
                return R.drawable.ic_mobile_cast;
            case MediaGroup.TYPE_HOME:
                return R.drawable.ic_nav_home;
            case SEARCH:
                return R.drawable.ic_mobile_search;
            case YOU:
                return R.drawable.ic_nav_you;
            case MediaGroup.TYPE_SHORTS:
                return R.drawable.ic_nav_shorts;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.drawable.ic_nav_subscriptions;
            case VideoDownloads.SECTION_ID:
                return R.drawable.ic_nav_downloads;
            case MediaGroup.TYPE_HISTORY:
                return R.drawable.ic_nav_history;
            case MediaGroup.TYPE_TRENDING:
                return R.drawable.ic_nav_hyped;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.drawable.ic_menu_folder;
            case NavTabs.NONE:
                return R.drawable.ic_sheet_close;
            default:
                return R.drawable.ic_mobile_add;
        }
    }

    private CharSequence title(int item) {
        switch (item) {
            case CAST:
                return mContext.getString(R.string.mobile_cast_button);
            case MediaGroup.TYPE_HOME:
                return mContext.getString(R.string.header_home);
            case SEARCH:
                return mContext.getString(R.string.mobile_search_button);
            case YOU:
                return mContext.getString(R.string.mobile_nav_you);
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return mContext.getString(R.string.mobile_settings_menu_playlists);
            case NavTabs.NONE:
                return mContext.getString(R.string.mobile_settings_bar_empty);
            default:
                return mContext.getString(sectionTitle(item));
        }
    }

    private CharSequence summary(int item) {
        int bottomHeader = mItems.indexOf(BOTTOM_HEADER);
        int pos = mItems.indexOf(item);
        boolean inTopBar = bottomHeader >= 0 && pos >= 0 && pos < bottomHeader;
        if (item == NavTabs.NONE) {
            return mContext.getString(R.string.mobile_settings_bar_empty_summary);
        }
        if (item == MediaGroup.TYPE_HOME && !NavTabs.showHome(mContext)) {
            return mContext.getString(R.string.mobile_settings_bar_home_removed);
        }
        if (inTopBar) {
            return mContext.getString(R.string.mobile_settings_bar_in_top);
        } else {
            int bottomIndex = pos - bottomHeader - 1;
            if (bottomIndex >= 5) {
                return mContext.getString(R.string.mobile_settings_bar_bottom_overflow);
            }
            return mContext.getString(R.string.mobile_settings_bar_in_bottom);
        }
    }

    private static int sectionTitle(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_SHORTS:
                return R.string.header_shorts;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.string.header_subscriptions;
            case VideoDownloads.SECTION_ID:
                return R.string.header_downloads;
            case MediaGroup.TYPE_TRENDING:
                return R.string.header_trending;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.string.mobile_settings_menu_playlists;
            default:
                return R.string.header_history;
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
