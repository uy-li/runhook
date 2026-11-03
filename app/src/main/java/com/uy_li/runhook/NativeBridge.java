package com.uy_li.runhook;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;

/**
 * 把模块 APK 里的 native 库解出来，在目标进程里挂钩传感器读取。
 * 失败时保持 Java 合成路径，不让目标应用崩溃。
 */
final class NativeBridge {

    private static final String TAG = "RunHook";
    private static volatile boolean attempted;
    private static volatile boolean active;

    private NativeBridge() {
    }

    static boolean isActive() {
        return active;
    }

    static boolean ensure(Context context) {
        if (attempted) return active;
        synchronized (NativeBridge.class) {
            if (attempted) return active;
            attempted = true;
            try {
                File apk = moduleApk();
                if (apk == null || !apk.isFile()) {
                    log("找不到模块 APK，只用 Java 路径");
                    return false;
                }
                String abi = pickAbi();
                File dir = new File(context.getCodeCacheDir(), "runhook-native");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    dir = new File(context.getCacheDir(), "runhook-native");
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        log("无法创建 native 缓存目录");
                        return false;
                    }
                }
                File shadow = extract(apk, "lib/" + abi + "/libshadowhook.so", new File(dir, "libshadowhook.so"));
                File ours = extract(apk, "lib/" + abi + "/librunhook.so", new File(dir, "librunhook.so"));
                if (ours == null) {
                    log("APK 里没有 " + abi + " 的 librunhook.so，只用 Java 路径");
                    return false;
                }
                if (shadow != null) System.load(shadow.getAbsolutePath());
                System.load(ours.getAbsolutePath());
                active = nativeInit();
                log(active ? "native 传感器钩子已安装" : "native 符号没挂上，回退 Java");
            } catch (Throwable t) {
                active = false;
                log("native 加载失败，回退 Java: " + t);
            }
            return active;
        }
    }

    static void publish(boolean engineActive, boolean spoofPressure, boolean javaOwnsSynthesis,
                        float counter, float stepHz, double phase, long phaseTimeNs,
                        float pressure, float tiltRoll, float tiltPitch, int noiseSeed) {
        if (!active) return;
        try {
            nativePublish(engineActive, spoofPressure, javaOwnsSynthesis, counter, stepHz, phase,
                    phaseTimeNs, pressure, tiltRoll, tiltPitch, noiseSeed);
        } catch (Throwable ignored) {
        }
    }

    static float lastHardware() {
        if (!active) return Float.NaN;
        try {
            return nativeLastHardware();
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    static float monotonic(float proposed) {
        if (!active || !Float.isFinite(proposed)) return proposed;
        try {
            return nativeMonotonic(proposed);
        } catch (Throwable ignored) {
            return proposed;
        }
    }

    private static File moduleApk() {
        String path = JoyrunHook.modulePath;
        if (path != null && !path.isEmpty()) {
            File file = new File(path);
            if (file.isFile()) return file;
        }
        try {
            java.security.CodeSource source = NativeBridge.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                File file = new File(source.getLocation().toURI());
                if (file.isFile()) return file;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String pickAbi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) return abi;
        }
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
    }

    private static File extract(File apk, String entryName, File out) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
            if (out.exists() && !out.delete()) {
                out = tmp;
            } else if (!tmp.renameTo(out)) {
                out = tmp;
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
            return out;
        } catch (Throwable t) {
            log("解压 " + entryName + " 失败: " + t.getMessage());
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }

    private static native boolean nativeInit();

    private static native void nativePublish(boolean active, boolean spoofPressure, boolean javaOwnsSynthesis,
                                             float counter, float stepHz, double phase, long phaseTimeNs,
                                             float pressure, float tiltRoll, float tiltPitch, int noiseSeed);

    private static native float nativeLastHardware();

    private static native float nativeMonotonic(float proposed);
}
