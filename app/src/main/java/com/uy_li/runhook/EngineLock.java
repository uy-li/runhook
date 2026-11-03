package com.uy_li.runhook;

/** 步频曲线、累加器和调度器共用的锁，放在不依赖 Android 的类里，方便单测。 */
final class EngineLock {
    static final Object STATE = new Object();

    private EngineLock() {
    }
}
