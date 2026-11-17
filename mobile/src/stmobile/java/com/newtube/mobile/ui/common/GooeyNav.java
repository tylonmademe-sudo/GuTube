package com.newtube.mobile.ui.common;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GUTUBE: Native Android port of the React Bits <GooeyNav /> component.
 * Extends {@link GooeyNavLayout} to provide the complete React Bits navigation engine with
 * AGSL RuntimeShader, RenderEffect metaball GPU pipelines, mass-spring physics, and
 * glitch-free highlight coordinate tracking.
 */
public class GooeyNav extends GooeyNavLayout {

    public GooeyNav(@NonNull Context context) {
        super(context);
    }

    public GooeyNav(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }
}
