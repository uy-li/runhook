package com.uy_li.runhook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

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
