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
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    private static boolean userEnabled;
    private static boolean altitudeEnabled;
    private static float altMinConfig = AltitudeCurve.DEFAULT_MIN;
    private static float altMaxConfig = AltitudeCurve.DEFAULT_MAX;
    private static final CadenceCurve cadenceCurve = new CadenceCurve(1L);
    private static final AltitudeCurve altitudeCurve = new AltitudeCurve(1L);
    private static boolean pendingCurve;
    private static long pendingCurveSeed = 1L;
    private static long pendingCurveStart;
    private static boolean pendingAlt;
    private static long pendingAltSeed = 1L;
    private static long pendingAltStart;

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
    private static volatile String controlToken = "";
    private static final ThreadLocal<SensorEvent> activeEvent = new ThreadLocal<>();

    private static final Set<Class<?>> hookedListenerClasses = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> hookedLocationClasses = ConcurrentHashMap.newKeySet();
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
                            if (!acceptControlToken(intent)) return;

                            boolean state = intent.getBooleanExtra("STATE", false);
                            float min = intent.hasExtra("MIN_CADENCE")
                                    ? intent.getFloatExtra("MIN_CADENCE", minCadenceConfig)
                                    : minCadenceConfig;
                            float max = intent.hasExtra("MAX_CADENCE")
                                    ? intent.getFloatExtra("MAX_CADENCE", maxCadenceConfig)
                                    : maxCadenceConfig;
                            boolean altitudeSpecified = intent.hasExtra("ALT_ENABLED")
                                    || intent.hasExtra("MIN_ALTITUDE")
                                    || intent.hasExtra("MAX_ALTITUDE");
                            boolean altitudeOn = intent.hasExtra("ALT_ENABLED")
                                    ? intent.getBooleanExtra("ALT_ENABLED", altitudeEnabled)
                                    : altitudeEnabled;
                            float altMin = intent.hasExtra("MIN_ALTITUDE")
                                    ? intent.getFloatExtra("MIN_ALTITUDE", altMinConfig)
                                    : altMinConfig;
                            float altMax = intent.hasExtra("MAX_ALTITUDE")
                                    ? intent.getFloatExtra("MAX_ALTITUDE", altMaxConfig)
                                    : altMaxConfig;
                            boolean quiet = intent.getBooleanExtra("QUIET", false);

                            boolean changed;
                            synchronized (STATE_LOCK) {
                                lastExplicitToggleElapsed = SystemClock.elapsedRealtime();
                                changed = applyUserStateLocked(state, min, max, altitudeOn, altMin, altMax,
                                        altitudeSpecified, true, System.currentTimeMillis());
                            }
                            if (changed && !state) {
                                saveStepState(true);
                            }
                            if (changed && !quiet && !"android".equals(pkg)) {
                                String statusMsg = state
                                        ? ("已开启 (步频 " + formatToastNumber(minCadenceConfig) + "-"
                                        + formatToastNumber(maxCadenceConfig)
                                        + (altitudeEnabled
                                        ? ("，海拔 " + formatToastNumber(altMinConfig) + "-" + formatToastNumber(altMaxConfig))
                                        : "")
                                        + ")")
                                        : "已关闭";
                                // system_server 里弹 Toast 可能直接把系统进程打崩，只记日志。
                                Toast.makeText(context, "万能接口拦截(一加专版) " + statusMsg, Toast.LENGTH_SHORT).show();
                                XposedBridge.log("JoyrunHook: " + pkg + " 收到广播，万能接口拦截 " + statusMsg);
                            } else if (changed) {
                                XposedBridge.log("JoyrunHook: " + pkg + " 开关变为 " + state);
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

        hookLocationAltitude(lpparam.classLoader);
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
            if (all != null) {
                Object tokenRaw = all.get(ConfigStore.KEY_TOKEN);
                if (tokenRaw instanceof String && !((String) tokenRaw).isEmpty()) {
                    controlToken = (String) tokenRaw;
                }
            }
            // 没有这份键就保持内存状态。进程刚起来时内存是关，所以不会默认注入。
            if (all == null || !all.containsKey(ConfigStore.KEY_ENABLED)) return;

            boolean enabled = xPrefs.getBoolean(ConfigStore.KEY_ENABLED, false);
            float min = xPrefs.getFloat(ConfigStore.KEY_MIN, ConfigStore.DEFAULT_MIN);
            float max = xPrefs.getFloat(ConfigStore.KEY_MAX, ConfigStore.DEFAULT_MAX);
            boolean altitudeKnown = all.containsKey(ConfigStore.KEY_ALT_ENABLED)
                    || all.containsKey(ConfigStore.KEY_ALT_MIN)
                    || all.containsKey(ConfigStore.KEY_ALT_MAX);
            boolean altitudeOn = xPrefs.getBoolean(ConfigStore.KEY_ALT_ENABLED, false);
            float altMin = xPrefs.getFloat(ConfigStore.KEY_ALT_MIN, AltitudeCurve.DEFAULT_MIN);
            float altMax = xPrefs.getFloat(ConfigStore.KEY_ALT_MAX, AltitudeCurve.DEFAULT_MAX);
            boolean turnedOff;
            synchronized (STATE_LOCK) {
                boolean was = userEnabled;
                applyUserStateLocked(enabled, min, max, altitudeOn, altMin, altMax,
                        altitudeKnown, false, System.currentTimeMillis());
                turnedOff = was && !userEnabled;
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

    /**
     * 调用方需要持有 STATE_LOCK。返回值表示用户想要的引擎开关是否变化。
     * explicit 为 false 表示进程刚起来在读配置：这时如果配置是关，直接停，不再补一段冷却步数。
     */
    private static boolean applyUserStateLocked(boolean enabled, float min, float max,
                                                boolean altitudeOn, float altMin, float altMax,
                                                boolean altitudeSpecified, boolean explicit, long nowMs) {
        min = ConfigStore.sanitizeCadence(min, minCadenceConfig);
        max = ConfigStore.sanitizeCadence(max, maxCadenceConfig);
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        minCadenceConfig = min;
        maxCadenceConfig = max;
        cadenceCurve.setRange(min, max);
        if (altitudeSpecified) {
            applyAltitudeLocked(altitudeOn, altMin, altMax, nowMs);
        }

        boolean was = userEnabled;
        if (enabled) {
            CadenceCurve.Mode mode = cadenceCurve.modeAt(nowMs);
            if (mode == CadenceCurve.Mode.RUNNING) {
                pendingCurve = false;
            } else if (!explicit && pendingCurve && pendingCurveStart > 0L) {
                // 进程重启后续上原来的曲线，但 lastUpdateMs 不恢复，死亡空档不计步。
                cadenceCurve.restoreRunning(pendingCurveSeed, min, max, pendingCurveStart);
                pendingCurve = false;
                XposedBridge.log("JoyrunHook: 恢复步频曲线");
            } else {
                pendingCurve = false;
                cadenceCurve.reseed(freshSeed());
                cadenceCurve.setRange(min, max);
                cadenceCurve.start(nowMs);
                // 关掉的那段时间不能补进步数里。
                accumulator.lastUpdateMs = nowMs;
                XposedBridge.log("JoyrunHook: 引擎开启，步频区间 " + min + "-" + max);
            }
            userEnabled = true;
        } else {
            pendingCurve = false;
            CadenceCurve.Mode mode = cadenceCurve.modeAt(nowMs);
            if (explicit && mode == CadenceCurve.Mode.RUNNING) {
                cadenceCurve.beginCooldown(nowMs);
                XposedBridge.log("JoyrunHook: 引擎关闭，步频冷却后停止");
            } else if (!explicit && cadenceCurve.isStarted()) {
                cadenceCurve.stopImmediate();
                XposedBridge.log("JoyrunHook: 配置为关闭，停止累加");
            }
            userEnabled = false;
        }
        isModifyCadence = userEnabled || cadenceCurve.isActive(nowMs);
        return was != enabled;
    }

    /** 调用方需要持有 STATE_LOCK。 */
    private static void applyAltitudeLocked(boolean enabled, float min, float max, long nowMs) {
        float[] range = new float[2];
        if (!AltitudeCurve.normalizeRange(min, max, range)) {
            if (!AltitudeCurve.normalizeRange(altMinConfig, altMaxConfig, range)) {
                range[0] = AltitudeCurve.DEFAULT_MIN;
                range[1] = AltitudeCurve.DEFAULT_MAX;
            }
        }
        altMinConfig = range[0];
        altMaxConfig = range[1];
        altitudeCurve.setRange(range[0], range[1]);
        if (!enabled) {
            altitudeEnabled = false;
            altitudeCurve.stop();
            pendingAlt = false;
            return;
        }
        altitudeEnabled = true;
        if (altitudeCurve.isStarted()) {
            pendingAlt = false;
            return;
        }
        if (pendingAlt && pendingAltStart > 0L) {
            altitudeCurve.restore(pendingAltSeed, range[0], range[1], pendingAltStart);
            pendingAlt = false;
            return;
        }
        altitudeCurve.reseed(freshSeed());
        altitudeCurve.setRange(range[0], range[1]);
        altitudeCurve.start(nowMs);
    }

    private static long freshSeed() {
        long seed = new Random().nextLong();
        return seed == 0L ? 0x5DEECE66DL : seed;
    }

    private static String formatToastNumber(float value) {
        if (value == (float) Math.rint(value)) return String.valueOf((long) value);
        return Float.toString(value);
    }

    private static boolean acceptControlToken(Intent intent) {
        ensureControlTokenLoaded();
        String expected = controlToken;
        if (expected == null || expected.isEmpty()) return true;
        return expected.equals(intent.getStringExtra("TOKEN"));
    }

    private static void ensureControlTokenLoaded() {
        if (controlToken != null && !controlToken.isEmpty()) return;
        try {
            if (xPrefs == null) {
                xPrefs = new XSharedPreferences(MODULE_PACKAGE, ConfigStore.PREF_NAME);
                tryRegisterPrefListener();
            }
            xPrefs.reload();
            String token = xPrefs.getString(ConfigStore.KEY_TOKEN, "");
            if (token != null && !token.isEmpty()) {
                controlToken = token;
            }
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: 读取控制口令失败: " + t.getMessage());
        }
    }

    private static void loadStepState() {
        synchronized (STATE_LOCK) {
            if (stepStateLoaded || appContext == null) return;
            stepStateLoaded = true;
            try {
                SharedPreferences sp = appContext.getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE);
                accumulator.boost = Math.max(0f, sp.getFloat("boost", 0f));
                accumulator.lastReal = sp.getFloat("last_real", -1f);
                if (sp.getBoolean("curve_running", false)) {
                    pendingCurve = true;
                    pendingCurveSeed = sp.getLong("curve_seed", 1L);
                    pendingCurveStart = sp.getLong("curve_start", 0L);
                }
                long altStart = sp.getLong("alt_start", 0L);
                if (altStart > 0L) {
                    pendingAlt = true;
                    pendingAltSeed = sp.getLong("alt_seed", 1L);
                    pendingAltStart = altStart;
                }
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
        boolean curveRunning;
        long curveSeed;
        long curveStart;
        long altSeed;
        long altStart;
        synchronized (STATE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastStepSaveElapsed < 5000) return;
            lastStepSaveElapsed = now;
            boost = accumulator.boost;
            lastReal = accumulator.lastReal;
            curveRunning = cadenceCurve.modeAt(System.currentTimeMillis()) == CadenceCurve.Mode.RUNNING;
            curveSeed = cadenceCurve.seed();
            curveStart = curveRunning ? cadenceCurve.sessionStartMs() : 0L;
            altSeed = altitudeCurve.seed();
            altStart = altitudeCurve.isStarted() ? altitudeCurve.sessionStartMs() : 0L;
        }
        try {
            SharedPreferences.Editor editor = ctx.getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("boost", boost)
                    .putFloat("last_real", lastReal)
                    .putBoolean("curve_running", curveRunning)
                    .putLong("curve_seed", curveSeed)
                    .putLong("curve_start", curveStart)
                    .putLong("alt_seed", altSeed)
                    .putLong("alt_start", altStart);
            // 传感器线程上每次 commit 都会卡磁盘。只有关闭或硬件复位才强制刷盘。
            if (force) editor.commit();
            else editor.apply();
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: 保存步数偏移失败: " + t.getMessage());
        }
    }

    private static void modifyEvent(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) return;
        // 子类 Hook 再调用 super 时，同一次回调会进来两遍，步数会被加两次。
        if (activeEvent.get() == event) return;
        SensorEvent previous = activeEvent.get();
        activeEvent.set(event);
        try {
            reloadConfigFromPrefs(false);

            int type = event.sensor.getType();
            if (type == Sensor.TYPE_STEP_COUNTER || type == 19) {
                modifyStepCounter(event);
                return;
            }
            if (type == Sensor.TYPE_PRESSURE) {
                modifyPressure(event);
                return;
            }

            boolean enabled;
            float yWave = 0f;
            float zWave = 0f;
            synchronized (STATE_LOCK) {
                enabled = userEnabled || cadenceCurve.isActive(System.currentTimeMillis());
                isModifyCadence = enabled;
                if (enabled && type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3 && spoofAccelerometer()) {
                    long sampleMs = eventWallTimeMs(event);
                    yWave = cadenceCurve.waveY(sampleMs);
                    zWave = cadenceCurve.waveZ(sampleMs);
                } else if (type == Sensor.TYPE_ACCELEROMETER) {
                    enabled = false;
                }
            }
            if (!enabled) return;

            if (type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
                // 系统计步服务自己的加速度融合一旦被改掉，可能把已保存的步数记录判定为异常并清掉。
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
        } finally {
            if (previous == null) activeEvent.remove();
            else activeEvent.set(previous);
        }
    }

    /**
     * 不把 timestamp 改成“现在”。灭屏后传感器会一次吐出一批事件，
     * 如果全部盖成同一个时间，步频会变成无穷大，OPPO 健康会丢掉这条传感器流，直到进程重启。
     */
    private static void keepTimestampMonotonic(SensorEvent event) {
        int type = event.sensor.getType();
        long ts = event.timestamp;
        synchronized (lastTimestamps) {
            Long prev = lastTimestamps.get(type);
            if (prev != null && ts <= prev) {
                ts = prev + 1_000_000L;
                // 接近 Long.MAX_VALUE 时再加会溢出成负数，直接保持原值。
                if (ts < 0) return;
                event.timestamp = ts;
            }
            lastTimestamps.put(type, ts);
        }
    }

    private static void modifyPressure(SensorEvent event) {
        if (event.values.length < 1) return;
        Double altitude = spoofedAltitudeOrNull();
        if (altitude == null) return;
        event.values[0] = AltitudeCurve.pressureHpa(altitude.floatValue());
        keepTimestampMonotonic(event);
    }

    /**
     * 灭屏批量回调的 timestamp 是采样时刻，墙钟却挤在一起。
     * 相位按采样时刻取，步数仍按墙钟积分，这样波形连续，也不会把一批事件算成同一步。
     */
    private static long eventWallTimeMs(SensorEvent event) {
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        long ts = event.timestamp;
        if (ts <= 0L || elapsed <= 0L) return wall;
        long delta = elapsed - ts;
        long maxNs = (long) StepAccumulator.MAX_GAP_SEC * 1_000_000_000L;
        if (delta < -2_000_000_000L || delta > maxNs) return wall;
        return wall - delta / 1_000_000L;
    }

    private static Double spoofedAltitudeOrNull() {
        synchronized (STATE_LOCK) {
            // system_server 里改气压会变成全机海拔。健康应用在自己的进程里读。
            if ("android".equals(currentPackage)) return null;
            if (!AltitudeCurve.shouldSpoof(userEnabled, altitudeEnabled)) return null;
            long now = System.currentTimeMillis();
            if (!altitudeCurve.isStarted()) {
                altitudeCurve.reseed(freshSeed());
                altitudeCurve.setRange(altMinConfig, altMaxConfig);
                altitudeCurve.start(now);
            }
            return altitudeCurve.altitudeAt(now);
        }
    }

    private void hookLocationAltitude(ClassLoader loader) {
        if ("android".equals(currentPackage)) return;
        try {
            Class<?> locationClass = XposedHelpers.findClass("android.location.Location", loader);
            XposedHelpers.findAndHookMethod(locationClass, "getAltitude", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Double altitude = spoofedAltitudeOrNull();
                    if (altitude != null) param.setResult(altitude);
                }
            });
            XposedHelpers.findAndHookMethod(locationClass, "hasAltitude", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (spoofedAltitudeOrNull() != null) param.setResult(true);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: Location 海拔读取 Hook 失败: " + t.getMessage());
        }

        try {
            Class<?> manager = XposedHelpers.findClass("android.location.LocationManager", loader);
            for (Method method : manager.getDeclaredMethods()) {
                String name = method.getName();
                if (!"requestLocationUpdates".equals(name) && !"requestSingleUpdate".equals(name)) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) return;
                        for (Object arg : param.args) {
                            if (arg instanceof LocationListener) {
                                ensureLocationListenerHooked((LocationListener) arg);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("JoyrunHook: LocationManager Hook 失败: " + t.getMessage());
        }
    }

    private void ensureLocationListenerHooked(LocationListener listener) {
        Class<?> type = listener.getClass();
        if (!hookedLocationClasses.add(type)) return;
        boolean hooked = false;
        try {
            XposedHelpers.findAndHookMethod(type, "onLocationChanged", Location.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Location) {
                        stampLocation((Location) param.args[0]);
                    }
                }
            });
            hooked = true;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(type, "onLocationChanged", List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof List)) return;
                    for (Object item : (List<?>) param.args[0]) {
                        if (item instanceof Location) stampLocation((Location) item);
                    }
                }
            });
            hooked = true;
        } catch (Throwable ignored) {
        }
        if (!hooked) hookedLocationClasses.remove(type);
    }

    private static void stampLocation(Location location) {
        if (location == null) return;
        Double altitude = spoofedAltitudeOrNull();
        if (altitude == null) return;
        try {
            location.setAltitude(altitude);
        } catch (Throwable ignored) {
        }
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
        boolean finished;
        synchronized (STATE_LOCK) {
            boolean activeNow = userEnabled || cadenceCurve.isActive(now);
            // 关着，并且没有需要托住的历史偏移：原样放行，不做任何改写。
            if (!activeNow && accumulator.boost <= 0f) return;
            double steps = cadenceCurve.creditedSteps(accumulator.lastUpdateMs, now, StepAccumulator.MAX_GAP_SEC);
            boolean count = accumulator.lastUpdateMs > 0L && cadenceCurve.isActive(accumulator.lastUpdateMs);
            reset = accumulator.advanceIntegrated(real, now, count, steps);
            hold = accumulator.shouldRewrite();
            reported = accumulator.reported(real);
            finished = isModifyCadence && !activeNow;
            isModifyCadence = activeNow;
        }
        if (finished) {
            saveStepState(true);
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
