package com.hyperosmod.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hyperosmod.Keys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Dual-row mobile signal icons for SystemUI.
 *
 * <p>HyperOS lays the per-SIM mobile icons out horizontally. When two SIMs are active this
 * hook replaces the official signal image inside the first mobile view with a small custom
 * view that shows the current data SIM's network type ("5G", "4G", ...) followed by the two
 * SIM signal levels stacked vertically. The remaining official mobile views are collapsed to
 * zero size so only the custom view is visible.</p>
 *
 * <p>The layout mirrors the approach used by HyperCeiler's
 * {@code DualRowSignalHookV}: signal levels are read from
 * {@code MobileSignalController}, the official views stay alive (their flows keep updating)
 * and the alternative view is injected into {@code mobile_signal_container}. The signal bars
 * themselves are drawn with Canvas so no extra drawable resources are required.</p>
 */
final class DualSignalHook {
    private static final String MOBILE_VIEW_CLASS =
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView";
    private static final String MOBILE_BINDER_CLASS =
            "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder";
    private static final String MOBILE_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.connectivity.MobileSignalController";

    private static final long FALLBACK_TICK_MS = 10_000L;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();

    private final Map<ViewGroup, Integer> mobileViews = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> slotBySubId = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> levelBySubId = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> dataSimBySubId = new ConcurrentHashMap<>();
    private final Map<Integer, String> typeBySubId = new ConcurrentHashMap<>();
    private final Map<String, Integer> resourceIds = new ConcurrentHashMap<>();

    private final Map<View, Object> typeFlows =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Object> tintFlows =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, SavedState> hiddenStates = new WeakHashMap<>();
    private final Map<ViewGroup, View> injectedViews = new WeakHashMap<>();

    /**
     * 已装上 pre-draw 守卫的视图 → 监听器。SystemUI 在每次状态栏重排（例如麦克风/相机隐私
     * 指示器绿点出现或消失）时会重新设置这些视图的 visibility 和 LayoutParams，把压制状态
     * 冲掉，原生信号图标就带着 5G 文字从自定义图标底下冒出来。守卫在每帧绘制前再压一次，
     * 直到 {@link #restoreHidden()} 明确解除。
     */
    private final Map<View, ViewTreeObserver.OnPreDrawListener> guards =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private long guardRestores;

    private volatile Tint tint = Tint.unknown();
    private volatile boolean receiverRegistered;
    private volatile boolean prefsListenerRegistered;

