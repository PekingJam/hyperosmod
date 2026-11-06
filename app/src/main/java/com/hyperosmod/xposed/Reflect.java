package com.hyperosmod.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection helpers used by the hooks. Never throw to the caller. */
final class Reflect {
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
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
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
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name)
                            || method.getParameterCount() != args.length) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            } catch (Throwable ignored) {
                // Try the superclass.
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
