package com.hyperosmod.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection helpers used by the hooks. Never throw to the caller. */
final class Reflect {
    /**
     * 反射结果缓存。
     *
     * <p>状态栏、通知图标这些路径每秒会被调用很多次，`getDeclaredField` 走一遍类层级 +
     * 异常构造的开销比实际读写大一到两个数量级。这里按「类 → 名字」缓存，未命中时也缓存
     * 一个哨兵对象，避免反复扫描找不到的成员。</p>
     */
    private static final Map<Class<?>, Map<String, Object>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Object>> CALL_CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private Reflect() {
    }

    static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        if (type == null) {
            return null;
        }
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            // Fall through to a signature-agnostic lookup.
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterTypes.length) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    static Field findField(Class<?> type, String name) {
        if (type == null || name == null) {
            return null;
        }
        Map<String, Object> perClass = FIELD_CACHE.get(type);
        if (perClass != null) {
            Object cached = perClass.get(name);
            if (cached == MISSING) {
                return null;
            }
            if (cached != null) {
                return (Field) cached;
            }
        }
        Field found = null;
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                found = field;
                break;
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        if (perClass == null) {
            perClass = new ConcurrentHashMap<>();
            Map<String, Object> existing = FIELD_CACHE.putIfAbsent(type, perClass);
            if (existing != null) {
                perClass = existing;
            }
        }
        perClass.put(name, found == null ? MISSING : found);
        return found;
    }

    static Object getField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = findField(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean setField(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return false;
            }
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        int argCount = args == null ? 0 : args.length;
        Map<String, Object> perClass = callCache(type);
        String key = name + '/' + argCount;
        Object cached = perClass.get(key);
        Method method;
        if (cached == MISSING) {
            return null;
        } else if (cached != null) {
            method = (Method) cached;
        } else {
            method = scanMethod(type, name, argCount);
            perClass.put(key, method == null ? MISSING : method);
            if (method == null) {
                return null;
            }
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            // 参数类型对不上：丢掉这条缓存，下次重新按名字扫描。
            perClass.remove(key);
            return null;
        }
    }

    /** 按「名字 + 参数个数」扫描，和原来的语义一致（取父类链上第一个匹配）。 */
    private static Method scanMethod(Class<?> type, String name, int argCount) {
        Class<?> current = type;
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)
                            || method.getParameterCount() != argCount) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method;
                }
            } catch (Throwable ignored) {
                // Try the superclass.
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Map<String, Object> callCache(Class<?> type) {
        Map<String, Object> perClass = CALL_CACHE.get(type);
        if (perClass != null) {
            return perClass;
        }
        perClass = new HashMap<>();
        Map<String, Object> existing = CALL_CACHE.putIfAbsent(type, perClass);
        return existing == null ? perClass : existing;
    }
}
