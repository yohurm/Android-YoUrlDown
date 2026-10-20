package com.yohu.urldown.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 底部页签下的一页。
 *
 * <p>YoTabs 是窗口级根容器，页内不能再套 YoPage（两者都消费系统栏），
 * 所以每页自己画标题，并按 {@link #setBottomInset} 让出悬浮栏的高度。
 */
public interface TabPage {

    @NonNull
    View getView();

    /** 页内主滚动视图，交给 YoTabs 驱动滚动隐藏；没有则返回 null。 */
    @Nullable
    View getScroller();

    /** 底部需要让出的高度（悬浮栏 + 导航条，或键盘）。 */
    void setBottomInset(int insetPx);

    /** 切到本页。 */
    default void onSelected() {
    }

    /** 再次点击当前页签：回到顶部。 */
    default void scrollToTop() {
    }

    void destroy();
}
