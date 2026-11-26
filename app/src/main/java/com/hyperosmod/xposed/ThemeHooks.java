package com.hyperosmod.xposed;

import com.hyperosmod.Keys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 允许安装 / 应用第三方主题（非小米官方签名 .mtz）。
 *
 * <p>参考 HyperCeiler 的两条实现（AGPL-3.0，思路参考、代码重写）：</p>
 * <ul>
 *   <li>{@code ThemeProvider}：系统进程里把 {@code miui.drm.DrmManager.isLegal(...)}
 *       在 {@code miui.drm.ThemeReceiver.validateTheme(Intent)} 的执行期内强制返回
 *       {@code DRM_SUCCESS}——即「只在应用/校验主题时放行」，不影响其它内容的版权校验；</li>
 *   <li>{@code AllowThirdTheme}：主题商店进程里直接让权限校验返回 {@code DRM_SUCCESS}。</li>
 * </ul>
 *
 * <p>这里按进程分两级强度，避免把整个系统的 DRM 判定都关掉：</p>
 * <ul>
 *   <li><b>system_server</b>（{@code aggressive=false}）：只在 {@code validateTheme}
 *       的线程内放行，最小副作用；</li>
 *   <li><b>com.android.thememanager</b>（{@code aggressive=true}）：主题商店里所有
 *       {@code isLegal} 一律成功。这个进程里 DRM 只服务于主题资源，放开的范围可控。</li>
 * </ul>
 *
 * <p>全部走反射：{@code miui.drm.*} 不在编译期 classpath 里（模块只依赖 android.jar），
 * 且各 HyperOS 版本的方法签名会变，所以按名字扫全部重载，不写死参数表。</p>
 */
final class ThemeHooks {
    private static final String DRM_MANAGER = "miui.drm.DrmManager";
    private static final String DRM_RESULT = "miui.drm.DrmManager$DrmResult";
    private static final String THEME_RECEIVER = "miui.drm.ThemeReceiver";
    private static final String CHECK_METHOD = "isLegal";
    private static final String VALIDATE_METHOD = "validateTheme";

    /** 只在 validateTheme 内部放行：进方法置位，出方法清掉（线程可能被复用）。 */
    private static final ThreadLocal<Boolean> IN_THEME_FLOW = new ThreadLocal<>();

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    ThemeHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    /**
     * @param aggressive true = 目标进程内所有 isLegal 强制成功（主题商店）；false = 只在
     *                   validateTheme 流程内放行（system_server）。
     */
    void install(boolean aggressive) {
        if (!HookPrefs.getBoolean(Keys.ALLOW_THIRD_THEME, false)) {
            Logs.i("third-theme: 开关未开，跳过安装");
            return;
        }
        Class<?> drm = Reflect.findClass(classLoader, DRM_MANAGER);
        if (drm == null) {
            Logs.w("third-theme: 找不到 " + DRM_MANAGER + "，本次不生效", null);
            return;
        }
        Object success = successValue(drm);
        int hooked = hookIsLegal(drm, aggressive, success);
        if (hooked == 0) {
            Logs.w("third-theme: DrmManager 里没有可用的 isLegal 重载", null);
        }
        if (!aggressive) {
            hookValidateTheme();
        }
        Logs.i("third-theme: isLegal 挂钩 " + hooked + " 个重载，模式="
                + (aggressive ? "强制成功" : "跟随 validateTheme"));
    }

    private int hookIsLegal(Class<?> drm, boolean aggressive, Object success) {
        int count = 0;
        for (Method method : drm.getDeclaredMethods()) {
            if (!CHECK_METHOD.equals(method.getName())) {
                continue;
            }
            final Object result = coerce(success, method.getReturnType());
            if (result == null) {
                continue;
            }
            try {
                method.setAccessible(true);
                xposed.hook(method).intercept(chain -> {
                    if (aggressive || Boolean.TRUE.equals(IN_THEME_FLOW.get())) {
                        return result;
                    }
                    return chain.proceed();
                });
                count++;
            } catch (Throwable t) {
                Logs.w("third-theme: 挂钩 " + method.getName() + " 失败", t);
            }
        }
        return count;
    }

    /**
     * 系统进程应用主题时会走 {@code ThemeReceiver.validateTheme}；在这里置位线程态，
     * 让 isLegal 只在这次校验里放行。
     */
    private void hookValidateTheme() {
        Class<?> receiver = Reflect.findClass(classLoader, THEME_RECEIVER);
        Method validate = Reflect.findMethod(receiver, VALIDATE_METHOD);
        if (validate == null) {
            Logs.w("third-theme: 找不到 " + THEME_RECEIVER + "#" + VALIDATE_METHOD
                    + "，系统侧只靠 isLegal 兜底", null);
            return;
        }
        try {
            validate.setAccessible(true);
            xposed.hook(validate).intercept(chain -> {
                IN_THEME_FLOW.set(Boolean.TRUE);
                try {
                    return chain.proceed();
                } finally {
                    IN_THEME_FLOW.remove();
                }
            });
            Logs.i("third-theme: 已挂钩 " + THEME_RECEIVER + "#" + VALIDATE_METHOD);
        } catch (Throwable t) {
            Logs.w("third-theme: 挂钩 validateTheme 失败", t);
        }
    }

    /** {@code DrmManager.DrmResult.DRM_SUCCESS}；拿不到就给该类型的常规成功值。 */
    private Object successValue(Class<?> drm) {
        Class<?> resultType = Reflect.findClass(classLoader, DRM_RESULT);
        for (Class<?> owner : new Class<?>[]{resultType, drm}) {
            Field field = Reflect.findField(owner, "DRM_SUCCESS");
            if (field == null) {
                continue;
            }
            try {
                return field.get(null);
            } catch (Throwable ignored) {
                // 换个持有类再试
            }
        }
        Logs.w("third-theme: 读不到 DRM_SUCCESS，使用默认成功值", null);
        return null;
    }

    /**
     * 把成功值转成目标返回类型；无法转换（void、String 之类）返回 null 表示跳过该方法。
     */
    private static Object coerce(Object success, Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.TRUE;
        }
        if (success == null) {
            if (returnType == int.class || returnType == Integer.class) {
                return 0;
            }
            if (returnType == long.class || returnType == Long.class) {
                return 0L;
            }
            return null;
        }
        if (returnType.isInstance(success)) {
            return success;
        }
        if ((returnType == int.class || returnType == Integer.class)
                && success instanceof Number) {
            return ((Number) success).intValue();
        }
        if ((returnType == long.class || returnType == Long.class)
                && success instanceof Number) {
            return ((Number) success).longValue();
        }
        return null;
    }
}
