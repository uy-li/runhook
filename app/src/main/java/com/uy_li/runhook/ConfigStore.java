package com.uy_li.runhook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.UUID;

/**
 * 模块自己的配置。默认关闭。
 * LSPosed 1.8+ 需要用 MODE_WORLD_READABLE 才会把配置放到 Hook 进程能读到的目录。
 */
public final class ConfigStore {

    public static final String PREF_NAME = "JoyrunConfig";
    public static final String KEY_ENABLED = "engine_enabled";
    public static final String KEY_MIN = "min_cadence";
    public static final String KEY_MAX = "max_cadence";
    public static final float DEFAULT_MIN = 172f;
    public static final float DEFAULT_MAX = 182f;
    public static final float CADENCE_MIN = 140f;
    public static final float CADENCE_MAX = 220f;
    public static final String KEY_TOKEN = "control_token";

    /** 代码实际会 Hook 的包，和 res/values/arrays.xml 里的 xposedscope 保持一致。 */
    public static final String[] TARGET_PACKAGES = {
            "android",
            "co.runner.app",
            "com.heytap.health",
            "com.oplus.healthservice",
            "com.oplus.pedometer",
            "com.gotokeep.keep",
            "com.android.healthconnect.controller"
    };

    private ConfigStore() {
    }

    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    public static SharedPreferences prefs(Context context) {
        try {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            // 模块进程还没被 LSPosed 注入时，系统会拒绝 WORLD_READABLE。
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    public static boolean isTargetPackage(String packageName) {
        if (packageName == null) return false;
        for (String item : TARGET_PACKAGES) {
            if (item.equals(packageName)) return true;
        }
        return false;
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    /** 没有写过开关时明确落成关闭，避免 Hook 侧把“读不到”和“用户开过”混在一起。 */
    public static void ensureDefaultOff(Context context) {
        SharedPreferences sp = prefs(context);
        if (!sp.contains(KEY_ENABLED)) {
            sp.edit().putBoolean(KEY_ENABLED, false).commit();
            makeReadable(context);
        }
        ensureToken(context);
    }

    /** 滑块读到越界或 NaN 时，RangeSlider.setValues 会直接把界面崩掉。 */
    public static float clampCadence(float value) {
        if (Float.isNaN(value) || value < CADENCE_MIN) return CADENCE_MIN;
        if (value > CADENCE_MAX) return CADENCE_MAX;
        return value;
    }

    public static void saveCadence(Context context, float min, float max) {
        min = clampCadence(min);
        max = clampCadence(max);
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        prefs(context).edit()
                .putFloat(KEY_MIN, min)
                .putFloat(KEY_MAX, max)
                .commit();
        makeReadable(context);
    }

    public static String ensureToken(Context context) {
        SharedPreferences sp = prefs(context);
        String token = sp.getString(KEY_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = UUID.randomUUID().toString();
            sp.edit().putString(KEY_TOKEN, token).commit();
            makeReadable(context);
        }
        return token;
    }

    public static void setEnabled(Context context, boolean enabled, float min, float max) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putFloat(KEY_MIN, min)
                .putFloat(KEY_MAX, max)
                .commit();
        makeReadable(context);
    }

    /**
     * 旧版 XSharedPreferences 直接读 /data/data/.../shared_prefs。
     * 新版由 LSPosed 转发，这里失败也没关系。
     */
    private static void makeReadable(Context context) {
        try {
            File dataDir = new File(context.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, PREF_NAME + ".xml");
            dataDir.setExecutable(true, false);
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
