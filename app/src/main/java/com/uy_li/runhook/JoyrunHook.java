package com.uy_li.runhook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class JoyrunHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TAG = "RunHook";
    private static final String MODULE_PACKAGE = "com.uy_li.runhook";
    private static final String STEP_PREFS = "runhook_step_state";

    static final Object STATE_LOCK = EngineLock.STATE;

    private static float minCadenceConfig = ConfigStore.DEFAULT_MIN;
    private static float maxCadenceConfig = ConfigStore.DEFAULT_MAX;
    private static boolean userEnabled;
    private static boolean altitudeEnabled;
    private static float altMinConfig = AltitudeCurve.DEFAULT_MIN;
    private static float altMaxConfig = AltitudeCurve.DEFAULT_MAX;
    static final CadenceCurve cadenceCurve = new CadenceCurve(1L);
    private static final AltitudeCurve altitudeCurve = new AltitudeCurve(1L);
    static final StepAccumulator accumulator = new StepAccumulator();
    static final StepEventScheduler stepScheduler = new StepEventScheduler(cadenceCurve, accumulator);
    static MotionWaveform motion = new MotionWaveform(1L);
    static BarometerModel barometer = new BarometerModel(1L);
    static String modulePath;

    private static boolean pendingCurve;
    private static long pendingCurveSeed = 1L;
    private static long pendingCurveStart;
    private static boolean pendingAlt;
    private static long pendingAltSeed = 1L;
    private static long pendingAltStart;

    static Context appContext;
    private static boolean stepStateLoaded;
    private static long lastStepSaveElapsed;

    private static XSharedPreferences xPrefs;
    private static boolean prefListenerRegistered;
    private static boolean reloadingPrefs;
    private static long lastPrefReloadElapsed;

    private static Handler pollHandler;
    private static final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            long delay = 400L;
            try {
                reloadConfigFromPrefs(false);
                delay = SensorPipeline.tick();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": tick 失败: " + t.getMessage());
            }
            Handler handler = pollHandler;
            if (handler != null) handler.postDelayed(this, delay);
        }
    };

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!ConfigStore.isTargetPackage(pkg)) return;
        // 系统进程里的传感器数据走 native SensorService，Java 钩子既看不到应用的数据，也容易碰坏系统计步。
        if ("android".equals(pkg)) {
            XposedBridge.log(TAG + ": 跳过 android 进程");
            return;
        }

        XposedBridge.log(TAG + ": loaded for " + pkg + " (engine default OFF)");
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    appContext = app;
                    loadStepState();
                    if (accumulator.lastReal >= 0f) {
                        stepScheduler.noteHardware(accumulator.lastReal, System.currentTimeMillis());
                    }
                    reloadConfigFromPrefs(true);
                    NativeBridge.ensure(app);
                    startPolling();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application 钩子失败: " + t.getMessage());
        }

        SensorPipeline.install(lpparam.classLoader);
        LocationSpoof.install(lpparam.classLoader);
        StealthHook.install(lpparam);
    }

    private static void startPolling() {
        if (pollHandler != null) return;
        Looper looper = Looper.getMainLooper();
        if (looper == null) return;
        pollHandler = new Handler(looper);
        pollHandler.postDelayed(pollTask, 200L);
    }

    private static void reloadConfigFromPrefs(boolean force) {
        if (reloadingPrefs) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPrefReloadElapsed < 300) return;

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
            boolean started;
            synchronized (STATE_LOCK) {
                boolean was = userEnabled;
                applyUserStateLocked(enabled, min, max, altitudeOn, altMin, altMax, altitudeKnown,
                        System.currentTimeMillis());
                turnedOff = was && !userEnabled;
                started = !was && userEnabled;
            }
            if (started) SensorPipeline.onEngineStarted();
            if (turnedOff) saveStepState(true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取配置失败，保持当前开关: " + t.getMessage());
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
        }
    }

    /**
     * 配置文件是唯一开关来源。从开到关走冷却，冷却过程中后续轮询不会把曲线立刻掐断。
     * 调用方需要持有 STATE_LOCK。
     */
    private static void applyUserStateLocked(boolean enabled, float min, float max,
                                             boolean altitudeOn, float altMin, float altMax,
                                             boolean altitudeSpecified, long nowMs) {
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
            } else if (!was && pendingCurve && pendingCurveStart > 0L) {
                cadenceCurve.restoreRunning(pendingCurveSeed, min, max, pendingCurveStart);
                pendingCurve = false;
                motion = new MotionWaveform(cadenceCurve.seed());
                accumulator.lastUpdateMs = nowMs;
                stepScheduler.anchor(nowMs);
                XposedBridge.log(TAG + ": 恢复步频曲线");
            } else {
                beginRunningLocked(min, max, nowMs);
            }
            userEnabled = true;
        } else {
            pendingCurve = false;
            CadenceCurve.Mode mode = cadenceCurve.modeAt(nowMs);
            if (was && mode == CadenceCurve.Mode.RUNNING) {
                cadenceCurve.beginCooldown(nowMs);
                XposedBridge.log(TAG + ": 引擎关闭，步频冷却后停止");
            } else if (!was && mode == CadenceCurve.Mode.RUNNING) {
                cadenceCurve.stopImmediate();
                XposedBridge.log(TAG + ": 配置为关闭，停止累加");
            }
            userEnabled = false;
        }
    }

    private static void beginRunningLocked(float min, float max, long nowMs) {
        pendingCurve = false;
        cadenceCurve.reseed(freshSeed());
        cadenceCurve.setRange(min, max);
        cadenceCurve.start(nowMs);
        accumulator.lastUpdateMs = nowMs;
        motion = new MotionWaveform(cadenceCurve.seed());
        stepScheduler.anchor(nowMs);
        XposedBridge.log(TAG + ": 引擎开启，步频区间 " + min + "-" + max);
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
            barometer = new BarometerModel(altitudeCurve.seed());
            return;
        }
        altitudeCurve.reseed(freshSeed());
        altitudeCurve.setRange(range[0], range[1]);
        altitudeCurve.start(nowMs);
        barometer = new BarometerModel(altitudeCurve.seed());
    }

    static boolean motionActive(long nowMs) {
        synchronized (STATE_LOCK) {
            return cadenceCurve.isActive(nowMs);
        }
    }

    static boolean runningNow() {
        synchronized (STATE_LOCK) {
            return cadenceCurve.modeAt(System.currentTimeMillis()) == CadenceCurve.Mode.RUNNING;
        }
    }

    /** 调用方需要持有 STATE_LOCK。 */
    static boolean altitudeActiveLocked() {
        return AltitudeCurve.shouldSpoof(userEnabled, altitudeEnabled);
    }

    /** 调用方需要持有 STATE_LOCK。 */
    static float pressureLocked(long nowMs) {
        if (!altitudeActiveLocked()) return Float.NaN;
        ensureAltitudeStartedLocked(nowMs);
        return barometer.pressureHpa(altitudeCurve.altitudeAt(nowMs), nowMs);
    }

    static float pressureAt(long nowMs) {
        synchronized (STATE_LOCK) {
            return pressureLocked(nowMs);
        }
    }

    static Double gpsAltitudeOrNull() {
        synchronized (STATE_LOCK) {
            if (!altitudeActiveLocked()) return null;
            long now = System.currentTimeMillis();
            ensureAltitudeStartedLocked(now);
            return barometer.gpsAltitudeMeters(altitudeCurve.altitudeAt(now), now);
        }
    }

    static Double mslAltitudeOrNull() {
        synchronized (STATE_LOCK) {
            if (!altitudeActiveLocked()) return null;
            long now = System.currentTimeMillis();
            ensureAltitudeStartedLocked(now);
            return barometer.mslAltitudeMeters(altitudeCurve.altitudeAt(now), now);
        }
    }

    static float verticalAccuracyMeters() {
        synchronized (STATE_LOCK) {
            if (!altitudeActiveLocked()) return Float.NaN;
            return barometer.verticalAccuracyMeters(System.currentTimeMillis());
        }
    }

    private static void ensureAltitudeStartedLocked(long nowMs) {
        if (altitudeCurve.isStarted()) return;
        altitudeCurve.reseed(freshSeed());
        altitudeCurve.setRange(altMinConfig, altMaxConfig);
        altitudeCurve.start(nowMs);
        barometer = new BarometerModel(altitudeCurve.seed());
    }

    private static long freshSeed() {
        long seed = new Random().nextLong();
        return seed == 0L ? 0x5DEECE66DL : seed;
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
                XposedBridge.log(TAG + ": 读取步数偏移失败: " + t.getMessage());
            }
        }
    }

    static void saveStepState(boolean force) {
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
            if (force) editor.commit();
            else editor.apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 保存步数偏移失败: " + t.getMessage());
        }
    }
}
