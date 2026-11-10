package com.hyperosmod.xposed;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把系统自带的视图「温柔地藏起来」，并且藏得住。
 *
 * <p>SystemUI 在每次状态栏重排（麦克风/相机隐私绿点出现或消失、运营商文字变化、主题切换）
 * 时都会把 {@code visibility} 和 {@code LayoutParams} 重新设回去，一次性 hide 挡不住，
 * 原生信号图标就会带着 5G 文字从自定义图标底下冒出来。这里做两件事：</p>
 *
 * <ul>
 *   <li>用 {@code INVISIBLE + alpha 0 + 1px} 压制，而不是 {@code GONE}：GONE 会触发父容器
 *       重新测量，系统反而更容易把尺寸改回去；1px 而不是 0，因为 ConstraintLayout 里
 *       {@code 0dp} 等于 MATCH_CONSTRAINT，写 0 会把视图撑开。</li>
 *   <li>给每个视图挂 {@link ViewTreeObserver.OnPreDrawListener}，在测量/布局/绘制之前
 *       幂等地再压一次，直到 {@link #restoreHidden()} 明确解除。</li>
 * </ul>
 *
 * <p>恢复时先摘守卫再还原状态，顺序反了会在同一帧被守卫重新压回去，现象是「关掉功能后
 * 原生图标也不回来了」。</p>
 */
final class ViewSuppressor {
    private static final String TAG = "dual-row";

    private final Map<View, SavedState> hiddenStates = new WeakHashMap<>();
    /** 用 IdentityHashMap：同一个视图只挂一个守卫，避免等值比较误判。 */
    private final Map<View, ViewTreeObserver.OnPreDrawListener> guards =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private long guardRestores;
    private boolean installed;

    /** 视图是否已经被我们压制。 */
    boolean isSuppressed(View view) {
        return view != null && hiddenStates.containsKey(view);
    }

    /** 已经挂上守卫的视图数量，用于日志与状态判断。 */
    int guardedCount() {
        return guards.size();
    }

    void hide(View view) {
        if (view == null) {
            return;
        }
        if (!hiddenStates.containsKey(view)) {
            hiddenStates.put(view, SavedState.capture(view));
        }
        applyHidden(view);
        installGuard(view);
    }

    /**
     * 把视图压回「不可见且不占位」。幂等：系统在任意一次重排里都可能把状态改回来，
     * 所以要能随时再调一次。
     *
     * @return 本次是否真的改动了什么
     */
    boolean applyHidden(View view) {
        boolean changed = false;
        if (view.getAlpha() != 0f) {
            view.setAlpha(0f);
            changed = true;
        }
        if (view.getVisibility() != View.INVISIBLE) {
            view.setVisibility(View.INVISIBLE);
            changed = true;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            return changed;
        }
        boolean needs = params.width != 1 || params.height != 1;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            boolean marginsDirty = margins.leftMargin != 0 || margins.topMargin != 0
                    || margins.rightMargin != 0 || margins.bottomMargin != 0
                    || margins.getMarginStart() != 0 || margins.getMarginEnd() != 0;
            if (marginsDirty) {
                margins.leftMargin = 0;
                margins.topMargin = 0;
                margins.rightMargin = 0;
                margins.bottomMargin = 0;
                margins.setMarginStart(0);
                margins.setMarginEnd(0);
                needs = true;
            }
        }
        if (needs) {
            params.width = 1;
            params.height = 1;
            view.setLayoutParams(params);
            changed = true;
        }
        return changed;
    }

    /**
     * 给视图装一个「每帧绘制前重新压制」的守卫，直到明确解除。ViewTreeObserver 实例会随
     * attach/detach 更换，重新 attach 时补装一次。
     */
    void installGuard(final View view) {
        if (guards.containsKey(view)) {
            return;
        }
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            try {
                if (hiddenStates.containsKey(view) && applyHidden(view)) {
                    guardRestores++;
                    if (guardRestores <= 3 || guardRestores % 50 == 0) {
                        Logs.i(TAG + ": guard re-hid " + describe(view)
                                + " (total " + guardRestores + ")");
                    }
                }
            } catch (Throwable t) {
                Logs.w(TAG + ": visibility guard failed", t);
            }
            return true;
        };
        try {
            view.getViewTreeObserver().addOnPreDrawListener(listener);
            guards.put(view, listener);
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View attached) {
                    ViewTreeObserver.OnPreDrawListener stale = guards.remove(attached);
                    if (stale != null) {
                        try {
                            attached.getViewTreeObserver().removeOnPreDrawListener(stale);
                        } catch (Throwable ignored) {
                            // 旧 observer 已随上一次窗口分离一起消失。
                        }
                    }
                    installGuard(attached);
                }

                @Override
                public void onViewDetachedFromWindow(View detached) {
                    // 下次 attach 时会重新装。
                }
            });
            installed = true;
        } catch (Throwable t) {
            Logs.w(TAG + ": unable to install visibility guard", t);
        }
    }

    void removeGuard(View view) {
        ViewTreeObserver.OnPreDrawListener listener = guards.remove(view);
        if (listener == null) {
            return;
        }
        try {
            view.getViewTreeObserver().removeOnPreDrawListener(listener);
        } catch (Throwable ignored) {
            // observer 可能已随窗口分离消失。
        }
    }

    /** 把被我们压制过的视图全部还原，并摘掉所有守卫。 */
    void restoreHidden() {
        // 先摘守卫再还原，否则同一帧会立刻被重新压回去。
        for (View guarded : new ArrayList<>(guards.keySet())) {
            removeGuard(guarded);
        }
        for (Map.Entry<View, SavedState> entry : hiddenStates.entrySet()) {
            View view = entry.getKey();
            SavedState saved = entry.getValue();
            if (view == null) {
                continue;
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.width = saved.width;
                params.height = saved.height;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    margins.leftMargin = saved.leftMargin;
                    margins.topMargin = saved.topMargin;
                    margins.rightMargin = saved.rightMargin;
                    margins.bottomMargin = saved.bottomMargin;
                    margins.setMarginStart(saved.marginStart);
                    margins.setMarginEnd(saved.marginEnd);
                }
                view.setLayoutParams(params);
            }
            view.setAlpha(saved.alpha);
            view.setVisibility(saved.visibility);
        }
        hiddenStates.clear();
    }

    /** 恢复时用的视图命名，日志与调试用。 */
    static String describe(View view) {
        if (view == null) {
            return "null";
        }
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                return view.getContext().getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
            // 落到类名。
        }
        return view.getClass().getSimpleName();
    }

    /** 视图被压制前的原始状态。 */
    private static final class SavedState {
        int width;
        int height;
        int leftMargin;
        int topMargin;
        int rightMargin;
        int bottomMargin;
        int marginStart;
        int marginEnd;
        float alpha;
        int visibility;

        static SavedState capture(View view) {
            SavedState state = new SavedState();
            state.alpha = view.getAlpha();
            state.visibility = view.getVisibility();
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                state.width = params.width;
                state.height = params.height;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    state.leftMargin = margins.leftMargin;
                    state.topMargin = margins.topMargin;
                    state.rightMargin = margins.rightMargin;
                    state.bottomMargin = margins.bottomMargin;
                    state.marginStart = margins.getMarginStart();
                    state.marginEnd = margins.getMarginEnd();
                }
            }
            return state;
        }
    }
}
