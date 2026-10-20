package com.yohu.urldown.ui.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

/**
 * 行尾的图标按钮：40dp 触控区、20dp 图标、无底色，按下时圆形水波。
 *
 * <p>没有文字，所以 contentDescription 必填，读屏和长按提示都靠它。
 */
@SuppressLint("ViewConstructor") // 只在代码里创建，不进 XML
public final class IconAction extends FrameLayout {

    private final ImageView icon;

    public IconAction(@NonNull Context context, @ColorInt int rippleColor) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(icon, new LayoutParams(Math.round(20 * d), Math.round(20 * d), Gravity.CENTER));
        // mask 为 null -> 无边界水波，以控件中心扩散成圆
        setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), null, null));
        setClickable(true);
        setFocusable(true);
        int size = Math.round(40 * d);
        setMinimumWidth(size);
        setMinimumHeight(size);
    }

    public void set(@DrawableRes int iconRes, @ColorInt int tint, @NonNull CharSequence label) {
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        setContentDescription(label);
        setTooltipText(label);
    }
}
