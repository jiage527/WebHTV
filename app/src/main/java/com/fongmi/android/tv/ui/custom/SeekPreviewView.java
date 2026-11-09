package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * 拖动进度条时显示的画面预览浮层。
 *
 * <p>只负责显示：抽帧、缓存与去重都在 {@code SeekPreviewExtractor}。位置由
 * {@link CustomSeekView} 按进度条的实际坐标算好后传进来，本类不做业务计算。
 */
public class SeekPreviewView extends FrameLayout {

    private static final int WIDTH_DP = 160;
    private static final int CORNER_DP = 5;
    private static final int MARGIN_DP = 6;

    private final ImageView image;
    private final int width;
    private final int height;

    public SeekPreviewView(Context context) {
        this(context, null);
    }

    public SeekPreviewView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SeekPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        width = ResUtil.dp2px(WIDTH_DP);
        height = Math.round(width * 9f / 16f);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setBackground(background());
        addView(image, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(GONE);
    }

    /** 半透明圆角底：有画面时提供边界，未抽到帧时充当占位框。 */
    private GradientDrawable background() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#CC000000"));
        drawable.setCornerRadius(ResUtil.dp2px(CORNER_DP));
        drawable.setStroke(ResUtil.dp2px(1), Color.parseColor("#33FFFFFF"));
        return drawable;
    }

    /**
     * 显示预览。
     *
     * @param frame 画面；null 表示还没抽到，此时显示占位框
     * @param left  左上角横坐标，已由调用方限定在本 View 父容器内
     * @param top   左上角纵坐标
     */
    public void show(@Nullable Bitmap frame, int left, int top) {
        image.setImageBitmap(frame);
        setVisibility(VISIBLE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        setLayoutParams(params);
    }

    public void hide() {
        setVisibility(GONE);
        image.setImageBitmap(null);
    }

    public int previewWidth() {
        return width;
    }

    public int previewHeight() {
        return height;
    }

    /** 浮层与进度条之间的垂直间隙。 */
    public static int gap(Context context) {
        return ResUtil.dp2px(MARGIN_DP);
    }

    @Override
    protected void onDetachedFromWindow() {
        // 缩略图由外部缓存持有，这里只解除引用，避免已回收的 View 继续持有 Bitmap。
        image.setImageBitmap(null);
        super.onDetachedFromWindow();
    }
}
