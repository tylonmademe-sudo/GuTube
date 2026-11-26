package com.newtube.mobile.ui.browse;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.newtube.mobile.ui.common.UnifiedPillNavView;

import java.util.ArrayList;
import java.util.List;

/**
 * GUTUBE: Home top categories adapter over {@link UnifiedPillNavView}.
 * Preserves {@link FeedStrip.Item} adapter API while sharing 100% of rendering and
 * spring physics with every other horizontal pill bar in the app.
 */
public class CategoryPillNavView extends UnifiedPillNavView {

    public interface OnChipClickListener {
        void onChipClick(FeedStrip.Item item, int index);
    }

    private final List<FeedStrip.Item> mItems = new ArrayList<>();
    @Nullable private OnChipClickListener mChipListener;

    public CategoryPillNavView(@NonNull Context context) {
        this(context, null);
    }

    public CategoryPillNavView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CategoryPillNavView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFixedMode(false);
        setOnTabSelectedListener((index, reselected) -> {
            if (mChipListener != null && index >= 0 && index < mItems.size()) {
                mChipListener.onChipClick(mItems.get(index), index);
            }
        });
    }

    public void setOnChipClickListener(@Nullable OnChipClickListener listener) {
        mChipListener = listener;
    }

    public void setItems(@Nullable List<FeedStrip.Item> items, long selectedId) {
        mItems.clear();
        List<CharSequence> labels = new ArrayList<>();
        if (items != null) {
            mItems.addAll(items);
            for (FeedStrip.Item item : items) {
                labels.add(item.label != null ? item.label : "");
            }
        }
        int targetIndex = findIndexById(selectedId);
        if (targetIndex < 0 && !mItems.isEmpty()) {
            targetIndex = 0;
        }
        setLabels(labels, targetIndex);
    }

    public void setSelectedId(long selectedId) {
        int index = findIndexById(selectedId);
        if (index >= 0) {
            selectIndex(index, isAttachedToWindow() && isShown());
        }
    }

    public void selectIndex(int index, boolean animate) {
        setSelectedIndex(index, animate);
    }

    private int findIndexById(long id) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }
}
