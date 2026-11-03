package com.uy_li.runhook;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.SparseArray;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 优先改 SystemSensorManager$SensorEventQueue.dispatchSensorEvent。
 * 引擎开着时由调度器按步频曲线补 TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER，
 * 手机静止或灭屏也继续涨。监听器 onSensorChanged 只在这条底层钩子装不上时启用。
 * native 钩子生效后，这里不再改真实事件的数值，避免和 read() 改两次。
 */
final class SensorPipeline {

    static final String TAG = "RunHook";
    private static final int TYPE_ACCEL_UNCAL = 35;
    private static final Object REG = new Object();
    private static final ThreadLocal<Boolean> SYNTHETIC = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<SensorEvent> ACTIVE = new ThreadLocal<>();
    private static final Map<Integer, Long> LAST_TS = new HashMap<>();

    private static final Map<Object, QueueInfo> QUEUES = new IdentityHashMap<>();
    private static final Map<Key, Reg> REGS = new HashMap<>();
    private static final Map<TriggerEventListener, TriggerInfo> TRIGGERS = new IdentityHashMap<>();
    private static final Map<Looper, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> HOOKED_LISTENERS = ConcurrentHashMap.newKeySet();
    private static final Map<SensorEventListener, SensorEventListener> WRAPS = new ConcurrentHashMap<>();

    static boolean dispatchInstalled;

    private SensorPipeline() {
    }

    static void install(ClassLoader loader) {
        try {
            dispatchInstalled = hookDispatch(loader);
        } catch (Throwable t) {
            dispatchInstalled = false;
            log("dispatch 安装失败: " + t.getMessage());
        }
        if (dispatchInstalled) {
            log("已挂钩 SensorEventQueue.dispatchSensorEvent");
        } else {
            log("dispatch 不可用，回退监听器");
            try {
                installFallback(loader);
            } catch (Throwable t) {
                log("监听器回退失败: " + t.getMessage());
            }
        }
        try {
            hookTriggers(loader);
        } catch (Throwable t) {
            log("显著运动钩子失败: " + t.getMessage());
        }
    }

    static void onEngineStarted() {
        ArrayList<TriggerInfo> pending = new ArrayList<>();
        synchronized (REG) {
            for (TriggerInfo info : TRIGGERS.values()) {
                if (info.listener == null || info.sensor == null || info.fired) continue;
                if (info.sensor.getType() != Sensor.TYPE_SIGNIFICANT_MOTION) continue;
                info.fired = true;
                pending.add(info);
            }
        }
        for (TriggerInfo info : pending) postTrigger(info);
    }

    /** @return 下一次 tick 的延迟，引擎活动时更密。 */
    static long tick() {
        Reg[] stepRegs;
        int sampling;
        int latency;
        boolean ownsSteps;
        synchronized (REG) {
            stepRegs = stepRegsLocked();
            sampling = Integer.MAX_VALUE;
            latency = Integer.MAX_VALUE;
            for (Reg reg : stepRegs) {
                if (reg.samplingUs < sampling) sampling = reg.samplingUs;
                if (reg.maxLatencyUs < latency) latency = reg.maxLatencyUs;
            }
            if (sampling == Integer.MAX_VALUE) sampling = 0;
            if (latency == Integer.MAX_VALUE) latency = 0;
            ownsSteps = stepRegs.length > 0;
        }

        long nowMs = System.currentTimeMillis();
        long nowNs = SystemClock.elapsedRealtimeNanos();
        boolean screen = screenOn();
        List<StepEventScheduler.Planned> events;
        float reported;
        double phase;
        float hz;
        float pressure;
        boolean active;
        boolean spoofPressure;
        MotionWaveform wave;
        synchronized (EngineLock.STATE) {
            if (NativeBridge.isActive()) {
                float hardware = NativeBridge.lastHardware();
                if (!Float.isNaN(hardware)) {
                    JoyrunHook.stepScheduler.noteHardware(hardware, nowMs);
                }
            }
            events = JoyrunHook.stepScheduler.poll(nowMs, nowNs, screen, sampling, latency);
            reported = JoyrunHook.stepScheduler.reported();
            active = JoyrunHook.cadenceCurve.isActive(nowMs);
            phase = JoyrunHook.cadenceCurve.phaseAt(nowMs);
            hz = (float) (JoyrunHook.cadenceCurve.cadenceAt(nowMs) / 60.0);
            spoofPressure = JoyrunHook.altitudeActiveLocked();
            pressure = spoofPressure ? JoyrunHook.pressureLocked(nowMs) : 0f;
            wave = JoyrunHook.motion;
        }
        if (NativeBridge.isActive() && wave != null) {
            NativeBridge.publish(active, spoofPressure, ownsSteps, reported, hz, phase, nowNs,
                    pressure, wave.tiltRollRad, wave.tiltPitchRad, wave.noiseSeed);
        }
        deliver(stepRegs, events);
        JoyrunHook.saveStepState(JoyrunHook.stepScheduler.consumeReset());
        return active ? 80L : 400L;
    }

