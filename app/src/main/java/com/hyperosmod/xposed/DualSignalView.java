package com.hyperosmod.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 状态栏双排信号的自定义容器：左边网络类型文字，右边上下两排圆点。
 *
 * <p>文字和图标是两个独立子 View，调图标大小不会带动文字，反之亦然。</p>
 *
 * <p>两排都是圆点：每排四颗、共用同一组列心（严格上下对齐）、两排半径完全相同；行距在
 * 保证不粘连的前提下压到最窄，并且随半径变化——粗细滑块拉满、圆点变大时缝也一起变大，
 * 所以无论怎么调都不会碰在一起。颜色取状态栏前景色（系统 tint），任何主题下都不会糊。</p>
 */
final class DualSignalView extends LinearLayout {
    private static final float TEXT_SIZE_DP = 9f;
    private static final float ICON_WIDTH_DP = 14f;
    private static final float ICON_HEIGHT_DP = 11f;

    private final TypeTextView typeView;
    private final SignalDotView iconView;
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

        iconView = new SignalDotView(context);
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

    /**
     * 两排都改成自绘圆点后，主题 drawable 不再参与渲染，这里只登记基准尺寸：
     * 主题给不给 drawable 都不影响圆点的绘制。
     */
    void setThemeIcons(Drawable top, Drawable bottom, boolean tint,
                       int baseW, int baseH, int rowGap) {
        iconView.setBaseSize(baseW, baseH);
    }

    /**
     * 旁边电池 / WiFi 图标的实测高度，作为整块双排圆点的对齐标尺。
     *
     * <p>系统 dimen 和主题实际画出来的尺寸经常对不上，直接量隔壁那颗才不会「比电池高一截」。</p>
     */
    boolean setReferenceHeight(int px) {
        return iconView.setReferenceHeight(px);
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
     * 两排圆点。
     *
     * <p>横向四等分，两排共用同一组列心 → 严格上下对齐；两排等高、半径相同 → 视觉对称。
     * 行距先按"零行距"估一次半径再推出来，因此粗细滑块拉到 200% 时缝会同步变大，圆点永远
     * 不会连成一条；而正常档位下缝又窄到几乎感觉不到。</p>
     */
    private static final class SignalDotView extends View {
        private static final int DOT_COUNT = 4;
        private static final float MIN_SCALE = 0.75f;
        private static final float MAX_SCALE = 2.0f;
        private static final float MIN_THICKNESS = 0.5f;
        private static final float MAX_THICKNESS = 2.0f;
        /** 圆点半径占单元格宽度的比例（再乘粗细系数）。 */
        private static final float RADIUS_RATIO = 0.30f;
        /** 两排之间的净缝 = 半径 × 这个系数。跟着半径走，所以粗细无论怎么调都不会粘连。 */
        private static final float GAP_RATIO = 0.5f;

        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        private int dataLevel;
        private int otherLevel;
        private int color = Color.WHITE;
        private int scalePercent = 100;
        private int thicknessPercent = 100;
        private int baseWidth;
        private int baseHeight;
        private int referenceHeight;

        SignalDotView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            dotPaint.setStyle(Paint.Style.FILL);
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

        void setBaseSize(int baseW, int baseH) {
            if (baseW == baseWidth && baseH == baseHeight) {
                return;
            }
            baseWidth = baseW;
            baseHeight = baseH;
            requestLayout();
        }

        boolean setReferenceHeight(int px) {
            if (px == referenceHeight) {
                return false;
            }
            referenceHeight = px;
            requestLayout();
            invalidate();
            return true;
        }

        private float scale() {
            return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scalePercent / 100f));
        }

        private float thickness() {
            return Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, thicknessPercent / 100f));
        }

        private int resolvedBaseWidth() {
            return baseWidth > 0 ? baseWidth : Math.round(ICON_WIDTH_DP * density);
        }

        private int resolvedBaseHeight() {
            return baseHeight > 0 ? baseHeight : Math.round(ICON_HEIGHT_DP * density);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = resolveSize(Math.round(resolvedBaseWidth() * scale()), widthSpec);
            int total = referenceHeight > 0
                    ? referenceHeight
                    : Math.round(resolvedBaseHeight() * scale() * 2f);
            int height = resolveSize(total, heightSpec);
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
            float cell = width / DOT_COUNT;
            float[] centres = columnCentres(cell);

            // 半径先由单元格宽度和粗细决定，再受"两排 + 缝必须塞进总高"约束。
            // 关键点：不再均分行高、把圆点摆在行中间——那样圆点变小时多出来的行内空白会变成
            // 一道大缝。这里直接按半径排圆心，缝永远是 GAP_RATIO × 半径。
            float dotRadius = Math.min(cell * RADIUS_RATIO * thickness(),
                    height / (4f + GAP_RATIO));
            dotRadius = Math.max(0.5f * density, dotRadius);
            float gap = dotRadius * GAP_RATIO;

            // 整块（两排圆 + 缝）在可用高度里垂直居中。
            float content = dotRadius * 4f + gap;
            float top = Math.max(0f, (height - content) / 2f);
            float firstCenterY = top + dotRadius;
            float secondCenterY = firstCenterY + dotRadius * 2f + gap;

            drawRow(canvas, centres, firstCenterY, dataLevel, dotRadius);
            drawRow(canvas, centres, secondCenterY, otherLevel, dotRadius);
        }

        private float[] columnCentres(float cell) {
            float[] centres = new float[DOT_COUNT];
            for (int i = 0; i < DOT_COUNT; i++) {
                centres[i] = cell * (i + 0.5f);
            }
            return centres;
        }

        private void drawRow(Canvas canvas, float[] centres, float centerY,
                             int level, float radius) {
            if (radius <= 0f) {
                return;
            }
            for (int i = 0; i < centres.length; i++) {
                dotPaint.setColor(i < level ? color : dim(color));
                canvas.drawCircle(centres[i], centerY, radius, dotPaint);
            }
        }

        private static int dim(int color) {
            int alpha = Math.max(24, Color.alpha(color) / 3);
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
