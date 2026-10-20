package com.yohu.urldown.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/**
 * 细进度条：4dp 高、圆头、无文字。
 *
 * <p>确定进度时平滑过渡到新值（进度回调 200ms 一次，直接跳会一顿一顿）；
 * 不确定时（解析 / 合并 / 还没拿到总大小）一段 30% 宽的色块循环滑动。
 * 不可见或脱离窗口时停掉动画，不在后台空转。
 */
public final class ProgressLine extends View {

    private static final long SMOOTH_MS = 240L;
    private static final long SWEEP_MS = 1300L;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float shown;
    private boolean indeterminate;
    private float sweep;
    private ValueAnimator smooth;
    private ValueAnimator sweeper;

    public ProgressLine(@NonNull Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setColors(@ColorInt int trackColor, @ColorInt int fillColor) {
        track.setColor(trackColor);
        fill.setColor(fillColor);
        invalidate();
    }

    /**
     * @param progress 0..1；小于 0 表示不确定
     * @param animate  是否从当前值平滑过渡（首次绑定时传 false）
     */
    public void setProgress(float progress, boolean animate) {
        if (progress < 0f) {
            setIndeterminate(true);
            return;
        }
        setIndeterminate(false);
        float target = Math.min(1f, progress);
        if (smooth != null) {
            smooth.cancel();
        }
        if (!animate || !isAttachedToWindow() || target < shown) {
            shown = target;
            invalidate();
            return;
        }
        smooth = ValueAnimator.ofFloat(shown, target).setDuration(SMOOTH_MS);
        smooth.setInterpolator(new DecelerateInterpolator());
        smooth.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        smooth.start();
    }

    private void setIndeterminate(boolean value) {
        if (indeterminate == value) {
            return;
        }
        indeterminate = value;
        updateSweeper();
        invalidate();
    }

    private void updateSweeper() {
        boolean run = indeterminate && isAttachedToWindow() && getVisibility() == VISIBLE;
        if (run && sweeper == null) {
            sweeper = ValueAnimator.ofFloat(0f, 1f).setDuration(SWEEP_MS);
            sweeper.setRepeatCount(ValueAnimator.INFINITE);
            sweeper.setInterpolator(new LinearInterpolator());
            sweeper.addUpdateListener(a -> {
                sweep = (float) a.getAnimatedValue();
                invalidate();
            });
            sweeper.start();
        } else if (!run && sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSweeper();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (smooth != null) {
            smooth.cancel();
        }
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateSweeper();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Math.round(4 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);
        if (indeterminate) {
            float seg = w * 0.3f;
            float left = -seg + (w + seg) * sweep;
            canvas.save();
            canvas.clipRect(0, 0, w, h);
            rect.set(Math.max(0, left), 0, Math.min(w, left + seg), h);
            if (rect.width() > 0) {
                canvas.drawRoundRect(rect, r, r, fill);
            }
            canvas.restore();
        } else if (shown > 0f) {
            rect.set(0, 0, Math.max(h, w * shown), h);
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }
}
