package com.uy_li.runhook;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 目标进程里的基础反检测。只处理 Java 层：
 * 栈过滤、Class.forName / loadClass 对 XposedBridge 与 XposedHelpers 的探测、
 * 以及 PackageManager 里对本模块包名的查询。
 * 不覆盖 native 的 /proc/self/maps、solist、内存特征扫描；那些需要真机另行评估。
 * 任何异常都放行，避免把目标进程或模块自己的类加载打坏。
 */
final class StealthHook {

    private static final String TAG = "RunHook";
    private static final String MODULE = "com.uy_li.runhook";
    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static boolean installed;

    private StealthHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed || lpparam == null) return;
        installed = true;
        hookStack();
        hookClassLookup();
        hookPackageManager(lpparam.classLoader);
    }

    private static void hookStack() {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (RAW.get()) return;
                try {
                    Object result = param.getResult();
                    if (result instanceof StackTraceElement[]) {
                        param.setResult(StackTraceFilter.filter((StackTraceElement[]) result));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Throwable.class, "getStackTrace", filter);
        } catch (Throwable t) {
            log("Throwable.getStackTrace: " + t.getMessage());
        }
        try {
            XposedBridge.hookAllMethods(Thread.class, "getStackTrace", filter);
        } catch (Throwable t) {
            log("Thread.getStackTrace: " + t.getMessage());
        }
    }

    private static void hookClassLookup() {
        XC_MethodHook hide = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (RAW.get() || param.args == null || param.args.length == 0) return;
                    if (!(param.args[0] instanceof String)) return;
                    String name = (String) param.args[0];
                    if (!StackTraceFilter.isSensitiveClassName(name)) return;
                    if (calledFromApp()) {
                        param.setThrowable(new ClassNotFoundException(name));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Class.class, "forName", hide);
        } catch (Throwable t) {
            log("Class.forName: " + t.getMessage());
        }
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", hide);
        } catch (Throwable t) {
            log("ClassLoader.loadClass: " + t.getMessage());
        }
    }

    private static boolean calledFromApp() {
        RAW.set(Boolean.TRUE);
        try {
            return StackTraceFilter.shouldHideFrameworkLookup(Thread.currentThread().getStackTrace());
        } catch (Throwable ignored) {
            return false;
        } finally {
            RAW.set(Boolean.FALSE);
        }
    }

    private static void hookPackageManager(ClassLoader loader) {
        Class<?> manager = find(loader, "android.app.ApplicationPackageManager");
        if (manager == null) {
            log("ApplicationPackageManager 不存在，跳过包名隐藏");
            return;
        }
        try {
            XposedBridge.hookAllMethods(manager, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (MODULE.equals(packageArg(param.args))) {
                            param.setThrowable(new PackageManager.NameNotFoundException(MODULE));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("getPackageInfo: " + t.getMessage());
        }
        hookList(manager, "getInstalledPackages");
        hookList(manager, "getInstalledApplications");
        hookList(manager, "queryIntentActivities");
    }

    private static void hookList(Class<?> manager, String name) {
        try {
            XposedBridge.hookAllMethods(manager, name, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof List)) return;
                        List<?> list = (List<?>) result;
                        boolean hit = false;
                        for (Object item : list) {
                            if (MODULE.equals(packageOf(item))) {
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) return;
                        ArrayList<Object> copy = new ArrayList<>(list.size());
                        for (Object item : list) {
                            if (!MODULE.equals(packageOf(item))) copy.add(item);
                        }
                        param.setResult(copy);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log(name + ": " + t.getMessage());
        }
    }

    private static String packageArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String) return (String) arg;
            if (arg == null) continue;
            try {
                if (arg.getClass().getName().indexOf("VersionedPackage") >= 0) {
                    Object name = arg.getClass().getMethod("getPackageName").invoke(arg);
                    if (name instanceof String) return (String) name;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String packageOf(Object item) {
        if (item instanceof PackageInfo) return ((PackageInfo) item).packageName;
        if (item instanceof ApplicationInfo) return ((ApplicationInfo) item).packageName;
        if (item instanceof ResolveInfo) {
            ResolveInfo info = (ResolveInfo) item;
            if (info.activityInfo != null) return info.activityInfo.packageName;
            if (info.serviceInfo != null) return info.serviceInfo.packageName;
            if (info.providerInfo != null) return info.providerInfo.packageName;
        }
        return null;
    }

    private static Class<?> find(ClassLoader loader, String name) {
        try {
            return de.robv.android.xposed.XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
        }
        try {
            return de.robv.android.xposed.XposedHelpers.findClass(name, null);
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
}
