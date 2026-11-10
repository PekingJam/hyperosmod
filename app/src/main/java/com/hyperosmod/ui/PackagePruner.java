package com.hyperosmod.ui;

import android.content.pm.PackageManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 清理勾选名单里已经不存在的应用。
 *
 * <p>三个按包名勾选的功能（阻止更新、目标应用伪装、FCM 修复）都把包名存成字符串集合。
 * 应用被卸载后包名仍留在集合里，界面上就变成「已选 N 个」但列表里根本找不到这几个，
 * 用户也没法取消勾选——只能全部清空。所以每次读取名单时顺手把不存在的包剔掉。</p>
 *
 * <p>判定口径刻意保守：只有 {@link PackageManager.NameNotFoundException} 才算「已卸载」，
 * 其它异常（权限、系统繁忙）一律保留原条目，避免把用户的选择误删。</p>
 */
public final class PackagePruner {

    private PackagePruner() {
    }

    /** 返回过滤后的新集合；没有变化时原样返回传入的集合，便于调用方判断是否需要写回。 */
    public static Set<String> prune(PackageManager pm, Set<String> packages) {
        if (pm == null || packages == null || packages.isEmpty()) {
            return packages;
        }
        Set<String> kept = new LinkedHashSet<>();
        boolean removed = false;
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty()) {
                removed = true;
                continue;
            }
            if (isInstalled(pm, pkg)) {
                kept.add(pkg);
            } else {
                removed = true;
            }
        }
        return removed ? kept : packages;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        } catch (Throwable other) {
            // 权限或系统繁忙：宁可保留，也不要擅自删掉用户的勾选。
            return true;
        }
    }
}
