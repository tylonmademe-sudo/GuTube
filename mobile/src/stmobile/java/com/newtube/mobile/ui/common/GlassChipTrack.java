package com.newtube.mobile.ui.common;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GUTUBE(glass segmented / gooey nav): segmented navigation track powered by the complete
 * native {@link GooeyNavLayout} engine with AGSL RuntimeShader, RenderEffect metaball GPU
 * pipelines, mass-spring physics, and glitch-free highlight coordinate tracking.
 */
public class GlassChipTrack extends GooeyNavLayout {

    public GlassChipTrack(@NonNull Context context) {
        super(context);
    }

    public GlassChipTrack(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }
}
