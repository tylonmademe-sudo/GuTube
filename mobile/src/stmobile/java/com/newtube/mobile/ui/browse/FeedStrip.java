package com.newtube.mobile.ui.browse;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.newtube.mobile.ui.common.GlassDrawable;
import com.newtube.mobile.ui.common.GlassSource;
import com.newtube.mobile.ui.common.JellySpringPhysics;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE(feed-strip, 2.9.8): the horizontal strip at the top of a browse feed, YouTube-style.
 * Home shows topic chips (issue #4: All / Music / Gaming / ...), Subscriptions shows the subscribed
 * channels' avatars (issue #2: All first, a tap filters the feed to that channel).
 *
 * <p>Topic chips feature the full React Bits &lt;JellyRadio /&gt; mass-spring physics engine:
 * <ul>
 *   <li>Active chip swells (1.20x) with jelly elasticity (wide-before-tall bounce)</li>
 *   <li>Neighbouring chips are shoved outward (barge = 6dp) with a staggered wave (22ms/step)</li>
 *   <li>Unchosen chips slightly shrink (0.95x) to frame the selected chip</li>
 *   <li>Smooth color morphing: #27272a dark pill &lt;-&gt; #f5f5f5 active white pill</li>
 *   <li>100% lag-free on the GPU RenderThread using translationX and scaleX/Y</li>
 * </ul>
 */
final class FeedStrip extends RecyclerView.Adapter<FeedStrip.Holder> {
    static final int MODE_NONE = 0;
    static final int MODE_CHIPS = 1;
    static final int MODE_AVATARS = 2;

    static final class Item {
        final long id;
        final CharSequence label;
        @Nullable final String imageUrl;
        @Nullable final Object data;

        Item(long id, CharSequence label, @Nullable String imageUrl, @Nullable Object data) {
            this.id = id;
            this.label = label;
            this.imageUrl = imageUrl;
            this.data = data;
        }
    }

    interface Listener {
        void onPicked(Item item);
    }

    private final Listener mListener;
    @Nullable private final GlassSource mGlass;
    private final ItemAdapter mItems = new ItemAdapter();
    @Nullable private com.newtube.mobile.ui.common.GlassChipTrack mTrack;
    private int mMode = MODE_NONE;
    private long mSelected;
    /** GUTUBE(all subscriptions): long-press on the avatars' "All". */
    @Nullable private Runnable mOnAllLongPressed;
    // GUTUBE(issue #87): a channel avatar opens its own menu.
    @Nullable private Listener mOnAvatarLongPressed;

    FeedStrip(Listener listener, @Nullable GlassSource glass) {
        mListener = listener;
        mGlass = glass;
    }

    /** The items themselves, for the floating chip row. */
    RecyclerView.Adapter<?> items() {
        return mItems;
    }

    int mode() {
        return mMode;
    }

    /** Shows the strip (or hides it with MODE_NONE / no items); the selected item gets the mark. */
    void show(int mode, List<Item> items, long selected) {
        boolean wasShown = getItemCount() > 0;
        boolean sameList = mode == mMode && sameItems(items);
        int oldMode = mMode;
        mMode = items == null || items.isEmpty() ? MODE_NONE : mode;
        if (oldMode != mMode) {
            mItems.mList = new ArrayList<>(); // another kind of cell: rebuild, not rebind
            mItems.notifyDataSetChanged();
        }
        mSelected = selected;
        if (!sameList) {
            mItems.mList = items != null ? new ArrayList<>(items) : new ArrayList<>();
        }
        mItems.notifyDataSetChanged();
        boolean shown = getItemCount() > 0;
        if (wasShown && !shown) {
            notifyItemRemoved(0);
        } else if (!wasShown && shown) {
            notifyItemInserted(0);
        }
        syncTrack();
        if (mMode == MODE_CHIPS && mItems.mRecyclerView != null) {
            mItems.mRecyclerView.post(() -> mItems.applyJelly(mItems.getSelectedIndex(), true));
        }
    }

    /** The segment style of the chips (Settings > Customization > Chips style): fed the same items. */
    void setTrack(com.newtube.mobile.ui.common.GlassChipTrack track) {
        mTrack = track;
        track.setOnPicked(index -> {
            if (index < mItems.mList.size() && mItems.mList.get(index).id != mSelected) {
                mListener.onPicked(mItems.mList.get(index));
            }
        });
        syncTrack();
    }

    private void syncTrack() {
        if (mTrack == null) {
            return;
        }
        List<CharSequence> labels = new ArrayList<>();
        int selected = -1;
        if (mMode == MODE_CHIPS) {
            for (int i = 0; i < mItems.mList.size(); i++) {
                labels.add(mItems.mList.get(i).label);
                if (mItems.mList.get(i).id == mSelected) {
                    selected = i;
                }
            }
        }
        mTrack.setChips(labels);
        mTrack.setSelected(selected);
    }

    void setOnAllLongPressed(@Nullable Runnable onAllLongPressed) {
        mOnAllLongPressed = onAllLongPressed;
    }

    void setOnAvatarLongPressed(@Nullable Listener onAvatarLongPressed) {
        mOnAvatarLongPressed = onAvatarLongPressed;
    }

    void select(long selected) {
        if (mSelected == selected) {
            return;
        }
        mSelected = selected;
        if (mMode == MODE_CHIPS && mItems.mRecyclerView != null) {
            int newIndex = mItems.getSelectedIndex();
            mItems.applyJelly(newIndex, false);
        } else {
            mItems.notifyDataSetChanged();
        }
        syncTrack();
    }

    long selected() {
        return mSelected;
    }

    private boolean sameItems(List<Item> items) {
        if (items == null || items.size() != mItems.mList.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id != mItems.mList.get(i).id) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemCount() {
        return mMode == MODE_AVATARS ? 1 : 0; // chips float (items()), only avatars sit in the grid
    }

    @Override
    public int getItemViewType(int position) {
        return R.id.mobile_feed_strip; // its own type: never confused with a card in the ConcatAdapter
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        RecyclerView row = new RecyclerView(parent.getContext());
        row.setId(R.id.mobile_feed_strip);
        row.setLayoutManager(new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(parent.getContext(), 12);
        // 16dp gutter like the cards; each chip carries an 8dp right margin, so the end pad is 8
        row.setPadding(dp(parent.getContext(), 16), dp(parent.getContext(), 4), dp(parent.getContext(), 8), dp(parent.getContext(), 8));
        row.setClipToPadding(false);
        row.setClipChildren(false);
        row.setItemAnimator(null);
        // The feed scrolls vertically; a sideways drag on the strip is the strip's.
        row.setNestedScrollingEnabled(false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        RecyclerView row = (RecyclerView) holder.itemView;
        if (row.getAdapter() != mItems) {
            row.setAdapter(mItems);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Item> mList = new ArrayList<>();
        @Nullable RecyclerView mRecyclerView;

        // JellyRadio Physics & Visual Specification constants
        private static final int CHIP_COLOR = 0xFF27272A;
        private static final int ACTIVE_COLOR = 0xFFF5F5F5;
        private static final int TEXT_COLOR = 0xFFF5F5F5;
        private static final int ACTIVE_TEXT_COLOR = 0xFF18181B;

        private static final float SWELL = 0.20f;
        private static final float SHRINK = 0.05f;
        private static final float JELLY = 1.0f;
        private static final float BOUNCE = 0.25f;
        private static final int STAGGER_MS = 22;
        private static final float STIFFNESS = 580f;

        private ValueAnimator mJellyAnimator;
        private float[] mStartX;
        private float[] mStartSx;
        private float[] mStartSy;
        private float[] mTargetX;
        private float[] mTargetSx;
        private float[] mTargetSy;
        private float[] mCurrentX;
        private float[] mCurrentSx;
        private float[] mCurrentSy;

        private final ArgbEvaluator mColorEvaluator = new ArgbEvaluator();

        @Override
        public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
            super.onAttachedToRecyclerView(recyclerView);
            mRecyclerView = recyclerView;
            recyclerView.setClipChildren(false);
            recyclerView.setClipToPadding(false);
            android.view.ViewParent p = recyclerView.getParent();
            while (p instanceof ViewGroup) {
                ((ViewGroup) p).setClipChildren(false);
                ((ViewGroup) p).setClipToPadding(false);
                p = p.getParent();
            }
        }

        @Override
        public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
            super.onDetachedFromRecyclerView(recyclerView);
            if (mJellyAnimator != null) {
                mJellyAnimator.cancel();
            }
            mRecyclerView = null;
        }

        int getSelectedIndex() {
            for (int i = 0; i < mList.size(); i++) {
                if (mList.get(i).id == mSelected) {
                    return i;
                }
            }
            return 0;
        }

        @Override
        public int getItemCount() {
            return mList.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mMode;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == MODE_AVATARS) {
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setLayoutParams(new RecyclerView.LayoutParams(dp(context, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setPadding(0, dp(context, 4), 0, 0);
                ImageView avatar = new ImageView(context);
                avatar.setId(android.R.id.icon);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                // GUTUBE(issue #73): a channel seen live gets the glowing red ring over its avatar.
                android.widget.FrameLayout avatarBox = new android.widget.FrameLayout(context);
                avatarBox.addView(avatar, new android.widget.FrameLayout.LayoutParams(dp(context, 56), dp(context, 56)));
                com.newtube.mobile.ui.common.LiveRingView ring = new com.newtube.mobile.ui.common.LiveRingView(context, null);
                ring.setId(android.R.id.icon2);
                ring.setVisibility(View.GONE);
                avatarBox.addView(ring, new android.widget.FrameLayout.LayoutParams(dp(context, 56), dp(context, 56)));
                cell.addView(avatarBox, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));
                TextView name = new TextView(context);
                name.setId(android.R.id.text1);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nameLp.topMargin = dp(context, 6);
                nameLp.leftMargin = nameLp.rightMargin = dp(context, 2);
                cell.addView(name, nameLp);
                view = cell;
            } else {
                TextView chip = new TextView(context);
                chip.setId(android.R.id.text1);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                chip.setSingleLine(true);
                chip.setIncludeFontPadding(false);
                chip.setGravity(Gravity.CENTER);

                int h = dp(context, 36);
                chip.setMinHeight(h);
                int padH = dp(context, 16);
                int padV = dp(context, 6);
                chip.setPadding(padH, padV, padH, padV);

                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(context, 8); // 8dp gap
                lp.topMargin = dp(context, 4);
                lp.bottomMargin = dp(context, 4);
                chip.setLayoutParams(lp);
                chip.setElevation(dp(context, 3));

                // Active touch press feedback: scale(0.97)
                chip.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(v.getScaleX() * 0.97f).scaleY(v.getScaleY() * 0.97f).setDuration(100).start();
                    } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        int pos = mRecyclerView != null ? mRecyclerView.getChildAdapterPosition(v) : -1;
                        float targetS = (pos == getSelectedIndex()) ? (1f + SWELL) : (1f - SHRINK);
                        v.animate().scaleX(targetS).scaleY(targetS).setDuration(160).start();
                    }
                    return false;
                });

                view = chip;
            }
            view.setClickable(true);
            view.setFocusable(true);
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = mList.get(position);
            Context context = holder.itemView.getContext();
            boolean selected = item.id == mSelected;
            TextView label = holder.itemView.findViewById(android.R.id.text1);
            label.setText(item.label);

            if (mMode == MODE_AVATARS) {
                // YouTube: picking one channel fades the others; "All" keeps every avatar lit.
                boolean dim = mSelected != mList.get(0).id && !selected;
                holder.itemView.setAlpha(dim ? 0.45f : 1f);
                label.setTextColor(ContextCompat.getColor(context, selected
                        ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
                ImageView avatar = holder.itemView.findViewById(android.R.id.icon);
                if (item.imageUrl != null) {
                    avatar.setBackground(null);
                    avatar.setImageDrawable(null);
                    Glide.with(context).load(item.imageUrl).circleCrop()
                            .placeholder(R.drawable.ic_watch_channel_placeholder)
                            .error(R.drawable.ic_watch_channel_placeholder).into(avatar);
                } else {
                    // "All": a plain disc with the grid glyph.
                    Glide.with(context).clear(avatar);
                    GradientDrawable disc = new GradientDrawable();
                    disc.setShape(GradientDrawable.OVAL);
                    disc.setColor(ContextCompat.getColor(context, R.color.mobile_color_pill));
                    avatar.setBackground(disc);
                    avatar.setScaleType(ImageView.ScaleType.CENTER);
                    avatar.setImageResource(R.drawable.ic_nav_subscriptions_outline);
                    avatar.setColorFilter(ContextCompat.getColor(context, R.color.mobile_color_on_surface));
                }
                if (item.imageUrl != null) {
                    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    avatar.clearColorFilter();
                }
                com.newtube.mobile.ui.common.LiveRingView.bind(holder.itemView.findViewById(android.R.id.icon2), avatar,
                        item.data instanceof com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
                                && com.newtube.mobile.ui.common.LiveRingView.isLive(
                                        ((com.liskovsoft.smartyoutubetv2.common.app.models.data.Video) item.data).channelId), 0);
            } else {
                int selectedIndex = getSelectedIndex();
                int dir = (position == selectedIndex) ? 0 : (position > selectedIndex ? 1 : -1);
                float selWidth = 72f * context.getResources().getDisplayMetrics().density;
                float push = (selWidth * SWELL) / 2f + dp(context, 6);
                float targetX = dir * push;
                float targetS = selected ? (1f + SWELL) : (1f - SHRINK);

                boolean animating = mJellyAnimator != null && mJellyAnimator.isRunning();
                float curX = animating && mCurrentX != null && position < mCurrentX.length ? mCurrentX[position] : targetX;
                float curSx = animating && mCurrentSx != null && position < mCurrentSx.length ? mCurrentSx[position] : targetS;
                float curSy = animating && mCurrentSy != null && position < mCurrentSy.length ? mCurrentSy[position] : targetS;

                holder.itemView.setTranslationX(curX);
                holder.itemView.setScaleX(curSx);
                holder.itemView.setScaleY(curSy);

                styleChipView(label, selected, 1f);
            }

            holder.itemView.setOnClickListener(v -> {
                if (mMode == MODE_CHIPS) {
                    if (item.id != mSelected) {
                        mSelected = item.id;
                        applyJelly(position, false);
                        mListener.onPicked(item);
                        syncTrack();
                        if (mRecyclerView != null) {
                            int scrollX = (v.getLeft() + v.getRight()) / 2 - mRecyclerView.getWidth() / 2;
                            mRecyclerView.smoothScrollBy(scrollX, 0);
                        }
                    }
                } else {
                    // GUTUBE(issue #87): a picked avatar can widen the feed to All.
                    if (item.id != mSelected || (mMode == MODE_AVATARS && item.data != null)) {
                        mListener.onPicked(item);
                    }
                }
            });

            boolean allAvatar = mMode == MODE_AVATARS && item.data == null && mOnAllLongPressed != null;
            boolean channelAvatar = mMode == MODE_AVATARS && item.data != null && mOnAvatarLongPressed != null;
            holder.itemView.setOnLongClickListener(allAvatar || channelAvatar ? v -> {
                if (allAvatar) {
                    mOnAllLongPressed.run();
                } else {
                    mOnAvatarLongPressed.onPicked(item);
                }
                return true;
            } : null);
            holder.itemView.setLongClickable(allAvatar || channelAvatar);
        }

        void applyJelly(int sel, boolean instant) {
            if (mRecyclerView == null || mList.isEmpty()) {
                return;
            }
            int count = mList.size();
            if (mCurrentX == null || mCurrentX.length != count) {
                mCurrentX = new float[count];
                mCurrentSx = new float[count];
                mCurrentSy = new float[count];
                mStartX = new float[count];
                mStartSx = new float[count];
                mStartSy = new float[count];
                mTargetX = new float[count];
                mTargetSx = new float[count];
                mTargetSy = new float[count];
            }

            Context context = mRecyclerView.getContext();
            float dp = context.getResources().getDisplayMetrics().density;
            boolean rtl = mRecyclerView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float selWidth = 72f * dp;
            RecyclerView.ViewHolder selHolder = mRecyclerView.findViewHolderForAdapterPosition(sel);
            if (selHolder != null && selHolder.itemView.getWidth() > 0) {
                selWidth = selHolder.itemView.getWidth();
            }
            float push = (selWidth * SWELL) / 2f + dp(context, 6);

            boolean inFlight = mJellyAnimator != null && mJellyAnimator.isRunning();
            if (mJellyAnimator != null) {
                mJellyAnimator.cancel();
            }

            for (int i = 0; i < count; i++) {
                boolean on = (i == sel);
                float dir = (i == sel) ? 0f : (i > sel ? 1f : -1f) * (rtl ? -1f : 1f);
                float x = dir * push;
                float s = on ? (1f + SWELL) : (1f - SHRINK);

                mTargetX[i] = x;
                mTargetSx[i] = s;
                mTargetSy[i] = s;

                if (instant) {
                    mCurrentX[i] = x;
                    mCurrentSx[i] = s;
                    mCurrentSy[i] = s;
                    RecyclerView.ViewHolder vh = mRecyclerView.findViewHolderForAdapterPosition(i);
                    if (vh != null && vh.itemView != null) {
                        vh.itemView.setTranslationX(x);
                        vh.itemView.setScaleX(s);
                        vh.itemView.setScaleY(s);
                        if (vh.itemView instanceof TextView) {
                            styleChipView((TextView) vh.itemView, on, 1f);
                        }
                    }
                } else {
                    mStartX[i] = mCurrentX[i];
                    mStartSx[i] = mCurrentSx[i];
                    mStartSy[i] = mCurrentSy[i];
                }
            }

            if (instant) {
                return;
            }

            final JellySpringPhysics.SpringConfig[] springsX = new JellySpringPhysics.SpringConfig[count];
            final JellySpringPhysics.SpringConfig[] springsSx = new JellySpringPhysics.SpringConfig[count];
            final JellySpringPhysics.SpringConfig[] springsSy = new JellySpringPhysics.SpringConfig[count];

            for (int i = 0; i < count; i++) {
                int far = Math.abs(i - sel);
                float k = STIFFNESS * (1f - 0.12f * Math.min(far, 3));
                springsX[i] = new JellySpringPhysics.SpringConfig(k, 0.9f, BOUNCE);
                float j = JELLY;
                springsSx[i] = new JellySpringPhysics.SpringConfig(
                        k * (1f + 0.24f * j),
                        Math.max(0.1f, 0.9f - 0.1f * j),
                        Math.min(0.85f, BOUNCE + 0.3f * j));
                springsSy[i] = new JellySpringPhysics.SpringConfig(
                        k * (1f - 0.14f * j),
                        0.9f + 0.05f * j,
                        BOUNCE);
            }

            final boolean hadInFlight = inFlight;
            mJellyAnimator = ValueAnimator.ofFloat(0f, 1f);
            mJellyAnimator.setDuration(450);
            mJellyAnimator.setInterpolator(new LinearInterpolator());
            mJellyAnimator.addUpdateListener(animation -> {
                long elapsedMs = animation.getCurrentPlayTime();
                for (int i = 0; i < count; i++) {
                    int far = Math.abs(i - sel);
                    long delayMs = hadInFlight ? 0 : (long) (far * STAGGER_MS);
                    float j = JELLY;
                    long delayYMs = delayMs + (long) (50f * j);

                    float tX = Math.max(0f, (elapsedMs - delayMs) / 1000f);
                    float tSx = Math.max(0f, (elapsedMs - delayMs) / 1000f);
                    float tSy = Math.max(0f, (elapsedMs - delayYMs) / 1000f);

                    float curX = JellySpringPhysics.eval(springsX[i], mStartX[i], 0f, mTargetX[i], tX);
                    float curSx = JellySpringPhysics.eval(springsSx[i], mStartSx[i], 0f, mTargetSx[i], tSx);
                    float curSy = JellySpringPhysics.eval(springsSy[i], mStartSy[i], 0f, mTargetSy[i], tSy);

                    mCurrentX[i] = curX;
                    mCurrentSx[i] = curSx;
                    mCurrentSy[i] = curSy;

                    RecyclerView.ViewHolder vh = mRecyclerView != null ? mRecyclerView.findViewHolderForAdapterPosition(i) : null;
                    if (vh != null && vh.itemView != null) {
                        vh.itemView.setTranslationX(curX);
                        vh.itemView.setScaleX(curSx);
                        vh.itemView.setScaleY(curSy);

                        if (vh.itemView instanceof TextView) {
                            boolean on = (i == sel);
                            float colorProgress = Math.min(1f, elapsedMs / 200f);
                            styleChipView((TextView) vh.itemView, on, colorProgress);
                        }
                    }
                }
            });
            mJellyAnimator.start();
        }

        private void styleChipView(TextView chip, boolean active, float progress) {
            Context context = chip.getContext();
            int bgTarget = active ? ACTIVE_COLOR : CHIP_COLOR;
            int textTarget = active ? ACTIVE_TEXT_COLOR : TEXT_COLOR;

            int bgColor = progress >= 1f ? bgTarget
                    : (int) mColorEvaluator.evaluate(progress, active ? CHIP_COLOR : ACTIVE_COLOR, bgTarget);
            int textColor = progress >= 1f ? textTarget
                    : (int) mColorEvaluator.evaluate(progress, active ? TEXT_COLOR : ACTIVE_TEXT_COLOR, textTarget);

            int radius = dp(context, 18);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(radius);
            shape.setColor(bgColor);
            if (!active) {
                shape.setStroke(dp(context, 1), 0x22FFFFFF);
            }

            int rippleColor = active ? 0x22000000 : 0x22FFFFFF;
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(radius);
            mask.setColor(Color.WHITE);

            RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
            chip.setBackground(ripple);
            chip.setTextColor(textColor);
        }
    }
}
