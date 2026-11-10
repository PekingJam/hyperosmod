package com.hyperosmod.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态栏图标的「主题尺寸」。
 *
 * <p>小米主题（{@code com.android.systemui} 模块里的 {@code stat_sys_signal_*}/drawable-*dpi）
 * 决定了状态栏信号图标的实际像素尺寸、留白和线条粗细，系统再按
 * {@code status_bar_icon_drawing_size} 一类的 dimen 把它摆进状态栏。所以双排图标的基准尺寸
 * 不能写死，要按这个顺序取：</p>
 *
 * <ol>
 *   <li>当前主题真的画出来的那张 drawable 的固有尺寸（最准，不同主题差很多）；</li>
 *   <li>SystemUI 资源里的状态栏图标 dimen（主题会覆盖它）；</li>
 *   <li>官方图标 View 的实测高度；</li>
 *   <li>兜底 12dp × 14dp（老版固定值）。</li>
 * </ol>
 *
 * <p>结果按「资源对象 + 屏幕密度」缓存，主题或字体缩放变化（configuration change）后自动失效。</p>
 */
final class StatusBarMetrics {
    /** SystemUI 里可能承载状态栏图标尺寸的 dimen 名，从最贴切排到最泛。 */
    private static final String[] ICON_SIZE_DIMENS = {
            "status_bar_icon_drawing_size",
            "status_bar_system_icon_size",
            "status_bar_icon_size",
            "status_bar_signal_icon_width",
    };

    private static final float FALLBACK_WIDTH_DP = 14f;
    private static final float FALLBACK_HEIGHT_DP = 11f;

    private final Map<String, Integer> dimenCache = new ConcurrentHashMap<>();
    private volatile int cachedDensityDpi = -1;
    private volatile IconBox cachedBox;

    /**
     * 解析主题图标尺寸。
     *
     * @param themeDrawable 当前主题给这颗 SIM 画出来的 drawable，可为 null
     * @param officialView  官方信号图标 View，用作最后的实测兜底，可为 null
     */
    IconBox resolve(Context context, Drawable themeDrawable, View officialView) {
        if (context == null) {
            return new IconBox(dp(context, FALLBACK_WIDTH_DP), dp(context, FALLBACK_HEIGHT_DP));
        }
        Resources resources = context.getResources();
        int densityDpi = resources.getDisplayMetrics().densityDpi;
        IconBox cached = cachedBox;
        if (cached != null && cachedDensityDpi == densityDpi && themeDrawable == null) {
            return cached;
        }

        int width = 0;
        int height = 0;

        if (themeDrawable != null) {
            int intrinsicWidth = themeDrawable.getIntrinsicWidth();
            int intrinsicHeight = themeDrawable.getIntrinsicHeight();
            if (intrinsicHeight > 0) {
                height = intrinsicHeight;
            }
            if (intrinsicWidth > 0) {
                width = intrinsicWidth;
            }
        }

        if (height <= 0) {
            height = dimen(resources, ICON_SIZE_DIMENS);
        }
        if (height <= 0 && officialView != null) {
            int measured = officialView.getHeight();
            if (measured <= 0) {
                measured = officialView.getMeasuredHeight();
            }
            if (measured > 0) {
                height = measured;
            }
        }
        if (height <= 0) {
            height = dp(context, FALLBACK_HEIGHT_DP);
        }
        if (width <= 0) {
            // 主题没给宽度时按 4:3 推，和官方图标比例接近。
            width = Math.round(height * FALLBACK_WIDTH_DP / FALLBACK_HEIGHT_DP);
        }

        IconBox box = new IconBox(width, height);
        cachedBox = box;
        cachedDensityDpi = densityDpi;
        return box;
    }

    /** 双排时两行之间的间隙：跟随图标高度，但不小于 0.5dp。 */
    int rowGap(Context context, int iconHeight) {
        int min = Math.max(1, dp(context, 0.5f));
        return Math.max(min, Math.round(iconHeight * 0.05f));
    }

    private int dimen(Resources resources, String[] names) {
        for (String name : names) {
            Integer cached = dimenCache.get(name);
            if (cached != null && cached > 0) {
                return cached;
            }
            try {
                int id = resources.getIdentifier(name, "dimen", "com.android.systemui");
                if (id == 0) {
                    id = resources.getIdentifier(name, "dimen", "android");
                }
                if (id == 0) {
                    continue;
                }
                int value = resources.getDimensionPixelSize(id);
                if (value > 0) {
                    dimenCache.put(name, value);
                    return value;
                }
            } catch (Throwable ignored) {
                // 下一个候选名。
            }
        }
        return 0;
    }

    private static int dp(Context context, float value) {
        if (context == null) {
            return Math.round(value * 2f);
        }
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    /** 单个信号图标的基准盒子。 */
    static final class IconBox {
        final int width;
        final int height;

        IconBox(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
