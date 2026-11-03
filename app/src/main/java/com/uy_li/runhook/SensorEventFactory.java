package com.uy_li.runhook;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.TriggerEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * SensorEvent / TriggerEvent 的构造器是 hidden API。API 24–35 上依次尝试
 * (int)、(float[])、带传感器参数的构造器，最后用 Unsafe.allocateInstance。
 */
final class SensorEventFactory {

    private static Object unsafe;
    private static boolean logged;

    private SensorEventFactory() {
    }

    static SensorEvent create(int size) {
        int n = Math.max(1, size);
        SensorEvent event = construct(SensorEvent.class, n, true);
        if (event == null) {
            logOnce("SensorEvent");
            return null;
        }
        if (event.values == null || event.values.length < n) {
            forceValues(event, n);
        }
        return event.values == null ? null : event;
    }

    static TriggerEvent createTrigger(int size) {
        int n = Math.max(1, size);
        TriggerEvent event = construct(TriggerEvent.class, n, false);
        if (event == null) {
            logOnce("TriggerEvent");
            return null;
        }
        if (event.values == null || event.values.length < n) {
            forceValues(event, n);
        }
        return event.values == null ? null : event;
    }

    private static <T> T construct(Class<T> type, int size, boolean withSensorCtor) {
        T created = newInstance(type, new Class<?>[]{int.class}, new Object[]{size});
        if (created != null) return created;
        created = newInstance(type, new Class<?>[]{float[].class}, new Object[]{new float[size]});
        if (created != null) return created;
        if (withSensorCtor) {
            created = newInstance(type,
                    new Class<?>[]{Sensor.class, int.class, long.class, float[].class},
                    new Object[]{null, 3, 0L, new float[size]});
            if (created != null) return created;
        }
        return allocate(type, size);
    }

    private static <T> T newInstance(Class<T> type, Class<?>[] params, Object[] args) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor(params);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type, int size) {
        Object theUnsafe = unsafe();
        if (theUnsafe == null) return null;
        try {
            Method allocateInstance = theUnsafe.getClass().getMethod("allocateInstance", Class.class);
            T instance = (T) allocateInstance.invoke(theUnsafe, type);
            forceValues(instance, size);
            return instance;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void forceValues(Object event, int size) {
        if (event == null) return;
        try {
            Field values = event.getClass().getField("values");
            values.setAccessible(true);
            try {
                values.set(event, new float[size]);
                return;
            } catch (Throwable ignored) {
                // final 字段在新版本上需要走 Unsafe。
            }
            Object theUnsafe = unsafe();
            if (theUnsafe == null) return;
            Method offset = theUnsafe.getClass().getMethod("objectFieldOffset", Field.class);
            long fieldOffset = (Long) offset.invoke(theUnsafe, values);
            Method put = theUnsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
            put.invoke(theUnsafe, event, fieldOffset, new float[size]);
        } catch (Throwable ignored) {
        }
    }

    private static Object unsafe() {
        if (unsafe != null) return unsafe;
        String[] names = {"sun.misc.Unsafe", "jdk.internal.misc.Unsafe"};
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name);
                Field field = type.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = field.get(null);
                if (unsafe != null) return unsafe;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void logOnce(String kind) {
        if (logged) return;
        logged = true;
        try {
            de.robv.android.xposed.XposedBridge.log("RunHook: 无法构造 " + kind + "，停止合成事件");
        } catch (Throwable ignored) {
        }
    }
}
