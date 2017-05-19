package com.github.teocci.ffmpeg.recorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017-May-19
 */
public class FixedRatioCroppedTextureView extends TextureView
{
    private int previewWidth;
    private int previewHeight;
    private int croppedWidthWeight;
    private int croppedHeightWeight;

    public FixedRatioCroppedTextureView(Context context)
    {
        super(context);
    }

    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
    }

    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs, int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public FixedRatioCroppedTextureView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes)
    {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
    {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = this.getMeasuredWidth();
        setMeasuredDimension(width, width * croppedHeightWeight / croppedWidthWeight);
    }

    @Override
    public void layout(int l, int t, int r, int b)
    {
        int actualPreviewWidth = r - l;
        int actualPreviewHeight = actualPreviewWidth * previewHeight / previewWidth;
        int top = t + ((b - t) - actualPreviewHeight) / 2;
        super.layout(l, top, r, top + actualPreviewHeight);
    }

    public void setPreviewSize(int previewWidth, int previewHeight)
    {
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
    }

    public void setCroppedSizeWeight(int widthWeight, int heightWeight)
    {
        this.croppedWidthWeight = widthWeight;
        this.croppedHeightWeight = heightWeight;
    }
}
