package com.uy_li.runhook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

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
    public static final String KEY_TOKEN = "control_token";
    public static final String KEY_DARK_THEME = "dark_theme";
    public static final String KEY_ALT_ENABLED = "altitude_enabled";
    public static final String KEY_ALT_MIN = "min_altitude";
    public static final String KEY_ALT_MAX = "max_altitude";

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

    /** 默认浅色。只有用户点过右上角按钮才进入深色。 */
    public static boolean isDarkTheme(Context context) {
        return prefs(context).getBoolean(KEY_DARK_THEME, false);
    }

    public static void setDarkTheme(Context context, boolean dark) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, dark).commit();
        makeReadable(context);
        applyNightMode(dark);
    }

    public static void applyStoredNightMode(Context context) {
        applyNightMode(isDarkTheme(context));
    }

    private static void applyNightMode(boolean dark) {
        int mode = dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
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

    /** 只拒绝 NaN、无穷、空值和小于等于 0。合法数字不再夹到 140–220。 */
    public static boolean isValidCadence(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0f;
    }

    public static float sanitizeCadence(float value, float fallback) {
        if (isValidCadence(value)) return value;
        if (isValidCadence(fallback)) return fallback;
        return DEFAULT_MIN;
    }

    /**
     * 海拔允许负数，但必须落在 -500 到 9000 米。空串、非数字、NaN 和越界返回 null。
     */
    public static Float parseAltitude(String text) {
        if (text == null) return null;
        String trimmed = text.trim().replace(',', '.');
        if (trimmed.isEmpty()) return null;
        try {
            float value = Float.parseFloat(trimmed);
            return AltitudeCurve.isValidAltitude(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean saveAltitude(Context context, boolean enabled, float min, float max) {
        float[] range = new float[2];
        if (!AltitudeCurve.normalizeRange(min, max, range)) return false;
        prefs(context).edit()
                .putBoolean(KEY_ALT_ENABLED, enabled)
                .putFloat(KEY_ALT_MIN, range[0])
                .putFloat(KEY_ALT_MAX, range[1])
                .commit();
        makeReadable(context);
        return true;
    }

    /** @return 解析成功且大于 0 时返回数值，空串、非数字、0 或负数返回 null。 */
    public static Float parseCadence(String text) {
        if (text == null) return null;
        String trimmed = text.trim().replace(',', '.');
        if (trimmed.isEmpty()) return null;
        try {
            float value = Float.parseFloat(trimmed);
            return isValidCadence(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean saveCadence(Context context, float min, float max) {
        if (!isValidCadence(min) || !isValidCadence(max)) return false;
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
        return true;
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
        min = sanitizeCadence(min, DEFAULT_MIN);
        max = sanitizeCadence(max, DEFAULT_MAX);
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
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
