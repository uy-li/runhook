package com.uy_li.runhook;

import android.app.Application;

/**
 * 在界面创建前套上已保存的浅色/深色，避免先闪一下系统主题。
 */
public class RunHookApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ConfigStore.applyStoredNightMode(this);
    }
}
