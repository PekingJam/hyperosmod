package com.hyperosmod.xposed;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Spoofs telephony/SIM values inside selected apps.
 *
 * <p>Hooks {@link android.telephony.TelephonyManager} and
 * {@link android.telephony.SubscriptionInfo} getters. No-argument getters use the
 * configured default SIM; getters that take a subscription id are mapped to a slot with
 * {@code SubscriptionManager.getSlotIndex(int)} so both SIM slots can be spoofed
 * independently.</p>
 */
final class TelephonyHooks {
    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    TelephonyHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            hookTelephonyManager();
        } catch (Throwable t) {
            Logs.e("Telephony: failed to hook TelephonyManager", t);
        }
        try {
            hookSubscriptionInfo();
        } catch (Throwable t) {
            Logs.e("Telephony: failed to hook SubscriptionInfo", t);
        }
    }

    private void hookTelephonyManager() {
        Class<?> type = Reflect.findClass(classLoader, "android.telephony.TelephonyManager");
        if (type == null) {
            Logs.w("Telephony: TelephonyManager not found", null);
            return;
        }
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            String field = fieldFor(method.getName());
            if (field == null || method.getReturnType() != String.class) {
                continue;
            }
            int count = method.getParameterCount();
            boolean usesSubId = count == 1 && method.getParameterTypes()[0] == int.class;
            if (count != 0 && !usesSubId) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int slot = usesSubId
                                ? slotForSubId((Integer) chain.getArg(0))
                                : defaultSlot();
                        String value = value(field, slot);
                        return value.isEmpty() ? chain.proceed() : value;
                    });
            hooked++;
        }

        hooked += hookIntConstant(type, "getPhoneCount", 2);
        hooked += hookIntConstant(type, "getActiveModemCount", 2);
        hooked += hookSimState(type);
        hooked += hookHasIccCard(type);

        if (hooked == 0) {
            Logs.w("Telephony: no TelephonyManager methods hooked", null);
        } else {
            Logs.i("Telephony: hooked " + hooked + " TelephonyManager methods");
        }
    }

    private void hookSubscriptionInfo() {
        Class<?> type = Reflect.findClass(classLoader, "android.telephony.SubscriptionInfo");
        if (type == null) {
            return;
        }
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if ("getNumber".equals(name) || "getIccId".equals(name)
                    || "getCountryIso".equals(name) || "getMccString".equals(name)
                    || "getMncString".equals(name)) {
                if (method.getReturnType() != String.class) {
                    continue;
                }
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int slot = slotForInfo(chain.getThisObject());
                            String value = subscriptionValue(name, slot);
                            return value.isEmpty() ? chain.proceed() : value;
                        });
                hooked++;
            } else if ("getCarrierName".equals(name)
                    && CharSequence.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int slot = slotForInfo(chain.getThisObject());
                            String value = value(Keys.TELEPHONY_SIM_OPERATOR_NAME, slot);
                            return value.isEmpty() ? chain.proceed() : value;
                        });
                hooked++;
            } else if (("getMcc".equals(name) || "getMnc".equals(name))
                    && method.getReturnType() == int.class) {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            int slot = slotForInfo(chain.getThisObject());
                            String operator = value(Keys.TELEPHONY_SIM_OPERATOR, slot);
                            if (operator.isEmpty()) {
                                return chain.proceed();
                            }
                            int result = parseMccMnc(operator, "getMcc".equals(name));
                            return result < 0 ? chain.proceed() : result;
                        });
                hooked++;
            }
        }
        if (hooked > 0) {
            Logs.i("Telephony: hooked " + hooked + " SubscriptionInfo methods");
        }
    }

    private int hookIntConstant(Class<?> type, String methodName, int value) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getParameterCount() != 0
                    || method.getReturnType() != int.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> enabled() ? value : chain.proceed());
            return 1;
        }
        return 0;
    }

    private int hookSimState(Class<?> type) {
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!"getSimState".equals(method.getName())
                    || method.getReturnType() != int.class) {
                continue;
            }
            int count = method.getParameterCount();
            if (count != 0 && !(count == 1 && method.getParameterTypes()[0] == int.class)) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        if (count == 1) {
                            int slot = (Integer) chain.getArg(0);
                            if (slot < 0 || slot > 1) {
                                return chain.proceed();
                            }
                        }
                        return 5; // TelephonyManager.SIM_STATE_READY
                    });
            hooked++;
        }
        return hooked;
    }

    private int hookHasIccCard(Class<?> type) {
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!"hasIccCard".equals(method.getName())
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            int count = method.getParameterCount();
            if (count != 0 && !(count == 1 && method.getParameterTypes()[0] == int.class)) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> enabled() ? Boolean.TRUE : chain.proceed());
            hooked++;
        }
        return hooked;
    }

    private boolean enabled() {
        return HookPrefs.getBoolean(Keys.TELEPHONY_ENABLED, false);
    }

    private int defaultSlot() {
        return HookPrefs.getInt(Keys.TELEPHONY_DEFAULT_SLOT, 0) == 1 ? 1 : 0;
    }

    private int slotForSubId(int subId) {
        try {
            Class<?> manager = Class.forName("android.telephony.SubscriptionManager",
                    false, classLoader);
            Method getSlotIndex;
            try {
                getSlotIndex = manager.getDeclaredMethod("getSlotIndex", int.class);
            } catch (NoSuchMethodException e) {
                getSlotIndex = manager.getMethod("getSlotIndex", int.class);
            }
            getSlotIndex.setAccessible(true);
            int slot = (Integer) getSlotIndex.invoke(null, subId);
            if (slot >= 0 && slot <= 1) {
                return slot;
            }
        } catch (Throwable ignored) {
            // Fall back to the configured default slot.
        }
        return defaultSlot();
    }

    private int slotForInfo(Object info) {
        Object slot = Reflect.call(info, "getSimSlotIndex");
        if (slot instanceof Integer) {
            int value = (Integer) slot;
            if (value >= 0 && value <= 1) {
                return value;
            }
        }
        return defaultSlot();
    }

    private String subscriptionValue(String methodName, int slot) {
        switch (methodName) {
            case "getNumber":
                return value(Keys.TELEPHONY_PHONE, slot);
            case "getIccId":
                return value(Keys.TELEPHONY_SIM_SERIAL, slot);
            case "getCountryIso":
                return value(Keys.TELEPHONY_SIM_COUNTRY, slot);
            case "getMccString":
                return mccMncPart(value(Keys.TELEPHONY_SIM_OPERATOR, slot), true);
            case "getMncString":
                return mccMncPart(value(Keys.TELEPHONY_SIM_OPERATOR, slot), false);
            default:
                return "";
        }
    }

    private String value(String field, int slot) {
        return HookPrefs.getString(Keys.telephony(field, slot), "");
    }

    private static String fieldFor(String methodName) {
        switch (methodName) {
            case "getPhoneNumber":
            case "getLine1Number":
                return Keys.TELEPHONY_PHONE;
            case "getSimSerialNumber":
                return Keys.TELEPHONY_SIM_SERIAL;
            case "getNetworkCountryIso":
                return Keys.TELEPHONY_NETWORK_COUNTRY;
            case "getNetworkOperator":
                return Keys.TELEPHONY_NETWORK_OPERATOR;
            case "getNetworkOperatorName":
                return Keys.TELEPHONY_NETWORK_OPERATOR_NAME;
            case "getSimCountryIso":
                return Keys.TELEPHONY_SIM_COUNTRY;
            case "getSimOperator":
                return Keys.TELEPHONY_SIM_OPERATOR;
            case "getSimOperatorName":
                return Keys.TELEPHONY_SIM_OPERATOR_NAME;
            default:
                return null;
        }
    }

    private static int parseMccMnc(String numeric, boolean mcc) {
        if (numeric == null || numeric.length() < 3) {
            return -1;
        }
        try {
            if (mcc) {
                return Integer.parseInt(numeric.substring(0, 3));
            }
            if (numeric.length() <= 3) {
                return -1;
            }
            return Integer.parseInt(numeric.substring(3));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String mccMncPart(String numeric, boolean mcc) {
        if (numeric == null) {
            return "";
        }
        if (mcc) {
            return numeric.length() >= 3 ? numeric.substring(0, 3) : numeric;
        }
        return numeric.length() > 3 ? numeric.substring(3) : "";
    }
}
