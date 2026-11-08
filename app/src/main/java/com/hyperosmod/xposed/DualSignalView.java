package com.hyperosmod.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Status bar container with two independent children: the network type text and the stacked
 * signal icon. Keeping them separate means changing the icon size never changes the text
 * size, and the icon can grow beyond the original signal slot.
 */
final class DualSignalView extends LinearLayout {
    private static final float TEXT_SIZE_DP = 9f;
    private static final float ICON_WIDTH_DP = 14f;
    private static final float ICON_HEIGHT_DP = 11f;

    private final TypeTextView typeView;
    private final SignalIconView iconView;
    private int textScalePercent = 100;
    private int textThicknessPercent = 100;

    DualSignalView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setWillNotDraw(true);

        float density = context.getResources().getDisplayMetrics().density;

        typeView = new TypeTextView(context);
        typeView.setSingleLine(true);
        typeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP);
        typeView.setTextColor(Color.WHITE);
        addView(typeView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        iconView = new SignalIconView(context);
        LayoutParams iconParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        iconParams.setMarginStart(Math.round(1.5f * density));
        addView(iconView, iconParams);
    }

    void setScale(int iconScalePercent, int iconThicknessPercent,
                  int textScalePercent, int textThicknessPercent) {
        iconView.setScale(iconScalePercent, iconThicknessPercent);
        int nextTextScale = Math.max(75, Math.min(200, textScalePercent));
        int nextTextThickness = Math.max(50, Math.min(200, textThicknessPercent));
        if (nextTextScale == this.textScalePercent
                && nextTextThickness == this.textThicknessPercent) {
            return;
        }
        this.textScalePercent = nextTextScale;
        this.textThicknessPercent = nextTextThickness;
        typeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP,
                TEXT_SIZE_DP * nextTextScale / 100f);
        typeView.setStrokeWidthDp((nextTextThickness - 50) / 150f * 0.9f);
    }

    void setState(String type, int dataLevel, int otherLevel, int color) {
        String nextType = type == null ? "" : type;
        typeView.setText(nextType);
        typeView.setTextColor(color);
        typeView.setVisibility(nextType.isEmpty() ? GONE : VISIBLE);
        iconView.setState(dataLevel, otherLevel, color);
    }

    /** TextView with a configurable outline stroke, used as a lightweight text-weight control. */
    private static final class TypeTextView extends TextView {
        private float strokeWidthPx;

        TypeTextView(Context context) {
            super(context);
            setIncludeFontPadding(false);
            setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        void setStrokeWidthDp(float dp) {
            float px = Math.max(0f, dp) * getResources().getDisplayMetrics().density;
            if (px == strokeWidthPx) {
                return;
            }
            strokeWidthPx = px;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (strokeWidthPx <= 0f) {
                super.onDraw(canvas);
                return;
            }
            Paint paint = getPaint();
            Paint.Style previousStyle = paint.getStyle();
            float previousWidth = paint.getStrokeWidth();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidthPx);
            paint.setStrokeJoin(Paint.Join.ROUND);
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(previousWidth);
            super.onDraw(canvas);
            paint.setStyle(previousStyle);
        }
    }

    /**
     * Draws the data SIM as staircase bars and the secondary SIM as a row of dots. The view
     * itself is sized from the user scale, so the bars can actually grow instead of being
     * capped by the original signal image height.
     */
    private static final class SignalIconView extends View {
        private static final int BAR_COUNT = 4;

        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();
        private final float density;

        private int dataLevel;
        private int otherLevel;
        private int color = Color.WHITE;
        private int scalePercent = 100;
        private int thicknessPercent = 100;

        SignalIconView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            barPaint.setStyle(Paint.Style.FILL);
            setWillNotDraw(false);
        }

        void setScale(int scalePercent, int thicknessPercent) {
            int nextScale = Math.max(75, Math.min(200, scalePercent));
            int nextThickness = Math.max(50, Math.min(200, thicknessPercent));
            if (nextScale == this.scalePercent && nextThickness == this.thicknessPercent) {
                return;
            }
            this.scalePercent = nextScale;
            this.thicknessPercent = nextThickness;
            requestLayout();
            invalidate();
        }

        void setState(int dataLevel, int otherLevel, int color) {
            if (this.dataLevel == dataLevel && this.otherLevel == otherLevel
                    && this.color == color) {
                return;
            }
            this.dataLevel = dataLevel;
            this.otherLevel = otherLevel;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = resolveSize(Math.round(ICON_WIDTH_DP * density * scale()), widthSpec);
            int height = resolveSize(Math.round(ICON_HEIGHT_DP * density * scale()), heightSpec);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) {
                return;
            }
            // Keep the two rows tightly packed: the gap must not grow with the icon size.
            float rowGap = 0.4f * density;
            float rowHeight = (height - rowGap) / 2f;
            drawBars(canvas, 0f, 0f, rowHeight, width, dataLevel);
            drawDots(canvas, 0f, rowHeight + rowGap, rowHeight, width, otherLevel);
        }

        private void drawBars(Canvas canvas, float left, float top, float rowHeight,
                              float barsWidth, int level) {
            if (rowHeight < 0.5f * density) {
                return;
            }
            float cell = barsWidth / BAR_COUNT;
            // Scale the bar width with both the icon size and the thickness slider. The
            // previous fixed 2.8dp cap meant the slider had no visible effect once the
            // icon was enlarged; the cell-relative cap keeps the bars from touching.
            float barWidth = Math.min(cell * 0.45f * thickness(), cell * 0.82f);
            float gap = (barsWidth - barWidth * BAR_COUNT) / (BAR_COUNT - 1);
            if (barWidth <= 0f || gap < 0f) {
                return;
            }
            float radius = Math.min(barWidth / 2f, 1f * density);
            for (int i = 0; i < BAR_COUNT; i++) {
                float factor = 0.30f + i * 0.20f;
                float barHeight = Math.min(rowHeight,
                        Math.max(1f * density, rowHeight * factor));
                float start = left + i * (barWidth + gap);
                barRect.set(start, top + rowHeight - barHeight,
                        start + barWidth, top + rowHeight);
                barPaint.setColor(i < level ? color : dim(color));
                canvas.drawRoundRect(barRect, radius, radius, barPaint);
            }
        }

        private void drawDots(Canvas canvas, float left, float top, float rowHeight,
                              float barsWidth, int level) {
            if (rowHeight < 0.5f * density) {
                return;
            }
            float cell = barsWidth / BAR_COUNT;
            float radius = Math.min(cell * 0.24f * thickness(), 2.1f * density);
            if (radius <= 0f) {
                return;
            }
            float centerY = top + rowHeight / 2f;
            for (int i = 0; i < BAR_COUNT; i++) {
                float centerX = left + cell * (i + 0.5f);
                barPaint.setColor(i < level ? color : dim(color));
                canvas.drawCircle(centerX, centerY, radius, barPaint);
            }
        }

        private static int dim(int color) {
            int alpha = Math.max(24, Color.alpha(color) / 3);
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        private float scale() {
            return scalePercent / 100f;
        }

        private float thickness() {
            return thicknessPercent / 100f;
        }
    }
}
