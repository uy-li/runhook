package com.uy_li.runhook;

/**
 * 在硬件计步器读数之上累加模拟步数。
 * 关闭引擎后停止累加，但保留已有偏移，避免读数突然变小。
 * 只有硬件计数本身倒退（重启后传感器复位）时才清掉偏移。
 */
public final class StepAccumulator {

    /** 单次回调最多补记的秒数，避免校时把步数一次拉高几天。 */
    static final float MAX_GAP_SEC = 12f * 3600f;

    float boost;
    float lastReal = -1f;
    long lastUpdateMs;

    public boolean advance(float real, long nowMs, boolean enabled, float stepsPerSec) {
        if (Float.isNaN(real) || Float.isInfinite(real)) {
            return false;
        }

        boolean reset = false;
        // 浮点抖动不应当成重启。真正的开机复位会掉到接近 0。
        if (lastReal >= 0f && real + 1f < lastReal) {
            boost = 0f;
            reset = true;
        }

        if (!reset && enabled && lastUpdateMs > 0L && nowMs > lastUpdateMs && stepsPerSec > 0f) {
            float dt = (nowMs - lastUpdateMs) / 1000f;
            if (dt > MAX_GAP_SEC) {
                dt = MAX_GAP_SEC;
            }
            if (dt > 0f) {
                boost += dt * stepsPerSec;
            }
        }

        lastReal = real;
        if (nowMs > 0L) {
            lastUpdateMs = nowMs;
        }
        return reset;
    }

    public boolean shouldRewrite() {
        return boost > 0f;
    }

    public float reported(float real) {
        return real + Math.max(0f, boost);
    }
}
