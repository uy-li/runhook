package com.uy_li.runhook;

/**
 * 从 Java 栈里拿掉 Xposed / LSPosed / EdXposed 和本模块的帧。
 * native 的 /proc/self/maps、solist 扫描不在这里处理。
 */
public final class StackTraceFilter {

    private StackTraceFilter() {
    }

    public static boolean isSensitiveClassName(String name) {
        return "de.robv.android.xposed.XposedBridge".equals(name)
                || "de.robv.android.xposed.XposedHelpers".equals(name);
    }

    public static StackTraceElement[] filter(StackTraceElement[] trace) {
        if (trace == null || trace.length == 0) return trace;
        int keep = 0;
        for (StackTraceElement frame : trace) {
            if (frame != null && !isHookFrame(frame.getClassName())) keep++;
        }
        if (keep == trace.length) return trace;
        StackTraceElement[] out = new StackTraceElement[keep];
        int index = 0;
        for (StackTraceElement frame : trace) {
            if (frame != null && !isHookFrame(frame.getClassName())) {
                out[index++] = frame;
            }
        }
        return out;
    }

    /**
     * 栈顶往下，跳过钩子跳板之后，第一帧如果不是框架或本模块，就认为是目标应用在探测。
     * 看不出来时放行，避免把模块自己的类加载打坏。
     */
    public static boolean shouldHideFrameworkLookup(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            if (frame == null) continue;
            String name = frame.getClassName();
            if (name == null || isTrampoline(name)) continue;
            return !isTrustedLookupCaller(name);
        }
        return false;
    }

    static boolean isHookFrame(String className) {
        if (className == null || className.isEmpty()) return false;
        if (className.startsWith("de.robv.android.xposed.")) return true;
        if (className.startsWith("io.github.lsposed.")) return true;
        if (className.startsWith("org.lsposed.")) return true;
        if (className.startsWith("com.elderdrivers.")) return true;
        if (className.startsWith("me.weishu.")) return true;
        if (className.startsWith("org.meowcat.edxposed.")) return true;
        if (className.startsWith("com.uy_li.runhook.")) return true;
        if (className.indexOf("LSPHooker") >= 0) return true;
        if (className.indexOf("EdXposed") >= 0 || className.indexOf("edxposed") >= 0) return true;
        if (className.indexOf("XposedBridge") >= 0 || className.indexOf("XposedHelpers") >= 0) return true;
        return false;
    }

    private static boolean isTrampoline(String name) {
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("sun.") || name.startsWith("jdk.")
                || name.startsWith("dalvik.") || name.startsWith("libcore.")) {
            return true;
        }
        if (name.startsWith("de.robv.android.xposed.XposedBridge")) return true;
        if (name.startsWith("de.robv.android.xposed.XC_MethodHook")) return true;
        if (name.startsWith("de.robv.android.xposed.XposedInit")) return true;
        if (name.startsWith("io.github.lsposed.lspd.")) return true;
        if (name.startsWith("org.lsposed.lspd.")) return true;
        if (name.indexOf("LSPHooker") >= 0) return true;
        if (name.startsWith("com.uy_li.runhook.StealthHook")) return true;
        return false;
    }

    private static boolean isTrustedLookupCaller(String name) {
        return name.startsWith("de.robv.android.xposed.")
                || name.startsWith("io.github.lsposed.")
                || name.startsWith("org.lsposed.")
                || name.startsWith("com.elderdrivers.")
                || name.startsWith("com.uy_li.runhook.")
                || name.startsWith("android.")
                || name.startsWith("com.android.");
    }
}
