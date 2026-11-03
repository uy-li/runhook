package com.uy_li.runhook;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 气压海拔和 GPS / MSL / NMEA 用同一个目标高度。GPS 与 MSL 各有一小段独立噪声。
 * 高德 AMapLocation、百度 BDLocation 以及后来加载的 Location 子类，只要自己重写了 getAltitude 就补钩。
 */
final class LocationSpoof {

    private static final String TAG = "RunHook";
    private static final Set<String> HOOKED = ConcurrentHashMap.newKeySet();
    private static final Map<Object, Object> NMEA_WRAPS = new IdentityHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static Class<?> legacyNmea;

    private LocationSpoof() {
    }

    static void install(ClassLoader loader) {
        hookLocationClass(Location.class);
        hookKnown(loader, "com.amap.api.location.AMapLocation");
        hookKnown(loader, "com.baidu.location.BDLocation");
        hookLocationListeners(loader);
        hookNmea(loader);
        watchClassLoader();
    }

    private static void hookKnown(ClassLoader loader, String name) {
        try {
            Class<?> type = XposedHelpers.findClass(name, loader);
            consider(type);
        } catch (Throwable ignored) {
        }
    }

    private static void watchClassLoader() {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (BUSY.get() || param.args == null || param.args.length == 0) return;
                    if (!(param.args[0] instanceof String)) return;
                    String name = (String) param.args[0];
                    if (!interesting(name)) return;
                    Object result = param.getResult();
                    if (!(result instanceof Class)) return;
                    BUSY.set(Boolean.TRUE);
                    try {
                        consider((Class<?>) result);
                    } catch (Throwable ignored) {
                    } finally {
                        BUSY.set(Boolean.FALSE);
                    }
                }
            });
        } catch (Throwable t) {
            log("ClassLoader 监视失败: " + t.getMessage());
        }
    }

    private static boolean interesting(String name) {
        if (name == null || name.startsWith("java.") || name.startsWith("android.")
                || name.startsWith("com.uy_li.runhook.")) {
            return false;
        }
        return name.indexOf("Location") >= 0 || name.indexOf("location") >= 0
                || name.indexOf("AMap") >= 0 || name.indexOf("amap") >= 0
                || name.indexOf("baidu") >= 0 || name.indexOf("Baidu") >= 0;
    }

    private static void consider(Class<?> type) {
        if (type == null || type == Location.class) return;
        boolean locationSub = false;
        try {
            locationSub = Location.class.isAssignableFrom(type);
        } catch (Throwable ignored) {
        }
        String name = type.getName();
        boolean known = name.endsWith("AMapLocation") || name.endsWith("BDLocation");
        if (!locationSub && !known) return;
        hookLocationClass(type);
    }

    private static void hookLocationClass(Class<?> type) {
        Method[] methods;
        try {
            methods = type.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method method : methods) {
            if (method.getParameterTypes().length != 0) continue;
            if (!isAltitudeMethod(method.getName())) continue;
            String key = type.getName() + "#" + method.getName() + method.getReturnType().getName();
            if (!HOOKED.add(key)) continue;
            try {
                XposedBridge.hookMethod(method, ALTITUDE);
                log("海拔方法 " + key);
            } catch (Throwable t) {
                HOOKED.remove(key);
                log("海拔方法失败 " + key + ": " + t.getMessage());
            }
        }
    }

    private static boolean isAltitudeMethod(String name) {
        return "getAltitude".equals(name)
                || "hasAltitude".equals(name)
                || "getMslAltitudeMeters".equals(name)
                || "hasMslAltitude".equals(name)
                || "getVerticalAccuracyMeters".equals(name)
                || "hasVerticalAccuracy".equals(name);
    }

    private static final XC_MethodHook ALTITUDE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Double gps = JoyrunHook.gpsAltitudeOrNull();
                if (gps == null) return;
                Method method = (Method) param.method;
                Class<?> ret = method.getReturnType();
                String name = method.getName();
                if (ret == boolean.class) {
                    param.setResult(Boolean.TRUE);
                } else if (ret == float.class) {
                    if (name.indexOf("Accuracy") >= 0 || name.indexOf("accuracy") >= 0) {
                        param.setResult(JoyrunHook.verticalAccuracyMeters());
                    } else {
                        param.setResult(gps.floatValue());
                    }
                } else if (ret == double.class) {
                    if (name.toLowerCase(Locale.US).indexOf("msl") >= 0) {
                        Double msl = JoyrunHook.mslAltitudeOrNull();
                        param.setResult(msl != null ? msl : gps);
                    } else {
                        param.setResult(gps);
                    }
                } else if (ret == String.class) {
                    param.setResult(String.format(Locale.US, "%.1f", gps));
                }
            } catch (Throwable t) {
                log("海拔读取改写失败: " + t.getMessage());
            }
        }
    };

    private static void hookLocationListeners(ClassLoader loader) {
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
                                ensureListener((LocationListener) arg);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log("LocationManager: " + t.getMessage());
        }
    }

    private static final Set<Class<?>> LISTENERS = ConcurrentHashMap.newKeySet();

    private static void ensureListener(LocationListener listener) {
        Class<?> type = listener.getClass();
        if (!LISTENERS.add(type)) return;
        boolean hooked = false;
        try {
            XposedHelpers.findAndHookMethod(type, "onLocationChanged", Location.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Location) {
                        stamp((Location) param.args[0]);
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
                        if (item instanceof Location) stamp((Location) item);
                    }
                }
            });
            hooked = true;
        } catch (Throwable ignored) {
        }
        if (!hooked) LISTENERS.remove(type);
    }

    private static void stamp(Location location) {
        if (location == null) return;
        Double gps = JoyrunHook.gpsAltitudeOrNull();
        if (gps == null) return;
        try {
            location.setAltitude(gps);
        } catch (Throwable ignored) {
        }
        try {
            Method has = location.getClass().getMethod("setMslAltitudeMeters", double.class);
            Double msl = JoyrunHook.mslAltitudeOrNull();
            if (msl != null) has.invoke(location, msl);
        } catch (Throwable ignored) {
        }
        try {
            Method accuracy = location.getClass().getMethod("setVerticalAccuracyMeters", float.class);
            accuracy.invoke(location, JoyrunHook.verticalAccuracyMeters());
        } catch (Throwable ignored) {
        }
    }

    private static void hookNmea(ClassLoader loader) {
        try {
            legacyNmea = Class.forName("android.location.GpsStatus$NmeaListener", false, loader);
        } catch (Throwable ignored) {
            legacyNmea = null;
        }
        try {
            Class<?> manager = XposedHelpers.findClass("android.location.LocationManager", loader);
            for (Method method : manager.getDeclaredMethods()) {
                String name = method.getName();
                if ("addNmeaListener".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewriteNmeaArg(param, false);
                        }
                    });
                } else if ("removeNmeaListener".equals(name)) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewriteNmeaArg(param, true);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            log("NMEA: " + t.getMessage());
        }
    }

    private static void rewriteNmeaArg(XC_MethodHook.MethodHookParam param, boolean remove) {
        if (param.args == null) return;
        try {
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (!isNmeaListener(arg) || arg instanceof RewritingNmea) continue;
                synchronized (NMEA_WRAPS) {
                    if (remove) {
                        Object wrapped = NMEA_WRAPS.remove(arg);
                        if (wrapped != null) param.args[i] = wrapped;
                    } else {
                        Object wrapped = NMEA_WRAPS.get(arg);
                        if (wrapped == null) {
                            wrapped = wrapNmea(arg);
                            if (wrapped != null) NMEA_WRAPS.put(arg, wrapped);
                        }
                        if (wrapped != null) param.args[i] = wrapped;
                    }
                }
            }
        } catch (Throwable t) {
            log("NMEA 包装失败: " + t.getMessage());
        }
    }

    private static boolean isNmeaListener(Object arg) {
        if (arg instanceof OnNmeaMessageListener) return true;
        return legacyNmea != null && legacyNmea.isInstance(arg);
    }

    private static Object wrapNmea(Object listener) {
        if (listener instanceof OnNmeaMessageListener) {
            return new RewritingNmea((OnNmeaMessageListener) listener);
        }
        if (legacyNmea == null || !legacyNmea.isInstance(listener)) return null;
        InvocationHandler handler = (proxy, method, args) -> {
            if ("onNmeaReceived".equals(method.getName()) && args != null && args.length >= 2) {
                long timestamp = args[0] instanceof Long ? (Long) args[0] : 0L;
                String message = args[1] instanceof String ? (String) args[1] : null;
                return method.invoke(listener, timestamp, rewriteSentence(message));
            }
            if (args == null) return method.invoke(listener);
            return method.invoke(listener, args);
        };
        try {
            return Proxy.newProxyInstance(legacyNmea.getClassLoader(), new Class<?>[]{legacyNmea}, handler);
        } catch (Throwable t) {
            log("旧 NMEA 代理失败: " + t.getMessage());
            return null;
        }
    }

    private static String rewriteSentence(String message) {
        if (message == null) return null;
        Double gps = JoyrunHook.gpsAltitudeOrNull();
        if (gps == null) return message;
        return NmeaRewriter.rewrite(message, gps);
    }

    private static final class RewritingNmea implements OnNmeaMessageListener {
        final OnNmeaMessageListener delegate;

        RewritingNmea(OnNmeaMessageListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onNmeaMessage(String message, long timestamp) {
            delegate.onNmeaMessage(rewriteSentence(message), timestamp);
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }
}