    private BroadcastReceiver receiver;
    private Runnable fallbackTicker;
    private int lastAnchorCount = -1;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    DualSignalHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            hookConstructAndBind();
        } catch (Throwable t) {
            Logs.e("Failed to hook ModernStatusBarMobileView", t);
        }
        try {
            hookBinder();
        } catch (Throwable t) {
            Logs.e("Failed to hook MiuiMobileIconBinder", t);
        }
        try {
            hookMobileSignalController();
        } catch (Throwable t) {
            Logs.e("Failed to hook MobileSignalController", t);
        }
        try {
            ensurePrefsListener();
        } catch (Throwable t) {
            Logs.w("Unable to observe dual-signal preference", t);
        }
    }

    // ==================================================================================
    // Hooks
    // ==================================================================================

    private void hookConstructAndBind() {
        Class<?> type = Reflect.findClass(classLoader, MOBILE_VIEW_CLASS);
        if (type == null) {
            Logs.w("ModernStatusBarMobileView not found; dual-row signal disabled", null);
            return;
        }
        Set<Method> methods = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if ("constructAndBind".equals(method.getName())
                        && !Modifier.isAbstract(method.getModifiers())) {
                    methods.add(method);
                }
            }
        }
        int hooked = 0;
        for (Method method : methods) {
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result instanceof ViewGroup) {
                                try {
                                    ViewGroup root = (ViewGroup) result;
                                    int subId = getIntField(root, "subId", -1);
                                    Object viewModel = findViewModel(chain.getArgs());
                                    onMobileViewCreated(root, subId, viewModel);
                                } catch (Throwable t) {
                                    Logs.w("Dual-signal constructAndBind callback failed", t);
                                }
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w("Failed to hook a constructAndBind overload", t);
            }
        }
        if (hooked == 0) {
            Logs.w("ModernStatusBarMobileView#constructAndBind not found", null);
        } else {
            Logs.i("Hooked ModernStatusBarMobileView#constructAndBind (" + hooked + ")");
        }
    }

    private void hookBinder() {
        Class<?> type = Reflect.findClass(classLoader, MOBILE_BINDER_CLASS);
        if (type == null) {
            Logs.w("MiuiMobileIconBinder not found; tint updates may be delayed", null);
            return;
        }
        Set<Method> methods = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if ("bind".equals(method.getName())
                        && !Modifier.isAbstract(method.getModifiers())) {
                    methods.add(method);
                }
            }
        }
        int hooked = 0;
        for (Method method : methods) {
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object first = chain.getArg(0);
                                if (first instanceof View) {
                                    View view = (View) first;
                                    if (view instanceof ViewGroup) {
                                        ViewGroup root = (ViewGroup) view;
                                        int subId = getIntField(root, "subId", -1);
                                        if (subId >= 0) {
                                            // The binder is shared by the keyguard and the
                                            // regular status bar; registering here covers
                                            // both even if constructAndBind was not hooked.
                                            onMobileViewCreated(root, subId,
                                                    findViewModel(chain.getArgs()));
                                        }
                                    }
                                    Object tintFlow = findTintFlow(result);
                                    if (tintFlow != null) {
                                        Tint parsed = readTint(tintFlow);
                                        if (parsed.known) {
                                            tint = parsed;
                                        }
                                        collectFlow(view, tintFlow, value -> {
                                            Tint changed = Tint.fromTriple(value);
                                            if (changed.known) {
                                                tint = changed;
                                                scheduleRefresh();
                                            }
                                        }, tintFlows);
                                    }
                                    scheduleRefresh();
                                }
                            } catch (Throwable t) {
                                Logs.w("Dual-signal binder callback failed", t);
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w("Failed to hook MiuiMobileIconBinder#bind", t);
            }
        }
        if (hooked > 0) {
            Logs.i("Hooked MiuiMobileIconBinder#bind (" + hooked + ")");
        }
    }

    private void hookMobileSignalController() {
        Class<?> type = Reflect.findClass(classLoader, MOBILE_CONTROLLER_CLASS);
        if (type == null) {
            Logs.w("MobileSignalController not found; signal levels will use TelephonyManager", null);
            return;
        }
        Set<Method> methods = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if ("notifyListeners".equals(method.getName())
                        && !Modifier.isAbstract(method.getModifiers())) {
                    methods.add(method);
                }
            }
        }
        int hooked = 0;
        for (Method method : methods) {
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                updateFromController(chain.getThisObject());
                            } catch (Throwable t) {
                                Logs.w("Dual-signal controller callback failed", t);
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w("Failed to hook MobileSignalController#notifyListeners", t);
            }
        }
        if (hooked > 0) {
            Logs.i("Hooked MobileSignalController#notifyListeners (" + hooked + ")");
        }
    }

    // ==================================================================================
    // State collection
    // ==================================================================================

    private void onMobileViewCreated(ViewGroup root, int subId, Object viewModel) {
        if (subId < 0) {
            return;
        }
        int slot = slotOf(subId);
        Integer previous = mobileViews.put(root, subId);
        slotBySubId.put(subId, slot);
        if (previous != null) {
            // constructAndBind and MiuiMobileIconBinder may both report the same view.
            // Only add one attach listener and one flow collector per root.
            scheduleRefresh();
            return;
        }

        Object showNameFlow = viewModel == null ? null : Reflect.getField(viewModel, "showName");
        if (showNameFlow != null) {
            Object initial = Reflect.call(showNameFlow, "getValue");
            if (initial instanceof String) {
                typeBySubId.put(subId, normalizeType((String) initial));
            }
            final int flowSubId = subId;
            collectFlow(root, showNameFlow, value -> {
                if (value instanceof String) {
                    typeBySubId.put(flowSubId, normalizeType((String) value));
                    scheduleRefresh();
                }
            }, typeFlows);
        }

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                mobileViews.put(root, subId);
                View parent = root.getParent() instanceof View ? (View) root.getParent() : null;
                Logs.i("Dual-signal mobile view attached: sub=" + subId
                        + " parent=" + (parent == null ? "null"
                        : parent.getClass().getSimpleName()));
                scheduleRefresh();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                mainHandler.postDelayed(() -> {
                    if (!root.isAttachedToWindow()) {
                        mobileViews.remove(root);
                        scheduleRefresh();
                    }
                }, 500L);
            }
        });

        scheduleRefresh();
        if (root.isAttachedToWindow()) {
            ensureReceiver(root.getContext());
        }
    }

    private void updateFromController(Object controller) {
        if (controller == null) {
            return;
        }
        Object info = Reflect.getField(controller, "mSubscriptionInfo");
        Object state = Reflect.getField(controller, "mCurrentState");
        if (state == null) {
            return;
        }
        int subId = -1;
        if (info != null) {
            Object value = Reflect.call(info, "getSubscriptionId");
            if (value instanceof Integer) {
                subId = (Integer) value;
            }
        }
        if (subId < 0) {
            return;
        }
        int slot = -1;
        if (info != null) {
            Object value = Reflect.call(info, "getSimSlotIndex");
            if (value instanceof Integer) {
                slot = (Integer) value;
            }
        }
        if (slot < 0) {
            slot = slotOf(subId);
        }
        if (slot >= 0) {
            slotBySubId.put(subId, slot);
        }

        boolean connected = Boolean.TRUE.equals(Reflect.getField(state, "connected"));
        boolean enabled = Boolean.TRUE.equals(Reflect.getField(state, "enabled"));
        boolean dataSim = Boolean.TRUE.equals(Reflect.getField(state, "dataSim"));
        dataSimBySubId.put(subId, dataSim);

        int raw = -1;
        boolean levelKnown = false;
        if (connected) {
            Object signalStrength = Reflect.getField(state, "signalStrength");
            Object miuiLevel = Reflect.call(signalStrength, "getMiuiLevel");
            if (miuiLevel instanceof Integer) {
                raw = (Integer) miuiLevel;
                levelKnown = true;
            }
            if (!levelKnown) {
                Object level = Reflect.call(signalStrength, "getLevel");
                if (level instanceof Integer) {
                    raw = (Integer) level;
                    levelKnown = true;
                }
            }
        }
        if (!levelKnown) {
            Object level = Reflect.getField(state, "level");
            if (level instanceof Integer) {
                raw = (Integer) level;
                levelKnown = true;
            }
        }

        if (levelKnown) {
            int mapped = clamp(raw, 0, 4);
            Integer previous = levelBySubId.get(subId);
            if (!connected) {
                mapped = (enabled && previous != null && previous > 0) ? previous : 0;
            }
            levelBySubId.put(subId, mapped);
        } else {
            // Let the TelephonyManager fallback provide a level instead of pinning it to 0.
            levelBySubId.remove(subId);
        }
        scheduleRefresh();
    }

    private Object findViewModel(List<Object> args) {
        if (args == null) {
            return null;
        }
        for (int i = args.size() - 1; i >= 0; i--) {
            Object candidate = unwrapViewModel(args.get(i));
            if (candidate == null) {
                continue;
            }
            if (Reflect.findField(candidate.getClass(), "showName") != null) {
                return candidate;
            }
        }
        return null;
    }

    private Object unwrapViewModel(Object arg) {
        if (arg == null) {
            return null;
        }
        if ("MiuiMobileIconVMImpl".equals(arg.getClass().getSimpleName())) {
            Object cell = Reflect.call(arg, "getCellProvider");
            if (cell != null) {
                return cell;
            }
        }
        return arg;
    }

    private Object findTintFlow(Object binding) {
        if (binding == null) {
            return null;
        }
        for (Field field : binding.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(binding);
                if (value == null || !"StateFlowImpl".equals(value.getClass().getSimpleName())) {
                    continue;
                }
                Object current = Reflect.call(value, "getValue");
                if (current != null && "Triple".equals(current.getClass().getSimpleName())) {
                    return value;
                }
            } catch (Throwable ignored) {
                // Try the next field.
            }
        }
        return null;
    }

    private Tint readTint(Object flow) {
        if (flow == null) {
            return Tint.unknown();
        }
        return Tint.fromTriple(Reflect.call(flow, "getValue"));
    }

    private void collectFlow(View view, Object flow, Consumer<Object> consumer,
                             Map<View, Object> tracker) {
        if (view == null || flow == null) {
            return;
        }
        if (tracker.get(view) == flow) {
            return;
        }
        tracker.put(view, flow);
        try {
            Class<?> adapter = Reflect.findClass(classLoader,
                    "com.android.systemui.util.kotlin.JavaAdapterKt");
            if (adapter == null) {
                return;
            }
            for (Method method : adapter.getDeclaredMethods()) {
                if (!"collectFlow".equals(method.getName()) || method.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters[0] != View.class || parameters[2] != Consumer.class) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(null, view, flow, consumer);
                return;
            }
        } catch (Throwable t) {
            Logs.w("Unable to collect a SystemUI flow", t);
        }
    }

    // ==================================================================================
    // Refresh
    // ==================================================================================

    private void scheduleRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        mainHandler.post(() -> {
            refreshScheduled.set(false);
            refreshNow();
        });
    }

    private void refreshNow() {
        try {
            if (!HookPrefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false)) {
                restoreAll();
                stopFallbackTicker();
                return;
            }
            ViewGroup sample = firstAttachedView();
            if (sample == null) {
                restoreAll();
                stopFallbackTicker();
                return;
            }
            Context context = sample.getContext();
            ensureReceiver(context);
            ensurePrefsListener();
            if (isAirplaneModeOn(context)) {
                restoreAll();
                stopFallbackTicker();
                return;
            }
            List<Integer> active = activeSubIds(context);
            if (active.size() < 2) {
                restoreAll();
                stopFallbackTicker();
                return;
            }
            List<ViewGroup> anchors = chooseAnchors(active);
            if (anchors.size() != lastAnchorCount) {
                lastAnchorCount = anchors.size();
                Logs.i("Dual-signal anchors: " + lastAnchorCount);
            }
            if (anchors.isEmpty()) {
                restoreAll();
                return;
            }
            Set<ViewGroup> desired = new LinkedHashSet<>(anchors);
            Set<ViewGroup> currentAnchors = new LinkedHashSet<>(injectedViews.keySet());
            if (!desired.equals(currentAnchors)) {
                // The keyguard and the main status bar each own a separate mobile view.
                // Rebuild both hosts whenever the set of anchors changes.
                restoreAll();
            }
            startFallbackTicker();

            int dataSubId = defaultDataSubId();
            List<Integer> ordered = orderSubs(active, dataSubId);
            int dataSub = ordered.get(0);
            int otherSub = ordered.get(1);
            String type = typeFor(dataSub, context);
            if (TextUtils.isEmpty(type)) {
                ViewGroup dataView = firstViewFor(dataSub);
                type = typeFromOfficialView(dataView != null ? dataView : anchors.get(0));
            }
            int dataLevel = levelFor(dataSub, context);
            int otherLevel = levelFor(otherSub, context);
            int scale = clamp(HookPrefs.getInt(Keys.DUAL_SIGNAL_SCALE,
                            Keys.DUAL_SIGNAL_SCALE_DEFAULT),
                    Keys.DUAL_SIGNAL_SCALE_MIN, Keys.DUAL_SIGNAL_SCALE_MAX);
            int thickness = clamp(HookPrefs.getInt(Keys.DUAL_SIGNAL_THICKNESS,
                            Keys.DUAL_SIGNAL_THICKNESS_DEFAULT),
                    Keys.DUAL_SIGNAL_THICKNESS_MIN, Keys.DUAL_SIGNAL_THICKNESS_MAX);
            int textScale = clamp(HookPrefs.getInt(Keys.DUAL_TEXT_SCALE,
                            Keys.DUAL_TEXT_SCALE_DEFAULT),
                    Keys.DUAL_TEXT_SCALE_MIN, Keys.DUAL_TEXT_SCALE_MAX);
            int textThickness = clamp(HookPrefs.getInt(Keys.DUAL_TEXT_THICKNESS,
                            Keys.DUAL_TEXT_THICKNESS_DEFAULT),
                    Keys.DUAL_TEXT_THICKNESS_MIN, Keys.DUAL_TEXT_THICKNESS_MAX);

            for (ViewGroup anchor : anchors) {
                applyDualIcon(anchor, type, dataLevel, otherLevel,
                        scale, thickness, textScale, textThickness);
            }
        } catch (Throwable t) {
            Logs.e("Dual-signal refresh failed", t);
        }
    }

    private void applyDualIcon(ViewGroup anchor, String type, int dataLevel, int otherLevel,
                               int scale, int thickness, int textScale, int textThickness) {
        Context context = anchor.getContext();
        View signalContainerView = findByIdName(anchor, "mobile_signal_container");
        ViewGroup signalContainer = signalContainerView instanceof ViewGroup
                ? (ViewGroup) signalContainerView : null;
        View mobileGroupView = findByIdName(anchor, "mobile_group");
        ViewGroup mobileGroup = mobileGroupView instanceof ViewGroup
                ? (ViewGroup) mobileGroupView : null;

        // The custom container is a sibling of the official signal container. It has its
        // own text and icon children, so scaling the icon cannot shrink the text or the bars.
        ViewGroup host = mobileGroup != null ? mobileGroup
                : (signalContainer != null ? signalContainer : anchor);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        anchor.setClipChildren(false);

        View custom = injectedViews.get(anchor);
        if (custom == null || custom.getParent() != host) {
            if (custom != null && custom.getParent() instanceof ViewGroup) {
                ((ViewGroup) custom.getParent()).removeView(custom);
            }
            custom = new DualSignalView(context);
            custom.setId(View.generateViewId());
            if (host instanceof LinearLayout) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.CENTER_VERTICAL;
                int index = signalContainer != null && signalContainer.getParent() == host
                        ? host.indexOfChild(signalContainer) : -1;
                try {
                    if (index >= 0) {
                        host.addView(custom, index, params);
                    } else {
                        host.addView(custom, params);
                    }
                } catch (Throwable t) {
                    Logs.w("Unable to add dual-signal container, using fallback layout", t);
                    host.addView(custom);
                }
            } else {
                try {
                    host.addView(custom);
                } catch (Throwable t) {
                    Logs.w("Unable to add dual-signal container", t);
                }
            }
            injectedViews.put(anchor, custom);
        }

        View typeSingle = findByIdName(anchor, "mobile_type_single");
        View typeSmall = findByIdName(anchor, "mobile_type");
        if (mobileGroup != null && signalContainer != null) {
            // The custom container replaces the whole official signal area, including its
            // private type icon and activity indicator.
            hide(signalContainer);
        } else {
            hide(findByIdName(anchor, "mobile_signal"));
        }
        hide(typeSmall);
        // The big 5G/4G TextView gets re-shown by SystemUI while the status bar re-lays out
        // around the privacy dot. Detaching it used to fail too: once detached the system can
        // simply add it back and we would no longer be watching it. Hide it through the same
        // guarded path instead, so the per-frame guard keeps it suppressed.
        hide(typeSingle);
        custom.bringToFront();

        // Collapse the other SIM view in the same status bar only. The keyguard and the
        // main status bar each have their own mobile group, and both must show the icon.
        ViewGroup parent = anchor.getParent() instanceof ViewGroup
                ? (ViewGroup) anchor.getParent() : null;
        if (parent != null) {
            for (Map.Entry<ViewGroup, Integer> entry : mobileViews.entrySet()) {
                ViewGroup other = entry.getKey();
                if (other != anchor && other.getParent() == parent) {
                    hide(other);
                }
            }
        }

        DualSignalView view = (DualSignalView) custom;
        view.setScale(scale, thickness, textScale, textThickness);
        view.setState(type, dataLevel, otherLevel, resolveColor(anchor));
    }

    private ViewGroup firstAttachedView() {
        for (ViewGroup view : mobileViews.keySet()) {
            if (view != null && view.isAttachedToWindow()) {
                return view;
            }
        }
        return null;
    }

    private ViewGroup firstViewFor(int subId) {
        for (Map.Entry<ViewGroup, Integer> entry : mobileViews.entrySet()) {
            if (entry.getValue() == subId && entry.getKey().isAttachedToWindow()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private List<ViewGroup> chooseAnchors(List<Integer> active) {
        Map<ViewGroup, ViewGroup> anchors = new LinkedHashMap<>();
        Map<ViewGroup, Integer> anchorSlots = new HashMap<>();
        for (Map.Entry<ViewGroup, Integer> entry : mobileViews.entrySet()) {
            ViewGroup view = entry.getKey();
            if (view == null || !view.isAttachedToWindow()) {
                continue;
            }
            if (!active.contains(entry.getValue())) {
                continue;
            }
            android.view.ViewParent parent = view.getParent();
            if (!(parent instanceof ViewGroup)) {
                continue;
            }
            ViewGroup host = (ViewGroup) parent;
            int slot = slotOf(entry.getValue());
            Integer current = anchorSlots.get(host);
            if (current == null || slot < current) {
                anchors.put(host, view);
                anchorSlots.put(host, slot);
            }
        }
        return new ArrayList<>(anchors.values());
    }

    @SuppressLint("MissingPermission")
    private List<Integer> activeSubIds(Context context) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        boolean authoritative = false;
        try {
            SubscriptionManager manager = (SubscriptionManager) context.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (manager != null) {
                List<SubscriptionInfo> infos = manager.getActiveSubscriptionInfoList();
                if (infos != null) {
                    authoritative = true;
                    for (SubscriptionInfo info : infos) {
                        if (info != null) {
                            result.add(info.getSubscriptionId());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the cached mobile views.
        }
        if (!authoritative) {
            result.addAll(mobileViews.values());
        }
        return new ArrayList<>(result);
    }

    private List<Integer> orderSubs(List<Integer> active, int dataSubId) {
        List<Integer> ordered = new ArrayList<>(active);
        ordered.sort((left, right) -> {
            if (left == dataSubId && right != dataSubId) {
                return -1;
            }
            if (right == dataSubId && left != dataSubId) {
                return 1;
            }
            boolean leftData = Boolean.TRUE.equals(dataSimBySubId.get(left));
            boolean rightData = Boolean.TRUE.equals(dataSimBySubId.get(right));
            if (leftData != rightData) {
                return leftData ? -1 : 1;
            }
            int leftSlot = slotOf(left);
            int rightSlot = slotOf(right);
            if (leftSlot != rightSlot) {
                if (leftSlot < 0) {
                    return 1;
                }
                if (rightSlot < 0) {
                    return -1;
                }
                return Integer.compare(leftSlot, rightSlot);
            }
            return Integer.compare(left, right);
        });
        return ordered;
    }

    private int defaultDataSubId() {
        try {
            return SubscriptionManager.getDefaultDataSubscriptionId();
        } catch (Throwable ignored) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    private int slotOf(int subId) {
        Integer cached = slotBySubId.get(subId);
        if (cached != null && cached >= 0) {
            return cached;
        }
        try {
            int slot = SubscriptionManager.getSlotIndex(subId);
            if (slot >= 0) {
                slotBySubId.put(subId, slot);
            }
            return slot;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int levelFor(int subId, Context context) {
        Integer value = levelBySubId.get(subId);
        if (value != null) {
            return clamp(value, 0, 4);
        }
        return fallbackSignalLevel(subId, context);
    }

    private String typeFor(int subId, Context context) {
        String value = typeBySubId.get(subId);
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        return fallbackNetworkType(subId, context);
    }

    private String typeFromOfficialView(View anchor) {
        View typeSingle = findByIdName(anchor, "mobile_type_single");
        if (typeSingle instanceof TextView) {
            CharSequence text = ((TextView) typeSingle).getText();
            if (text != null) {
                String normalized = normalizeType(text.toString());
                if (!TextUtils.isEmpty(normalized)) {
                    return normalized;
                }
            }
        }
        View typeSmall = findByIdName(anchor, "mobile_type");
        if (typeSmall instanceof ImageView) {
            Object drawable = ((ImageView) typeSmall).getDrawable();
            Object value = Reflect.getField(drawable, "mMobileType");
            if (value instanceof String) {
                return normalizeType((String) value);
            }
        }
        return "";
    }

    private int resolveColor(View anchor) {
        Tint current = tint;
        if (current.known) {
            return current.resolve();
        }
        int color = anchorColor(anchor);
        return color != 0 ? color : Color.WHITE;
    }

    private int anchorColor(View anchor) {
        View typeSingle = findByIdName(anchor, "mobile_type_single");
        if (typeSingle instanceof TextView) {
            int color = ((TextView) typeSingle).getCurrentTextColor();
            if (Color.alpha(color) != 0) {
                return color;
            }
        }
        View type = findByIdName(anchor, "mobile_type");
        int color = filterColor(type);
        if (color != 0) {
            return color;
        }
        return filterColor(findByIdName(anchor, "mobile_signal"));
    }

    private int filterColor(View view) {
        if (!(view instanceof ImageView)) {
            return 0;
        }
        ColorFilter filter = ((ImageView) view).getColorFilter();
        if (filter instanceof PorterDuffColorFilter) {
            try {
                Method getColor = filter.getClass().getMethod("getColor");
                Object value = getColor.invoke(filter);
                if (value instanceof Integer && Color.alpha((Integer) value) != 0) {
                    return (Integer) value;
                }
            } catch (Throwable ignored) {
                // getColor() is not part of the public SDK stubs; best effort only.
            }
        }
        return 0;
    }

    // ==================================================================================
    // View management
    // ==================================================================================

    private void hide(View view) {
        if (view == null) {
            return;
        }
        SavedState saved = hiddenStates.get(view);
        if (saved == null) {
            saved = SavedState.capture(view);
            hiddenStates.put(view, saved);
        }
        applyHidden(view);
        installGuard(view);
    }

    /**
     * 把视图压回"不可见且不占位"，幂等：系统在任意一次重排里都可能把 visibility 或
     * LayoutParams 改回来，所以要能随时再调一次。
     *
     * <p>用 {@link View#INVISIBLE} 而不是 {@link View#GONE}：GONE 会让父容器重新测量布局，
     * 系统反而更容易把尺寸改回去；INVISIBLE 保留槽位，配合 1px 尺寸在视觉上同样不可见，
     * 但不需要父容器重排。1px 而不是 0：ConstraintLayout 里 0dp 等于 MATCH_CONSTRAINT，
     * 写 0 会把视图撑开。</p>
     */
    private boolean applyHidden(View view) {
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
     * 给视图装一个"每帧绘制前重新压制"的守卫。状态栏围绕隐私绿点重排时，系统会在我们的
     * hide() 之后把 visibility 设回 VISIBLE，普通的一次性隐藏挡不住。OnPreDrawListener 在
     * 所有测量/布局/绘制之前回调，每次都能把状态顶回去，而且不依赖任何 HyperOS 内部类名。
     */
    private void installGuard(final View view) {
        if (guards.containsKey(view)) {
            return;
        }
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            try {
                if (hiddenStates.containsKey(view) && applyHidden(view)) {
                    guardRestores++;
                    if (guardRestores <= 3 || guardRestores % 50 == 0) {
                        Logs.i("dual-row: guard re-hid " + describe(view)
                                + " (total " + guardRestores + ")");
                    }
                }
            } catch (Throwable t) {
                Logs.w("dual-row: visibility guard failed", t);
            }
            return true;
        };
        try {
            view.getViewTreeObserver().addOnPreDrawListener(listener);
            guards.put(view, listener);
            // ViewTreeObserver 实例会随 attach/detach 更换，重新挂上时补装一次。
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View attached) {
                    ViewTreeObserver.OnPreDrawListener stale = guards.remove(attached);
                    if (stale != null) {
                        try {
                            attached.getViewTreeObserver().removeOnPreDrawListener(stale);
                        } catch (Throwable ignored) {
                            // The old observer is gone with the previous window attachment.
                        }
                    }
                    installGuard(attached);
                }

                @Override
                public void onViewDetachedFromWindow(View detached) {
                    // Reinstalled on the next attach.
                }
            });
        } catch (Throwable t) {
            Logs.w("dual-row: unable to install visibility guard", t);
        }
    }

    private static String describe(View view) {
        if (view == null) {
            return "null";
        }
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                return view.getContext().getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
            // Fall through to the class name.
        }
        return view.getClass().getSimpleName();
    }

    private void removeGuard(View view) {
        ViewTreeObserver.OnPreDrawListener listener = guards.remove(view);
        if (listener == null) {
            return;
        }
        try {
            view.getViewTreeObserver().removeOnPreDrawListener(listener);
        } catch (Throwable ignored) {
            // The observer may already be gone with the window attachment.
        }
    }

    private void restoreAll() {
        if (!injectedViews.isEmpty()) {
            for (View custom : new ArrayList<>(injectedViews.values())) {
                if (custom != null && custom.getParent() instanceof ViewGroup) {
                    ((ViewGroup) custom.getParent()).removeView(custom);
                }
            }
            injectedViews.clear();
        }
        restoreHidden();
    }

    private void detachView(View view) {
        if (view == null) {
            return;
        }
        SavedState saved = hiddenStates.get(view);
        if (saved == null) {
            saved = SavedState.capture(view);
            hiddenStates.put(view, saved);
        }
        android.view.ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) {
            hide(view);
            return;
        }
        if (saved.detachedFrom == null) {
            saved.detachedFrom = (ViewGroup) parent;
            saved.detachedIndex = saved.detachedFrom.indexOfChild(view);
            saved.detachedParams = view.getLayoutParams();
        }
        ((ViewGroup) parent).removeView(view);
    }

    private void restoreHidden() {
        // Drop the per-frame guards first, otherwise the view would be hidden again
        // immediately after we restore it.
        for (View guarded : new ArrayList<>(guards.keySet())) {
            removeGuard(guarded);
        }
        Map<ViewGroup, List<SavedState>> detached = new LinkedHashMap<>();
        for (SavedState saved : hiddenStates.values()) {
            if (saved.detachedFrom != null) {
                detached.computeIfAbsent(saved.detachedFrom, key -> new ArrayList<>()).add(saved);
            }
        }
        for (Map.Entry<ViewGroup, List<SavedState>> group : detached.entrySet()) {
            group.getValue().sort((left, right) ->
                    Integer.compare(left.detachedIndex, right.detachedIndex));
            for (SavedState saved : group.getValue()) {
                if (saved.view == null || saved.view.getParent() != null) {
                    continue;
                }
                int index = Math.min(saved.detachedIndex, group.getKey().getChildCount());
                try {
                    if (saved.detachedParams != null) {
                        group.getKey().addView(saved.view, index, saved.detachedParams);
                    } else {
                        group.getKey().addView(saved.view, index);
                    }
                } catch (Throwable ignored) {
                    // The old parent may be detached; the new status bar will rebind.
                }
            }
        }
        for (Map.Entry<View, SavedState> entry : hiddenStates.entrySet()) {
            View view = entry.getKey();
            SavedState saved = entry.getValue();
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

    private View findByIdName(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = idFor(root.getContext(), name);
        if (id == 0) {
            return null;
        }
        return findById(root, id);
    }

    private static View findById(View root, int id) {
        if (root == null) {
            return null;
        }
        if (root.getId() == id) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findById(group.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressLint("DiscouragedApi")
    private int idFor(Context context, String name) {
        String packageName = context.getPackageName();
        String key = packageName + ':' + name;
        Integer cached = resourceIds.get(key);
        if (cached != null) {
            return cached;
        }
        int id = 0;
        try {
            Resources resources = context.getResources();
            id = resources.getIdentifier(name, "id", packageName);
        } catch (Throwable ignored) {
            // Try the framework package below.
        }
        if (id == 0) {
            try {
                id = context.getResources().getIdentifier(name, "id", "android");
            } catch (Throwable ignored) {
                // Leave as zero.
            }
        }
        resourceIds.put(key, id);
        return id;
    }

    // ==================================================================================
    // Telephony fallbacks
    // ==================================================================================

    @SuppressLint("MissingPermission")
    private int fallbackSignalLevel(int subId, Context context) {
        try {
            TelephonyManager manager = telephonyFor(context, subId);
            if (manager == null) {
                return 0;
            }
            SignalStrength strength = manager.getSignalStrength();
            if (strength == null) {
                return 0;
            }
            return clamp(strength.getLevel(), 0, 4);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @SuppressLint("MissingPermission")
    private String fallbackNetworkType(int subId, Context context) {
        try {
            TelephonyManager manager = telephonyFor(context, subId);
            if (manager == null) {
                return "";
            }
            return networkTypeLabel(manager.getDataNetworkType());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private TelephonyManager telephonyFor(Context context, int subId) {
        try {
            TelephonyManager base = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            if (base == null) {
                return null;
            }
            if (subId >= 0) {
                return base.createForSubscriptionId(subId);
            }
            return base;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String networkTypeLabel(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case 19: // NETWORK_TYPE_LTE_CA
                return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "3G+";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            default:
                return "";
        }
    }

    private static boolean isAirplaneModeOn(Context context) {
        try {
            return android.provider.Settings.Global.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        String type = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (type.length() > 4) {
            type = type.substring(0, 4);
        }
        return type;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int getIntField(Object target, String name, int fallback) {
        Object value = Reflect.getField(target, name);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    // ==================================================================================
    // App-level plumbing
    // ==================================================================================

    private void ensurePrefsListener() {
        if (prefsListenerRegistered) {
            return;
        }
        SharedPreferences prefs = HookPrefs.raw();
        if (prefs == null) {
            return;
        }
        prefsListener = (sharedPreferences, key) -> {
            if (Keys.DUAL_ROW_SIGNAL.equals(key)
                    || Keys.DUAL_SIGNAL_SCALE.equals(key)
                    || Keys.DUAL_SIGNAL_THICKNESS.equals(key)
                    || Keys.DUAL_TEXT_SCALE.equals(key)
                    || Keys.DUAL_TEXT_THICKNESS.equals(key)) {
                scheduleRefresh();
            }
        };
        try {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            prefsListenerRegistered = true;
        } catch (Throwable t) {
            prefsListener = null;
            Logs.w("Unable to observe the dual-signal switch", t);
        }
    }

    private void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) {
            return;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            filter.addAction("android.intent.action.SIM_STATE_CHANGED");
            filter.addAction("android.intent.action.AIRPLANE_MODE");
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    scheduleRefresh();
                }
            };
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) {
            Logs.w("Unable to register dual-signal broadcasts", t);
        }
    }

    private void startFallbackTicker() {
        if (fallbackTicker != null) {
            return;
        }
        fallbackTicker = new Runnable() {
            @Override
            public void run() {
                if (!HookPrefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false)) {
                    fallbackTicker = null;
                    return;
                }
                // The controller hook is the primary update source. This slow ticker is a
                // safety net for HyperOS builds that rename the controller internals, and it
                // only runs while the feature is enabled.
                scheduleRefresh();
                mainHandler.postDelayed(this, FALLBACK_TICK_MS);
            }
        };
        mainHandler.postDelayed(fallbackTicker, FALLBACK_TICK_MS);
    }

    private void stopFallbackTicker() {
        Runnable ticker = fallbackTicker;
        if (ticker != null) {
            mainHandler.removeCallbacks(ticker);
            fallbackTicker = null;
        }
    }

    // ==================================================================================
    // Tint / state helpers
    // ==================================================================================

    private static final class Tint {
        final boolean known;
        final boolean useTint;
        final boolean light;
        final int color;

        private Tint(boolean known, boolean useTint, boolean light, int color) {
            this.known = known;
            this.useTint = useTint;
            this.light = light;
            this.color = color;
        }

        static Tint unknown() {
            return new Tint(false, true, true, Color.WHITE);
        }

        static Tint fromTriple(Object triple) {
            if (triple == null || !"Triple".equals(triple.getClass().getSimpleName())) {
                return unknown();
            }
            Object first = Reflect.call(triple, "getFirst");
            Object second = Reflect.call(triple, "getSecond");
            Object third = Reflect.call(triple, "getThird");
            if (!(first instanceof Boolean) && !(second instanceof Boolean)
                    && !(third instanceof Integer)) {
                return unknown();
            }
            boolean useTint = first instanceof Boolean ? (Boolean) first : false;
            boolean light = second instanceof Boolean ? (Boolean) second : true;
            int color = third instanceof Integer ? (Integer) third : Color.WHITE;
            return new Tint(true, useTint, light, color);
        }

        int resolve() {
            if (useTint) {
                return color == 0 ? Color.WHITE : color;
            }
            return light ? 0xE6FFFFFF : 0xBF000000;
        }
    }

    private static final class SavedState {
        View view;
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
        ViewGroup detachedFrom;
        int detachedIndex;
        ViewGroup.LayoutParams detachedParams;

        static SavedState capture(View view) {
            SavedState state = new SavedState();
            state.view = view;
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
