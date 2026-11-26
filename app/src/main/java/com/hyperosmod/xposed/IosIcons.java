package com.hyperosmod.xposed;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * iOS 风格状态栏图标：蜂窝圆点柱、实心 WiFi、药丸电池（数字在图标内）。
 *
 * <p>全部自绘，不依赖主题里的 png，所以换主题也不会变形；尺寸取原图标的 bounds，
 * 只改"画什么"，不改"占多大地方"。</p>
 */
final class IosIcons {

    private IosIcons() {
    }

    /** 诊断用：一块醒目的红块，确认替换是否真的画到了屏幕上。 */
    static final class Marker extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Marker() {
            paint.setColor(0xFFFF1744);
            paint.setAlpha(150);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawRect(b, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 信号：4 根圆头竖条，按电平点亮；0 格时画成细的灰条。 */
    static final class Signal extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int level = 4;
        private int color = Color.WHITE;
        private boolean dataActivity;

        Signal() {
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setTintColor(int value) {
            color = value;
            invalidateSelf();
        }

        void setDataActivity(boolean value) {
            dataActivity = value;
            invalidateSelf();
        }

        void setSignalLevel(int value) {
            if (level != value) {
                level = value;
                invalidateSelf();
            }
        }

        @Override
        protected boolean onLevelChange(int value) {
            level = value;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = b.height();
            float w = b.width();
            float barW = Math.max(2f, w / 7.5f);        // 4 根条 + 间隙
            float gap = barW * 0.85f;
            float totalW = barW * 4 + gap * 3;
            float left = (w - totalW) / 2f;
            float baseY = b.bottom - h * 0.06f;
            float minH = h * 0.30f;
            float maxH = h * 0.92f;
            int lit = Math.max(1, Math.min(4, level));

            paint.setColor(color);
            for (int i = 0; i < 4; i++) {
                float bh = minH + (maxH - minH) * (i / 3f);
                float x = left + i * (barW + gap);
                int alpha = i < lit ? 255 : 55;
                paint.setAlpha(alpha);
                RectF bar = new RectF(x, baseY - bh, x + barW, baseY);
                float r = barW / 2f;
                canvas.drawRoundRect(bar, r, r, paint);
            }
            paint.setAlpha(255);
            // 数据活动的小点（iOS 风格右上角一个点）
            if (dataActivity) {
                paint.setColor(color);
                canvas.drawCircle(b.right - barW * 0.35f, baseY - maxH * 0.98f,
                        Math.max(1.2f, barW * 0.28f), paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * 双卡预览：没有实体 SIM 时，用来在状态栏上模拟「两张卡的信号圆点」，
     * 方便在无卡机器上评估 iOS 风格的样子。真实有卡时会用 Signal 走正常路径。
     */
    static final class DualSignalPreview extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = Color.WHITE;
        private final int[] levels = {4, 3};

        void setTintColor(int value) {
            color = value;
            invalidateSelf();
        }

        void setLevels(int first, int second) {
            levels[0] = Math.max(0, Math.min(4, first));
            levels[1] = Math.max(0, Math.min(4, second));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = b.height();
            float w = b.width();
            float clusterW = w * 0.46f;
            for (int c = 0; c < 2; c++) {
                float left = b.left + c * (clusterW + w * 0.08f);
                drawCluster(canvas, left, b.top, clusterW, h, levels[c]);
            }
        }

        private void drawCluster(Canvas canvas, float left, float top, float width, float height, int level) {
            float barW = Math.max(1.8f, width / 8.2f);
            float gap = barW * 0.75f;
            float totalW = barW * 4 + gap * 3;
            float startX = left + (width - totalW) / 2f;
            float baseY = top + height * 0.94f;
            float minH = height * 0.30f;
            float maxH = height * 0.90f;
            for (int i = 0; i < 4; i++) {
                float bh = minH + (maxH - minH) * (i / 3f);
                paint.setColor(color);
                paint.setAlpha(i < level ? 255 : 55);
                float x = startX + i * (barW + gap);
                RectF bar = new RectF(x, baseY - bh, x + barW, baseY);
                float r = barW / 2f;
                canvas.drawRoundRect(bar, r, r, paint);
            }
            paint.setAlpha(255);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** WiFi：三段圆弧 + 圆点，按电平点亮未点亮部分。 */
    static final class Wifi extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int level = 4;
        private int color = Color.WHITE;

        Wifi() {
            paint.setStyle(Paint.Style.FILL);
        }

        void setTintColor(int value) {
            color = value;
            invalidateSelf();
        }

        void setWifiLevel(int value) {
            if (level != value) {
                level = value;
                invalidateSelf();
            }
        }

        @Override
        protected boolean onLevelChange(int value) {
            level = value;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float cx = b.centerX();
            // 以圆心在底部下方的方式画扇形：三段同心圆弧
            float dotY = b.bottom - h * 0.12f;
            float dotR = Math.max(1.2f, w * 0.075f);
            paint.setColor(color);

            int lit = Math.max(0, Math.min(3, level - 1));
            float[] radii = {w * 0.46f, w * 0.30f, w * 0.16f};
            for (int i = 0; i < radii.length; i++) {
                float stroke = Math.max(1.6f, w * 0.10f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setAlpha(i < lit ? 255 : 55);
                RectF arc = new RectF(cx - radii[i], dotY - radii[i], cx + radii[i], dotY + radii[i]);
                canvas.drawArc(arc, -135f, 90f, false, paint);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, dotY, dotR, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * 电池：iOS 药丸 —— 外框 + 右侧小尾巴，按电量填充，数字画在填充区里。
     * 充电时填充区换成电量带闪电（这里简化成"充电中"的小闪电）。
     */
    static final class Battery extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = 100;
        private boolean charging;
        private boolean showNumber = true;
        private int color = Color.WHITE;

        Battery() {
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setTintColor(int value) {
            color = value;
            invalidateSelf();
        }

        void setShowNumber(boolean value) {
            showNumber = value;
            invalidateSelf();
        }

        void setBattery(int level, boolean charging) {
            this.level = Math.max(0, Math.min(100, level));
            this.charging = charging;
            invalidateSelf();
        }

        @Override
        protected boolean onLevelChange(int value) {
            level = Math.max(0, Math.min(100, value));
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float h = b.height();
            float w = b.width();
            float stroke = Math.max(1.5f, h * 0.09f);
            float capW = Math.max(1.5f, w * 0.045f);
            float bodyRight = b.right - capW - stroke * 0.5f;
            float inset = stroke * 0.5f;
            RectF body = new RectF(b.left + inset, b.top + inset, bodyRight, b.bottom - inset);
            float radius = h * 0.34f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(color);
            paint.setAlpha(120);
            canvas.drawRoundRect(body, radius, radius, paint);

            // 右侧小尾巴
            paint.setAlpha(120);
            RectF cap = new RectF(bodyRight + stroke * 0.1f, b.centerY() - h * 0.16f,
                    b.right - inset * 0.2f, b.centerY() + h * 0.16f);
            canvas.drawRoundRect(cap, h * 0.12f, h * 0.12f, paint);

            // 电量填充
            float innerPad = stroke * 1.6f;
            float fillableW = body.width() - innerPad * 2f;
            float fillW = Math.max(0f, fillableW * (level / 100f));
            paint.setAlpha(255);
            if (fillW > 0) {
                RectF fill = new RectF(body.left + innerPad, body.top + innerPad,
                        body.left + innerPad + fillW, body.bottom - innerPad);
                float fillRadius = Math.min(radius - innerPad * 0.5f, fill.height() / 2f);
                canvas.drawRoundRect(fill, fillRadius, fillRadius, paint);
            }

            if (charging) {
                drawBolt(canvas, body, paint, color);
                return;
            }

            if (!showNumber) {
                return;
            }
            // 数字：画在图标内，颜色跟着填充反色
            String text = String.valueOf(level);
            float textSize = h * (text.length() > 2 ? 0.52f : 0.62f);
            textPaint.setTextSize(textSize);
            float centerX = body.centerX();
            float baseline = body.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
            // 填充覆盖到数字位置时用底色写字，否则用图标色
            boolean inside = fillW > body.width() * 0.45f;
            textPaint.setColor(inside ? Color.BLACK : color);
            if (inside) {
                // 深色填充上白字更清楚（iOS 也是这样）
                textPaint.setColor(Color.WHITE);
            }
            canvas.drawText(text, centerX, baseline, textPaint);
        }

        private void drawBolt(Canvas canvas, RectF body, Paint p, int tint) {
            float cx = body.centerX();
            float cy = body.centerY();
            float h = body.height() * 0.62f;
            float w = body.width() * 0.30f;
            Path bolt = new Path();
            bolt.moveTo(cx + w * 0.15f, cy - h / 2f);
            bolt.lineTo(cx - w * 0.55f, cy + h * 0.06f);
            bolt.lineTo(cx - w * 0.02f, cy + h * 0.06f);
            bolt.lineTo(cx - w * 0.25f, cy + h / 2f);
            bolt.lineTo(cx + w * 0.55f, cy - h * 0.08f);
            bolt.lineTo(cx + w * 0.02f, cy - h * 0.08f);
            bolt.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(tint);
            canvas.drawPath(bolt, p);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
