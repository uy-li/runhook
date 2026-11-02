package com.uy_li.runhook;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class JoyrunHook implements IXposedHookLoadPackage {

    private static final String MODULE_PACKAGE = "com.uy_li.runhook";
    private static final String ACTION_TOGGLE = "com.uy_li.TOGGLE_CADENCE";
    private static final String STEP_PREFS = "runhook_step_state";

    // 默认必须关闭。以前写成 true，进程一启动（包括重启后的系统服务）就会改步数。
    private static volatile boolean isModifyCadence = false;
    private static volatile String currentPackage;

    private static float minCadenceConfig = ConfigStore.DEFAULT_MIN;
    private static float maxCadenceConfig = ConfigStore.DEFAULT_MAX;
    private static float currentCadence = 176f;
    private static long waveStartMs = 0L;

    private static final Object STATE_LOCK = new Object();
    private static final StepAccumulator accumulator = new StepAccumulator();
    private static Context appContext;
    private static boolean stepStateLoaded;
    private static long lastStepSaveElapsed;

    private static XSharedPreferences xPrefs;
    private static boolean prefListenerRegistered;
    private static boolean reloadingPrefs;
    private static long lastPrefReloadElapsed;
    private static long lastExplicitToggleElapsed = -1L;

    private static final Set<Class<?>> hookedListenerClasses = ConcurrentHashMap.newKeySet();
    private static final Map<SensorEventListener, SensorEventListener> wrappedListeners = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastTimestamps = new ConcurrentHashMap<>();

    private final XC_MethodHook sensorEventHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args != null && param.args.length > 0 && param.args[0] instanceof SensorEvent) {
                modifyEvent((SensorEvent) param.args[0]);
            }
        }
    };

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        currentPackage = pkg;

        if (!ConfigStore.isTargetPackage(pkg)) {
            return;
        }

        XposedBridge.log("JoyrunHook loaded for package: " + pkg + " (engine default OFF)");

        // ==========================================
        // 1. 悬浮窗广播 + 持久化开关
        // ==========================================
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    appContext = app;
                    loadStepState();
                    reloadConfigFromPrefs(true);

                    BroadcastReceiver receiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (intent == null || !ACTION_TOGGLE.equals(intent.getAction())) return;
                            if (!intent.hasExtra("STATE")) return;

                            boolean state = intent.getBooleanExtra("STATE", false);
                            float min = intent.hasExtra("MIN_CADENCE")
                                    ? intent.getFloatExtra("MIN_CADENCE", minCadenceConfig)
                                    : minCadenceConfig;
                            float max = intent.hasExtra("MAX_CADENCE")
                                    ? intent.getFloatExtra("MAX_CADENCE", maxCadenceConfig)
                                    : maxCadenceConfig;
                            boolean quiet = intent.getBooleanExtra("QUIET", false);

                            boolean changed;
                            synchronized (STATE_LOCK) {
                                lastExplicitToggleElapsed = SystemClock.elapsedRealtime();
                                changed = applyEnabledLocked(state, min, max);
                            }
                            if (changed && !state) {
                                saveStepState(true);
                            }
                            if (changed && !quiet) {
                                String statusMsg = state
                                        ? ("已开启 (范围 " + (int) minCadenceConfig + "-" + (int) maxCadenceConfig + ")")
                                        : "已关闭";
                                Toast.makeText(context, "万能接口拦截(一加专版) " + statusMsg, Toast.LENGTH_SHORT).show();
                                XposedBridge.log("JoyrunHook: " + pkg + " 收到广播，万能接口拦截 " + statusMsg);
                            }
                        }
                    };

                    IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                    } else {
                        app.registerReceiver(receiver, filter);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: Broadcast hook skipped for " + pkg + ": " + t.getMessage());
        }

        // ==========================================
        // 2. 万能接口 Hook：动态锁定所有的 SensorEventListener
        // ==========================================
        try {
            hookSensorManager(android.hardware.SensorManager.class);
            try {
                Class<?> sysManagerClass = XposedHelpers.findClass("android.hardware.SystemSensorManager", lpparam.classLoader);
                hookSensorManager(sysManagerClass);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> oplusManagerClass = XposedHelpers.findClass("com.oplus.sensor.OplusSensorManager", lpparam.classLoader);
                if (oplusManagerClass != null) {
                    hookSensorManager(oplusManagerClass);
                    XposedBridge.log("JoyrunHook: 发现并准备 Hook OplusSensorManager in " + pkg);
                }
            } catch (Throwable ignored) {
            }

            XposedBridge.log("JoyrunHook: 成功建立系统级万能接口分发拦截器 in " + pkg);
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: 建立万能接口拦截器失败: " + t.getMessage());
        }
    }

    private void hookSensorManager(Class<?> managerClass) {
        if (managerClass == null) return;

        for (Method method : managerClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!"registerListener".equals(name) && !"unregisterListener".equals(name)) continue;
            final boolean unregister = "unregisterListener".equals(name);
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        rewriteListenerArg(param, unregister);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("JoyrunHook: hook " + name + " failed on " + managerClass.getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * 优先 Hook 真正声明了 onSensorChanged 的类，这样 unregister 仍然用原来的对象。
     * 动态代理没有这个方法，Hook 失败时才包一层，并在 unregister 时换回去。
     */
    private void rewriteListenerArg(XC_MethodHook.MethodHookParam param, boolean unregister) {
        Object[] args = param.args;
        if (args == null) return;

        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof SensorEventListener)) continue;
            SensorEventListener listener = (SensorEventListener) args[i];
            if (listener instanceof InjectingListener) return;

            if (!unregister) {
                if (ensureListenerHooked(listener)) return;
                SensorEventListener wrapped = wrappedListeners.get(listener);
                if (wrapped == null) {
                    wrapped = new InjectingListener(listener);
                    wrappedListeners.put(listener, wrapped);
                    XposedBridge.log("JoyrunHook: 改为包装监听 " + listener.getClass().getName());
                }
                args[i] = wrapped;
                return;
            }

            boolean partial = false;
            for (Object arg : args) {
                if (arg instanceof Sensor) partial = true;
            }
            SensorEventListener wrapped = partial
                    ? wrappedListeners.get(listener)
                    : wrappedListeners.remove(listener);
            if (wrapped != null) args[i] = wrapped;
            return;
        }
    }

    private boolean ensureListenerHooked(SensorEventListener listener) {
        Class<?> declaring = findDeclaringClass(listener.getClass());
        if (declaring == null) return false;
        if (!hookedListenerClasses.add(declaring)) return true;
        try {
            XposedHelpers.findAndHookMethod(declaring, "onSensorChanged", SensorEvent.class, sensorEventHook);
            XposedBridge.log("JoyrunHook: Successfully locked onto onSensorChanged for: " + declaring.getName());
            return true;
        } catch (Throwable t) {
            hookedListenerClasses.remove(declaring);
            XposedBridge.log("JoyrunHook: Failed to hook onSensorChanged for: " + declaring.getName() + " -> " + t.getMessage());
            return false;
        }
    }

    private static Class<?> findDeclaringClass(Class<?> type) {
        while (type != null && type != Object.class) {
            try {
                type.getDeclaredMethod("onSensorChanged", SensorEvent.class);
                return type;
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static void reloadConfigFromPrefs(boolean force) {
        if (reloadingPrefs) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPrefReloadElapsed < 1000) return;
        // 本进程已经收过悬浮窗广播之后，以广播为准。
        // 否则定时同步和文件轮询会来回覆盖，开关来回跳。
        if (!force && lastExplicitToggleElapsed >= 0) return;

        reloadingPrefs = true;
        lastPrefReloadElapsed = now;
        try {
            if (xPrefs == null) {
                xPrefs = new XSharedPreferences(MODULE_PACKAGE, ConfigStore.PREF_NAME);
                try {
                    xPrefs.makeWorldReadable();
                } catch (Throwable ignored) {
                }
                tryRegisterPrefListener();
            }
            xPrefs.reload();
            Map<String, ?> all = xPrefs.getAll();
            // 没有这份键就保持内存状态。进程刚起来时内存是关，所以不会默认注入。
            if (all == null || !all.containsKey(ConfigStore.KEY_ENABLED)) return;

            boolean enabled = xPrefs.getBoolean(ConfigStore.KEY_ENABLED, false);
            float min = xPrefs.getFloat(ConfigStore.KEY_MIN, ConfigStore.DEFAULT_MIN);
            float max = xPrefs.getFloat(ConfigStore.KEY_MAX, ConfigStore.DEFAULT_MAX);
            boolean turnedOff;
            synchronized (STATE_LOCK) {
                boolean was = isModifyCadence;
                applyEnabledLocked(enabled, min, max);
                turnedOff = was && !isModifyCadence;
            }
            if (turnedOff) saveStepState(true);
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: 读取配置失败，保持当前开关: " + t.getMessage());
        } finally {
            reloadingPrefs = false;
        }
    }

    private static void tryRegisterPrefListener() {
        if (prefListenerRegistered || xPrefs == null) return;
        try {
            xPrefs.registerOnSharedPreferenceChangeListener((SharedPreferences prefs, String key) -> reloadConfigFromPrefs(false));
            prefListenerRegistered = true;
        } catch (Throwable ignored) {
            // 旧版 XSharedPreferences 不支持监听，靠定时 reload 和广播。
        }
    }

    /** 调用方需要持有 STATE_LOCK。返回值表示开关是否变化。 */
    private static boolean applyEnabledLocked(boolean enabled, float min, float max) {
        if (min > 0f && max >= min) {
            minCadenceConfig = min;
            maxCadenceConfig = max;
        }
        boolean changed = enabled != isModifyCadence;
        if (enabled && !isModifyCadence) {
            float span = maxCadenceConfig - minCadenceConfig;
            currentCadence = minCadenceConfig + (span > 0f ? (float) (Math.random() * span) : 0f);
            long now = System.currentTimeMillis();
            // 关掉的那段时间不能补进步数里。
            accumulator.lastUpdateMs = now;
            waveStartMs = now;
            XposedBridge.log("JoyrunHook: 引擎开启，步频 " + currentCadence);
        } else if (!enabled && isModifyCadence) {
            XposedBridge.log("JoyrunHook: 引擎关闭，停止累加");
        }
        isModifyCadence = enabled;
        return changed;
    }

    private static void loadStepState() {
        synchronized (STATE_LOCK) {
            if (stepStateLoaded || appContext == null) return;
            stepStateLoaded = true;
            try {
                SharedPreferences sp = appContext.getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE);
                accumulator.boost = Math.max(0f, sp.getFloat("boost", 0f));
                accumulator.lastReal = sp.getFloat("last_real", -1f);
            } catch (Throwable t) {
                XposedBridge.log("JoyrunHook: 读取步数偏移失败: " + t.getMessage());
            }
        }
    }

    private static void saveStepState(boolean force) {
        Context ctx = appContext;
        if (ctx == null) return;
        float boost;
        float lastReal;
        synchronized (STATE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastStepSaveElapsed < 5000) return;
            lastStepSaveElapsed = now;
            boost = accumulator.boost;
            lastReal = accumulator.lastReal;
        }
        try {
            ctx.getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("boost", boost)
                    .putFloat("last_real", lastReal)
                    .commit();
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: 保存步数偏移失败: " + t.getMessage());
        }
    }

    private static void modifyEvent(SensorEvent event) {
        try {
            if (event == null || event.sensor == null || event.values == null) return;
            reloadConfigFromPrefs(false);

            int type = event.sensor.getType();
            if (type == Sensor.TYPE_STEP_COUNTER || type == 19) {
                modifyStepCounter(event);
                return;
            }

            boolean enabled;
            float cadence;
            long waveStart;
            synchronized (STATE_LOCK) {
                enabled = isModifyCadence;
                cadence = currentCadence;
                waveStart = waveStartMs;
            }
            if (!enabled) return;

            if (type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
                // 系统计步服务自己的加速度融合一旦被改掉，可能把已保存的步数记录判定为异常并清掉。
                if (!spoofAccelerometer()) return;
                if (waveStart == 0L) waveStart = System.currentTimeMillis();
                float elapsedSecs = (System.currentTimeMillis() - waveStart) / 1000f;
                float freq = cadence / 60.0f;

                float zWave = 9.81f + (float) (Math.sin(elapsedSecs * Math.PI * 2 * freq) * 3.5f);
                float yWave = (float) (Math.cos(elapsedSecs * Math.PI * 2 * freq) * 1.5f);
                float jitterY = (float) (Math.random() * 0.4f - 0.2f);
                float jitterZ = (float) (Math.random() * 0.4f - 0.2f);

                event.values[0] = 0.0f;
                event.values[1] = yWave + jitterY;
                event.values[2] = zWave + jitterZ;
                keepTimestampMonotonic(event);
            } else if (type == Sensor.TYPE_STEP_DETECTOR && event.values.length >= 1) {
                event.values[0] = 1.0f;
                keepTimestampMonotonic(event);
            }
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: modify failed: " + t.getMessage());
        }
    }

    /**
     * 不把 timestamp 改成“现在”。灭屏后传感器会一次吐出一批事件，
     * 如果全部盖成同一个时间，步频会变成无穷大，OPPO 健康会丢掉这条传感器流，直到进程重启。
     */
    private static void keepTimestampMonotonic(SensorEvent event) {
        int type = event.sensor.getType();
        long ts = event.timestamp;
        Long prev = lastTimestamps.get(type);
        if (prev != null && ts <= prev) {
            ts = prev + 1_000_000L;
            event.timestamp = ts;
        }
        lastTimestamps.put(type, ts);
    }

    private static boolean spoofAccelerometer() {
        // system_server 里改加速度会影响系统自己的计步融合，有机会把已经落盘的步数记录判成异常。
        // 健康 App 进程里仍然模拟加速度，否则只认波形的应用不会涨步数。
        return !"android".equals(currentPackage);
    }

    private static void modifyStepCounter(SensorEvent event) {
        if (event.values.length < 1) return;
        loadStepState();

        if (!stepStateLoaded && appContext == null) return;

        float real = event.values[0];
        long now = System.currentTimeMillis();
        boolean reset;
        boolean hold;
        float reported;
        synchronized (STATE_LOCK) {
            // 关着，并且没有需要托住的历史偏移：原样放行，不做任何改写。
            if (!isModifyCadence && accumulator.boost <= 0f) return;
            reset = accumulator.advance(real, now, isModifyCadence, currentCadence / 60f);
            hold = accumulator.shouldRewrite();
            reported = accumulator.reported(real);
        }
        if (reset) {
            saveStepState(true);
            return;
        }
        if (!hold) return;
        event.values[0] = reported;
        keepTimestampMonotonic(event);
        saveStepState(false);
    }

    private static final class InjectingListener implements SensorEventListener {
        final SensorEventListener delegate;

        InjectingListener(SensorEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            modifyEvent(event);
            delegate.onSensorChanged(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            delegate.onAccuracyChanged(sensor, accuracy);
        }
    }
}
