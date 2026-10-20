package com.yohu.urldown.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import com.yohu.youi.api.common.YoCorner;

/**
 * 一组行的承载面：圆角纯色底、不投影，行之间画一条从文字起点开始的细分隔线。
 *
 * <p>不用 YoCard 是因为卡片内容区自带 16dp 内边距，行的按压水波到不了边；
 * 这里让行自己决定内边距，水波铺满整行，圆角由 outline 裁切。
 */
@SuppressLint("ViewConstructor") // 只在代码里创建，不进 XML
public final class GroupSurface extends LinearLayout {

    private final int dividerColor;
    private final int dividerInset;

    public GroupSurface(@NonNull Context context, @ColorInt int surface, @ColorInt int divider,
                        int dividerInsetPx) {
        super(context);
        setOrientation(VERTICAL);
        float d = context.getResources().getDisplayMetrics().density;
        float radius = 16 * d;
        setBackground(YoCorner.fill(surface, YoCorner.Radii.uniform(radius)));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        this.dividerColor = divider;
        this.dividerInset = dividerInsetPx;
    }

    /** 追加一行；不是第一行时先补一条分隔线。 */
    public void addRow(@NonNull View row) {
        if (getChildCount() > 0) {
            View line = new View(getContext());
            line.setBackgroundColor(dividerColor);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    Math.max(1, Math.round(0.5f * getResources().getDisplayMetrics().density)));
            lp.leftMargin = dividerInset;
            addView(line, lp);
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }
}
