package com.yohu.urldown.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

/**
 * 任务缩略图，也是任务的平台标识位：圆角方块。
 *
 * <ul>
 *   <li>没有封面 / 本地文件时：浅底 + 居中的平台图标（{@link PlatformMark}）；</li>
 *   <li>有图时：居中裁切铺满，右下角压一个小平台图标（带一圈卡面色描边），
 *       平台标识始终只在这一个位置出现。</li>
 * </ul>
 * 状态不在这里表达：进度条、说明文字已经说了。
 */
public final class TaskThumb extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float density;

    @Nullable
    private Drawable mark;
    @DrawableRes
    private int markRes;
    @Nullable
    private Bitmap bitmap;
    @Nullable
    private BitmapShader shader;
    /** 绑定键：异步加载回来时用它判断这个控件是不是已经换绑成别的任务。 */
    @Nullable
    private String key;

    public TaskThumb(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** @param fill 无图时的底色；@param ring 小平台图标外圈（与所在卡面同色） */
    public void setColors(@ColorInt int fill, @ColorInt int ring) {
        fillPaint.setColor(fill);
        ringPaint.setColor(ring);
        invalidate();
    }

    /** @param platformIcon {@link PlatformMark#iconOf} 给出的图标 */
    public void bind(@NonNull String key, @DrawableRes int platformIcon) {
        if (!key.equals(this.key)) {
            this.key = key;
            this.bitmap = null;
            this.shader = null;
            imagePaint.setShader(null);
        }
        if (platformIcon != markRes || mark == null) {
            markRes = platformIcon;
            mark = AppCompatResources.getDrawable(getContext(), platformIcon);
        }
        invalidate();
    }

    @Nullable
    public String key() {
        return key;
    }

    public void setBitmap(@NonNull String forKey, @Nullable Bitmap bmp) {
        if (!forKey.equals(key)) {
            return;
        }
        bitmap = bmp;
        shader = bmp == null ? null
                : new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        imagePaint.setShader(shader);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(48 * density);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) * 0.22f;
        rect.set(0, 0, w, h);
        boolean hasImage = bitmap != null && shader != null && !bitmap.isRecycled();
        if (hasImage) {
            float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - bitmap.getWidth() * scale) / 2f, (h - bitmap.getHeight() * scale) / 2f);
            shader.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, r, r, imagePaint);
        } else {
            canvas.drawRoundRect(rect, r, r, fillPaint);
        }
        if (mark == null) {
            return;
        }
        if (hasImage) {
            // 封面上的小标：16dp，贴右下角，外圈 1.5dp 卡面色把它和图分开
            int s = Math.round(16 * density);
            float ring = 1.5f * density;
            int left = Math.round(w - s - 3 * density);
            int top = Math.round(h - s - 3 * density);
            float rr = s * 0.28f + ring;
            canvas.drawRoundRect(left - ring, top - ring, left + s + ring, top + s + ring, rr, rr, ringPaint);
            mark.setBounds(left, top, left + s, top + s);
        } else {
            int s = Math.round(Math.min(w, h) * 0.5f);
            int left = Math.round((w - s) / 2f);
            int top = Math.round((h - s) / 2f);
            mark.setBounds(left, top, left + s, top + s);
        }
        mark.draw(canvas);
    }
}