    private static boolean hookDispatch(ClassLoader loader) {
        Class<?> queue = find(loader, "android.hardware.SystemSensorManager$SensorEventQueue");
        Class<?> base = find(loader, "android.hardware.SystemSensorManager$BaseEventQueue");
        if (queue == null) return false;
        XposedBridge.hookAllConstructors(queue, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    rememberQueue(param.thisObject, param.args);
                } catch (Throwable t) {
                    log("queue ctor: " + t.getMessage());
                }
            }
        });
        if (base != null) {
            XposedBridge.hookAllMethods(base, "addSensor", ADD);
            XposedBridge.hookAllMethods(base, "removeSensor", REMOVE);
            XposedBridge.hookAllMethods(base, "dispose", DISPOSE);
        }
        Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(queue, "dispatchSensorEvent", DISPATCH);
        if (hooked == null || hooked.isEmpty()) {
            if (base == null) return false;
            hooked = XposedBridge.hookAllMethods(base, "dispatchSensorEvent", DISPATCH);
        }
        return hooked != null && !hooked.isEmpty();
    }

    private static final XC_MethodHook DISPATCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                int handle = -1;
                float[] values = null;
                long timestamp = 0L;
                int tsIndex = -1;
                if (param.args[0] instanceof Integer && param.args[1] instanceof float[]) {
                    handle = (Integer) param.args[0];
                    values = (float[]) param.args[1];
                    if (param.args.length > 3 && param.args[3] instanceof Long) {
                        timestamp = (Long) param.args[3];
                        tsIndex = 3;
                    }
                } else {
                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (arg instanceof float[]) values = (float[]) arg;
                        else if (arg instanceof Integer && handle < 0) handle = (Integer) arg;
                        else if (arg instanceof Long) {
                            timestamp = (Long) arg;
                            tsIndex = i;
                        }
                    }
                }
                if (values == null) return;
                int type = typeOf(param.thisObject, handle);
                long updated = rewrite(type, values, timestamp);
                if (tsIndex >= 0 && updated != timestamp) param.args[tsIndex] = updated;
            } catch (Throwable t) {
                log("dispatch: " + t.getMessage());
            }
        }
    };

    private static final XC_MethodHook ADD = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (result instanceof Boolean && !((Boolean) result)) return;
                Sensor sensor = null;
                int[] ints = new int[3];
                int count = 0;
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (arg instanceof Sensor) sensor = (Sensor) arg;
                        else if (arg instanceof Integer && count < ints.length) ints[count++] = (Integer) arg;
                    }
                }
                if (sensor == null) return;
                QueueInfo info;
                synchronized (REG) {
                    info = QUEUES.get(param.thisObject);
                    Reg reg = new Reg(param.thisObject, info, sensor,
                            count > 0 ? ints[0] : 0,
                            count > 1 ? ints[1] : 0);
                    REGS.put(new Key(param.thisObject, sensorHandle(sensor)), reg);
                }
            } catch (Throwable t) {
                log("addSensor: " + t.getMessage());
            }
        }
    };

    private static final XC_MethodHook REMOVE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Sensor sensor = null;
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (arg instanceof Sensor) sensor = (Sensor) arg;
                    }
                }
                synchronized (REG) {
                    if (sensor == null) {
                        dropQueue(param.thisObject);
                    } else {
                        Reg reg = REGS.remove(new Key(param.thisObject, sensorHandle(sensor)));
                        if (reg != null) reg.dead = true;
                    }
                }
            } catch (Throwable t) {
                log("removeSensor: " + t.getMessage());
            }
        }
    };

    private static final XC_MethodHook DISPOSE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                synchronized (REG) {
                    dropQueue(param.thisObject);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static void rememberQueue(Object queue, Object[] args) {
        SensorEventListener listener = null;
        Looper looper = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof SensorEventListener) listener = (SensorEventListener) arg;
                else if (arg instanceof Looper) looper = (Looper) arg;
            }
        }
        synchronized (REG) {
            QUEUES.put(queue, new QueueInfo(listener, looper));
        }
    }

    private static void dropQueue(Object queue) {
        QUEUES.remove(queue);
        ArrayList<Key> dead = new ArrayList<>();
        for (Map.Entry<Key, Reg> entry : REGS.entrySet()) {
            if (entry.getKey().queue == queue) {
                entry.getValue().dead = true;
                dead.add(entry.getKey());
            }
        }
        for (Key key : dead) REGS.remove(key);
    }

    /** getHandle() 是 hidden API，公开 stub 里没有。 */
    private static int sensorHandle(Sensor sensor) {
        try {
            Method method = Sensor.class.getMethod("getHandle");
            Object value = method.invoke(sensor);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
        }
        return System.identityHashCode(sensor);
    }

    private static int typeOf(Object queue, int handle) {
        synchronized (REG) {
            Reg reg = REGS.get(new Key(queue, handle));
            if (reg != null) return reg.type;
        }
        try {
            Object manager = XposedHelpers.getObjectField(queue, "mManager");
            Object map = XposedHelpers.getObjectField(manager, "mHandleToSensor");
            if (map instanceof SparseArray) {
                Object sensor = ((SparseArray<?>) map).get(handle);
                if (sensor instanceof Sensor) return ((Sensor) sensor).getType();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static long rewrite(int type, float[] values, long timestampNs) {
        if (values == null || type < 0 || SYNTHETIC.get()) return timestampNs;
        try {
            if (NativeBridge.isActive()) {
                if (type == Sensor.TYPE_STEP_COUNTER) {
                    float hardware = NativeBridge.lastHardware();
                    if (!Float.isNaN(hardware)) {
                        JoyrunHook.stepScheduler.noteHardware(hardware, System.currentTimeMillis());
                    }
                }
                return timestampNs;
            }
            long wall = wallTime(timestampNs);
            if (type == Sensor.TYPE_STEP_COUNTER && values.length > 0) {
                float reported = JoyrunHook.stepScheduler.onRealCounter(
                        values[0], timestampNs, System.currentTimeMillis());
                values[0] = reported;
                JoyrunHook.saveStepState(JoyrunHook.stepScheduler.consumeReset());
                return JoyrunHook.stepScheduler.monotonicTimestamp(timestampNs);
            }
            if (type == Sensor.TYPE_STEP_DETECTOR && values.length > 0) {
                values[0] = 1f;
                return JoyrunHook.stepScheduler.monotonicTimestamp(timestampNs);
            }
            if (type == Sensor.TYPE_PRESSURE && values.length > 0) {
                float pressure = JoyrunHook.pressureAt(wall);
                if (Float.isNaN(pressure)) return timestampNs;
                values[0] = pressure;
                return monotonicType(type, timestampNs);
            }
            if (!JoyrunHook.motionActive(wall) && !JoyrunHook.motionActive(System.currentTimeMillis())) {
                return timestampNs;
            }
            MotionWaveform wave;
            double phase;
            double hz;
            synchronized (EngineLock.STATE) {
                long sample = JoyrunHook.cadenceCurve.isActive(wall) ? wall : System.currentTimeMillis();
                if (!JoyrunHook.cadenceCurve.isActive(sample)) return timestampNs;
                phase = JoyrunHook.cadenceCurve.phaseAt(sample);
                hz = JoyrunHook.cadenceCurve.cadenceAt(sample) / 60.0;
                wave = JoyrunHook.motion;
            }
            if (wave == null) return timestampNs;
            long sampleNs = timestampNs > 0L ? timestampNs : SystemClock.elapsedRealtimeNanos();
            if (applySample(type, values, wave.sample(phase, hz, sampleNs))) {
                return monotonicType(type, timestampNs);
            }
        } catch (Throwable t) {
            log("rewrite: " + t.getMessage());
        }
        return timestampNs;
    }

    private static boolean applySample(int type, float[] values, MotionWaveform.Sample sample) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                return write3(values, sample.ax, sample.ay, sample.az);
            case TYPE_ACCEL_UNCAL:
                if (!write3(values, sample.ax + sample.bax, sample.ay + sample.bay, sample.az + sample.baz)) {
                    return false;
                }
                if (values.length >= 6) {
                    values[3] = sample.bax;
                    values[4] = sample.bay;
                    values[5] = sample.baz;
                }
                return true;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return write3(values, sample.lx, sample.ly, sample.lz);
            case Sensor.TYPE_GRAVITY:
                return write3(values, sample.gx, sample.gy, sample.gz);
            case Sensor.TYPE_GYROSCOPE:
                return write3(values, sample.wx, sample.wy, sample.wz);
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                if (!write3(values, sample.wx + sample.bwx, sample.wy + sample.bwy, sample.wz + sample.bwz)) {
                    return false;
                }
                if (values.length >= 6) {
                    values[3] = sample.bwx;
                    values[4] = sample.bwy;
                    values[5] = sample.bwz;
                }
                return true;
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                if (values.length < 3) return false;
                values[0] = sample.qx;
                values[1] = sample.qy;
                values[2] = sample.qz;
                if (values.length > 3) values[3] = sample.qw;
                if (values.length > 4) values[4] = 0.05f;
                return true;
            case Sensor.TYPE_MAGNETIC_FIELD:
                return write3(values, sample.mx, sample.my, sample.mz);
            default:
                return false;
        }
    }

    private static boolean write3(float[] values, float x, float y, float z) {
        if (values.length < 3) return false;
        values[0] = x;
        values[1] = y;
        values[2] = z;
        return true;
    }

    private static long monotonicType(int type, long timestampNs) {
        synchronized (LAST_TS) {
            Long prev = LAST_TS.get(type);
            if (prev != null && timestampNs <= prev) {
                long next = prev + 1_000L;
                if (next > 0L) timestampNs = next;
            }
            LAST_TS.put(type, timestampNs);
            return timestampNs;
        }
    }

    private static long wallTime(long timestampNs) {
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        if (timestampNs <= 0L || elapsed <= 0L) return wall;
        long delta = elapsed - timestampNs;
        long maxNs = (long) StepAccumulator.MAX_GAP_SEC * 1_000_000_000L;
        if (delta < -2_000_000_000L || delta > maxNs) return wall;
        return wall - delta / 1_000_000L;
    }

    private static void deliver(Reg[] regs, List<StepEventScheduler.Planned> events) {
        if (regs == null || events == null || events.isEmpty()) return;
        for (Reg reg : regs) {
            if (reg == null || reg.dead || reg.listener == null || reg.sensor == null) continue;
            ArrayList<StepEventScheduler.Planned> mine = new ArrayList<>();
            for (StepEventScheduler.Planned planned : events) {
                if (reg.type == Sensor.TYPE_STEP_DETECTOR
                        && planned.kind == StepEventScheduler.Kind.DETECTOR) {
                    mine.add(planned);
                } else if (reg.type == Sensor.TYPE_STEP_COUNTER
                        && planned.kind == StepEventScheduler.Kind.COUNTER) {
                    mine.add(planned);
                }
            }
            if (mine.isEmpty()) continue;
            Handler handler = handlerFor(reg.looper);
            if (handler == null) continue;
            SensorEventListener listener = reg.listener;
            Sensor sensor = reg.sensor;
            handler.post(() -> emit(listener, sensor, mine));
        }
    }

    private static void emit(SensorEventListener listener, Sensor sensor, List<StepEventScheduler.Planned> events) {
        SYNTHETIC.set(Boolean.TRUE);
        try {
            for (StepEventScheduler.Planned planned : events) {
                SensorEvent event = SensorEventFactory.create(1);
                if (event == null) return;
                event.sensor = sensor;
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
                event.timestamp = planned.timestampNs;
                event.values[0] = planned.value;
                listener.onSensorChanged(event);
            }
        } catch (Throwable t) {
            log("投递计步失败: " + t.getMessage());
        } finally {
            SYNTHETIC.set(Boolean.FALSE);
        }
    }

    private static Reg[] stepRegsLocked() {
        ArrayList<Reg> list = new ArrayList<>();
        for (Reg reg : REGS.values()) {
            if (!reg.dead && reg.step) list.add(reg);
        }
        return list.toArray(new Reg[0]);
    }

    private static void hookTriggers(ClassLoader loader) {
        Class<?> trigger = find(loader, "android.hardware.SystemSensorManager$TriggerEventQueue");
        if (trigger != null) {
            XposedBridge.hookAllConstructors(trigger, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        rememberTrigger(param.thisObject, param.args);
                    } catch (Throwable t) {
                        log("trigger ctor: " + t.getMessage());
                    }
                }
            });
        }
        Class<?> manager = find(loader, "android.hardware.SystemSensorManager");
        if (manager == null) return;
        XC_MethodHook request = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    onTriggerRequest(param);
                } catch (Throwable t) {
                    log("requestTrigger: " + t.getMessage());
                }
            }
        };
        XC_MethodHook cancel = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg instanceof TriggerEventListener) {
                            synchronized (REG) {
                                TRIGGERS.remove(arg);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        for (String name : new String[]{"requestTriggerSensor", "requestTriggerSensorImpl"}) {
            try {
                XposedBridge.hookAllMethods(manager, name, request);
            } catch (Throwable ignored) {
            }
        }
        for (String name : new String[]{"cancelTriggerSensor", "cancelTriggerSensorImpl"}) {
            try {
                XposedBridge.hookAllMethods(manager, name, cancel);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void rememberTrigger(Object queue, Object[] args) {
        TriggerEventListener listener = null;
        Looper looper = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof TriggerEventListener) listener = (TriggerEventListener) arg;
                else if (arg instanceof Looper) looper = (Looper) arg;
            }
        }
        if (listener == null) return;
        synchronized (REG) {
            TriggerInfo info = TRIGGERS.get(listener);
            if (info == null) {
                info = new TriggerInfo();
                TRIGGERS.put(listener, info);
            }
            info.queue = queue;
            info.listener = listener;
            info.looper = looper;
        }
    }

    private static void onTriggerRequest(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (result instanceof Boolean && !((Boolean) result)) return;
        TriggerEventListener listener = null;
        Sensor sensor = null;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (arg instanceof TriggerEventListener) listener = (TriggerEventListener) arg;
                else if (arg instanceof Sensor) sensor = (Sensor) arg;
            }
        }
        if (listener == null || sensor == null) return;
        if (sensor.getType() != Sensor.TYPE_SIGNIFICANT_MOTION) return;
        TriggerInfo fire = null;
        synchronized (REG) {
            TriggerInfo info = TRIGGERS.get(listener);
            if (info == null) {
                info = new TriggerInfo();
                info.listener = listener;
                TRIGGERS.put(listener, info);
            }
            info.sensor = sensor;
            info.fired = false;
            if (JoyrunHook.runningNow()) {
                info.fired = true;
                fire = info;
            }
        }
        if (fire != null) postTrigger(fire);
    }

    private static void postTrigger(TriggerInfo info) {
        Handler handler = handlerFor(info.looper);
        if (handler == null || info.listener == null) return;
        handler.post(() -> {
            try {
                TriggerEvent event = SensorEventFactory.createTrigger(1);
                if (event == null) return;
                event.sensor = info.sensor;
                event.timestamp = SystemClock.elapsedRealtimeNanos();
                if (event.values.length > 0) event.values[0] = 1f;
                info.listener.onTrigger(event);
                log("已触发 TYPE_SIGNIFICANT_MOTION");
            } catch (Throwable t) {
                log("显著运动回调失败: " + t.getMessage());
            }
        });
    }

    private static void installFallback(ClassLoader loader) {
        hookManagerListeners(SensorManager.class);
        Class<?> system = find(loader, "android.hardware.SystemSensorManager");
        if (system != null) hookManagerListeners(system);
        try {
            Class<?> oplus = XposedHelpers.findClass("com.oplus.sensor.OplusSensorManager", loader);
            hookManagerListeners(oplus);
        } catch (Throwable ignored) {
        }
    }

    private static void hookManagerListeners(Class<?> manager) {
        if (manager == null) return;
        for (Method method : manager.getDeclaredMethods()) {
            String name = method.getName();
            if (!"registerListener".equals(name) && !"unregisterListener".equals(name)) continue;
            boolean unregister = "unregisterListener".equals(name);
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            rewriteListener(param, unregister);
                        } catch (Throwable t) {
                            log("listener arg: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                log("hook " + name + ": " + t.getMessage());
            }
        }
    }

    private static void rewriteListener(XC_MethodHook.MethodHookParam param, boolean unregister) {
        Object[] args = param.args;
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof SensorEventListener)) continue;
            SensorEventListener listener = (SensorEventListener) args[i];
            if (listener instanceof InjectingListener) return;
            if (!unregister) {
                if (ensureListenerHooked(listener)) return;
                SensorEventListener wrapped = WRAPS.get(listener);
                if (wrapped == null) {
                    wrapped = new InjectingListener(listener);
                    WRAPS.put(listener, wrapped);
                }
                args[i] = wrapped;
                return;
            }
            boolean partial = false;
            for (Object arg : args) {
                if (arg instanceof Sensor) partial = true;
            }
            SensorEventListener wrapped = partial ? WRAPS.get(listener) : WRAPS.remove(listener);
            if (wrapped != null) args[i] = wrapped;
            return;
        }
    }

    private static boolean ensureListenerHooked(SensorEventListener listener) {
        Class<?> declaring = findDeclaring(listener.getClass());
        if (declaring == null) return false;
        if (!HOOKED_LISTENERS.add(declaring)) return true;
        try {
            XposedHelpers.findAndHookMethod(declaring, "onSensorChanged", SensorEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof SensorEvent)) return;
                    SensorEvent event = (SensorEvent) param.args[0];
                    if (ACTIVE.get() == event) return;
                    SensorEvent previous = ACTIVE.get();
                    ACTIVE.set(event);
                    try {
                        if (event.sensor == null || event.values == null) return;
                        long ts = rewrite(event.sensor.getType(), event.values, event.timestamp);
                        if (ts != event.timestamp) event.timestamp = ts;
                    } finally {
                        if (previous == null) ACTIVE.remove();
                        else ACTIVE.set(previous);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            HOOKED_LISTENERS.remove(declaring);
            log("onSensorChanged 失败 " + declaring.getName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Class<?> findDeclaring(Class<?> type) {
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

    private static boolean screenOn() {
        try {
            android.content.Context context = JoyrunHook.appContext;
            if (context == null) return true;
            PowerManager pm = (PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Handler handlerFor(Looper looper) {
        Looper use = looper != null ? looper : Looper.getMainLooper();
        if (use == null) return null;
        Handler existing = HANDLERS.get(use);
        if (existing != null) return existing;
        try {
            Handler created = new Handler(use);
            HANDLERS.put(use, created);
            return created;
        } catch (Throwable t) {
            log("Handler: " + t.getMessage());
            return null;
        }
    }

    private static Class<?> find(ClassLoader loader, String name) {
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.findClass(name, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }

    private static final class Key {
        final Object queue;
        final int handle;

        Key(Object queue, int handle) {
            this.queue = queue;
            this.handle = handle;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return handle == key.handle && queue == key.queue;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(queue) * 31 + handle;
        }
    }

    private static final class QueueInfo {
        final SensorEventListener listener;
        final Looper looper;

        QueueInfo(SensorEventListener listener, Looper looper) {
            this.listener = listener;
            this.looper = looper;
        }
    }

    private static final class Reg {
        final SensorEventListener listener;
        final Sensor sensor;
        final int type;
        final Looper looper;
        final int samplingUs;
        final int maxLatencyUs;
        final boolean step;
        volatile boolean dead;

        Reg(Object queue, QueueInfo info, Sensor sensor, int samplingUs, int maxLatencyUs) {
            this.listener = info != null ? info.listener : null;
            this.sensor = sensor;
            this.type = sensor.getType();
            this.looper = info != null ? info.looper : null;
            this.samplingUs = samplingUs;
            this.maxLatencyUs = maxLatencyUs;
            this.step = type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR;
            if (queue == null) dead = true;
        }
    }

    private static final class TriggerInfo {
        Object queue;
        TriggerEventListener listener;
        Sensor sensor;
        Looper looper;
        boolean fired;
    }

    private static final class InjectingListener implements SensorEventListener {
        final SensorEventListener delegate;

        InjectingListener(SensorEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event != null && event.sensor != null && event.values != null && !SYNTHETIC.get()) {
                long ts = rewrite(event.sensor.getType(), event.values, event.timestamp);
                if (ts != event.timestamp) event.timestamp = ts;
            }
            delegate.onSensorChanged(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            delegate.onAccuracyChanged(sensor, accuracy);
        }
    }
}
