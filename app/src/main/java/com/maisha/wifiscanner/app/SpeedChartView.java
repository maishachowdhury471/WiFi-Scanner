package com.maisha.wifiscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple bar chart of the last speed-test download results.
 * No external chart library — plain Canvas drawing.
 */
public class SpeedChartView extends View {

    private List<Float> values = new ArrayList<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SpeedChartView(Context context) {
        super(context);
        init();
    }

    public SpeedChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setColor(0xFF1d4ed8);
        textPaint.setColor(0xFF334155);
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(0xFF64748b);
        emptyPaint.setTextSize(34f);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<Float> v) {
        values = v == null ? new ArrayList<>() : new ArrayList<>(v);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (values.isEmpty()) {
            canvas.drawText("এখনো কোনো গতি মাপা হয়নি", w / 2f, h / 2f, emptyPaint);
            return;
        }
        float max = 10f;
        for (float v : values) if (v > max) max = v;
        int n = values.size();
        float slot = (float) w / n;
        float barW = Math.min(slot * 0.6f, 90f);
        float base = h - 12f;
        float topPad = 44f;
        for (int i = 0; i < n; i++) {
            float v = values.get(i);
            float bh = (v / max) * (h - topPad - 20f);
            float cx = slot * i + slot / 2f;
            float top = base - bh;
            canvas.drawRect(cx - barW / 2f, top, cx + barW / 2f, base, barPaint);
            String label = String.format(Locale.US, "%.0f", v);
            canvas.drawText(label, cx, Math.max(top - 8f, 34f), textPaint);
        }
    }
}
