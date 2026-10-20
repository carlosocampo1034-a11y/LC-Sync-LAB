package com.lc.technician.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class BarChartView extends View {

    private int[] values  = new int[0];
    private String[] labels = new String[0];

    private final Paint pBar   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCount = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BarChartView(Context ctx) { super(ctx); init(); }
    public BarChartView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public BarChartView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        pBar.setColor(Color.parseColor("#EB1700"));
        pCount.setColor(Color.rgb(25, 25, 25));
        pCount.setTextSize(11f * density);
        pCount.setFakeBoldText(true);
        pCount.setTextAlign(Paint.Align.CENTER);
        pLabel.setColor(Color.rgb(118, 118, 118));
        pLabel.setTextSize(10f * density);
        pLabel.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(int[] values, String[] labels) {
        this.values = values;
        this.labels = labels;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (160 * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (values == null || values.length == 0) return;
        float density = getResources().getDisplayMetrics().density;

        int n = values.length;
        float w = getWidth();
        float h = getHeight();
        float labelH = 16f * density;
        float countH = 16f * density;
        float barArea = h - labelH - countH - 4 * density;

        int max = 0;
        for (int v : values) if (v > max) max = v;
        if (max == 0) max = 1;

        float barW = (w / n) * 0.55f;
        float gap  = w / n;
        float radius = 4f * density;

        for (int i = 0; i < n; i++) {
            float cx = gap * i + gap / 2f;
            float barH = (values[i] / (float) max) * barArea;
            float top  = countH + (barArea - barH);
            float left = cx - barW / 2f;
            float right = cx + barW / 2f;
            float bottom = countH + barArea;

            if (barH > 0) {
                RectF rect = new RectF(left, top, right, bottom);
                canvas.drawRoundRect(rect, radius, radius, pBar);
                // square off bottom corners
                if (barH > radius) {
                    canvas.drawRect(left, top + radius, right, bottom, pBar);
                }
            }

            // count above bar
            if (values[i] > 0) {
                canvas.drawText(String.valueOf(values[i]), cx, top - 2 * density, pCount);
            }

            // label below
            String lbl = (i < labels.length) ? labels[i] : "";
            canvas.drawText(lbl, cx, h - 2 * density, pLabel);
        }
    }
}
