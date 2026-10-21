package com.yohu.urldown.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.youi.api.common.YoCorner;
import com.yohu.youi.api.theme.YoColors;
import com.yohu.youi.api.theme.YoFonts;

/**
 * 页面搭建的小工具：尺寸换算、统一字号层级、页标题。
 *
 * <p>颜色一律取 {@link YoColors}，字体取 {@link YoFonts}，不在应用里写死色值，
 * 深浅色切换时跟随 Kit。
 */
public final class Ui {

    /** 页大标题（对齐 HDS 一级页大标题）。 */
    public static final float TEXT_PAGE_TITLE = 30f;
    public static final float TEXT_CARD_TITLE = 17f;
    public static final float TEXT_BODY = 14f;
    public static final float TEXT_CAPTION = 12f;

    /** 页面左右留白。 */
    public static final int PAGE_PADDING_DP = 16;

    private Ui() {
    }

    public static int dp(@NonNull Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    @NonNull
    public static TextView text(@NonNull Context context, @Nullable CharSequence text,
                                float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        YoFonts.applyRegular(view);
        return view;
    }

    @NonNull
    public static TextView medium(@NonNull Context context, @Nullable CharSequence text,
                                  float sizeSp, int color) {
        TextView view = text(context, text, sizeSp, color);
        YoFonts.applyMedium(view);
        return view;
    }

    @NonNull
    public static TextView bold(@NonNull Context context, @Nullable CharSequence text,
                                float sizeSp, int color) {
        TextView view = text(context, text, sizeSp, color);
        YoFonts.applyBold(view);
        return view;
    }

    /** 一级页标题区：大标题 + 可选副标题。返回的副标题 TextView 可后续更新。 */
    @NonNull
    public static PageHeader pageHeader(@NonNull Context context, @NonNull YoColors colors,
                                        @NonNull CharSequence title) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, PAGE_PADDING_DP + 8), dp(context, 20),
                dp(context, PAGE_PADDING_DP + 8), dp(context, 12));
        TextView titleView = bold(context, title, TEXT_PAGE_TITLE, colors.textPrimary());
        box.addView(titleView);
        TextView subtitle = text(context, null, 13f, colors.textSecondary());
        subtitle.setPadding(0, dp(context, 4), 0, 0);
        subtitle.setVisibility(View.GONE);
        box.addView(subtitle);
        return new PageHeader(box, subtitle);
    }

    /** 分组标题：卡片组上方的小字。 */
    @NonNull
    public static TextView sectionCaption(@NonNull Context context, @NonNull YoColors colors,
                                          @NonNull CharSequence text) {
        TextView view = medium(context, text, 13f, colors.textSecondary());
        view.setPadding(dp(context, 12), dp(context, 20), dp(context, 12), dp(context, 8));
        return view;
    }

    /** 小胶囊标签（平台名 / 状态）。 */
    @NonNull
    public static TextView chip(@NonNull Context context, @NonNull CharSequence text,
                                int textColor, int fillColor) {
        TextView view = medium(context, text, TEXT_CAPTION, textColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        view.setBackground(YoCorner.fill(fillColor, YoCorner.Radii.uniform(dp(context, 100))));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** 颜色加透明度，用于品牌色浅底。 */
    public static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @NonNull
    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @NonNull
    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 页标题区的两个可变部分。 */
    public static final class PageHeader {
        public final LinearLayout view;
        public final TextView subtitle;

        PageHeader(LinearLayout view, TextView subtitle) {
            this.view = view;
            this.subtitle = subtitle;
        }

        public void setSubtitle(@Nullable CharSequence text) {
            subtitle.setText(text);
            subtitle.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
    }
}
