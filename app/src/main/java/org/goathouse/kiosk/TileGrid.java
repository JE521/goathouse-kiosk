package org.goathouse.kiosk;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out in equal columns; children tagged WIDE take a whole row. Column count follows the width. */
final class TileGrid extends ViewGroup {
    static final Object WIDE = new Object();
    private final int gap, minCol;

    TileGrid(Context c) {
        super(c);
        gap = Ui.dp(c, 12);
        minCol = Ui.dp(c, 150);
    }

    private int columns(int width) { return Math.max(2, Math.min(6, (width + gap) / (minCol + gap))); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int width = MeasureSpec.getSize(wSpec);
        int cols = columns(width);
        int colW = (width - gap * (cols - 1)) / cols;
        int x = 0, rowH = 0, total = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            boolean wide = v.getTag() == WIDE;
            if (wide && x > 0) { total += rowH + gap; x = 0; rowH = 0; }
            int w = wide ? width : colW;
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowH = Math.max(rowH, v.getMeasuredHeight());
            x++;
            if (wide || x == cols) { total += rowH + gap; x = 0; rowH = 0; }
        }
        if (x > 0) total += rowH + gap;
        setMeasuredDimension(width, Math.max(0, total - gap));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int cols = columns(width);
        int colW = (width - gap * (cols - 1)) / cols;
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            boolean wide = v.getTag() == WIDE;
            if (wide && x > 0) { y += rowH + gap; x = 0; rowH = 0; }
            int left = wide ? 0 : x * (colW + gap);
            v.layout(left, y, left + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            rowH = Math.max(rowH, v.getMeasuredHeight());
            x++;
            if (wide || x == cols) { y += rowH + gap; x = 0; rowH = 0; }
        }
    }
}
