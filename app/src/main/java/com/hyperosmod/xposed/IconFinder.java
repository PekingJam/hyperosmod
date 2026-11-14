package com.hyperosmod.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SystemUI 视图查找工具。
 *
 * <p>HyperOS 不同版本、不同资源可见性下 {@code getIdentifier} 可能返回 0（资源被
 * 裁剪或改名），所以这里固定套路：先按 id 名查，查不到再按 {@code getResourceEntryName}
 * 在子树上比对，最后再按名字关键字兜底。</p>
 */
final class IconFinder {

    View byName(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = idFor(root.getContext(), name);
        if (id == 0) {
            return null;
        }
        return byId(root, id);
    }

    /** 依次尝试多个候选名，返回第一个命中的视图。 */
    View byNames(View root, String... names) {
        for (String name : names) {
            View found = byName(root, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 按 id 找不到时的兜底：在子树里比对各视图的 resource entry name，看是否包含关键字。
     *
     * @param keywords 关键字（小写），例如 {@code "signal"}、{@code "strength"}
     */
    View byKeyword(View root, int maxDepth, String... keywords) {
        return byKeyword(root, 0, maxDepth, keywords);
    }

    private View byKeyword(View view, int depth, int maxDepth, String... keywords) {
        if (view == null || depth > maxDepth) {
            return null;
        }
        String name = entryName(view);
        if (name != null) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = byKeyword(group.getChildAt(i), depth + 1, maxDepth, keywords);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static View byId(View root, int id) {
        if (root == null) {
            return null;
        }
        if (root.getId() == id) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = byId(group.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static String entryName(View view) {
        if (view == null) {
            return null;
        }
        int id = view.getId();
        if (id == View.NO_ID) {
            return null;
        }
        try {
            return view.getContext().getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    int idFor(Context context, String name) {
        String packageName = context.getPackageName();
        // 同一个进程里 context 的包名基本是常量，缓存住就不用每次拼 key 字符串。
        if (packageName.equals(cachedPackage) && !idsByName.isEmpty()) {
            Integer cached = idsByName.get(name);
            if (cached != null) {
                return cached;
            }
        } else if (!packageName.equals(cachedPackage)) {
            idsByName.clear();
            cachedPackage = packageName;
        }
        int id = resolveId(context, name, packageName);
        idsByName.put(name, id);
        return id;
    }

    private volatile String cachedPackage = "";
    private final Map<String, Integer> idsByName = new ConcurrentHashMap<>();

    private static int resolveId(Context context, String name, String packageName) {
        int id = 0;
        try {
            Resources resources = context.getResources();
            id = resources.getIdentifier(name, "id", packageName);
        } catch (Throwable ignored) {
            // 试框架包。
        }
        if (id == 0) {
            try {
                id = context.getResources().getIdentifier(name, "id", "android");
            } catch (Throwable ignored) {
                // 保持 0。
            }
        }
        return id;
    }
}
