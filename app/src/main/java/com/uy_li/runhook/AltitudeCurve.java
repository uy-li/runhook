package com.uy_li.runhook;

import java.util.Random;

/**
 * 海拔在用户区间内缓慢漂移。爬升速率不超过跑步/登山里常见的 0.4 米/秒，曲线本身没有跳变。
 * 气压用国际标准大气的压高公式，和 {@code SensorManager.getAltitude(p0, p)} 互为反函数：
 * altitude = 44330 * (1 - (p / p0) ^ (1/5.255))，p0 = 1013.25 hPa。
 * 合法海拔为 -500 到 9000 米（低于死海、高于珠峰一点），允许负数。
 */
public final class AltitudeCurve {

    public static final float LIMIT_LOW = -500f;
    public static final float LIMIT_HIGH = 9000f;
    public static final float DEFAULT_MIN = 30f;
    public static final float DEFAULT_MAX = 80f;
    public static final float STANDARD_ATMOSPHERE_HPA = 1013.25f;
    /** 与 android.hardware.SensorManager.getAltitude 使用的 1/5.255 一致。 */
    public static final float ALTITUDE_COEF = 1.0f / 5.255f;
    public static final double MAX_CLIMB_MPS = 0.40;

    private static final double[] WEIGHTS = {0.55, 0.30, 0.15};
    private static final double[] BASE_PERIODS = {180.0, 420.0, 900.0};

    private long seed = 1L;
    private float min = DEFAULT_MIN;
    private float max = DEFAULT_MAX;
    /** {@link Long#MIN_VALUE} 表示还没有会话。0 是合法的起始时间。 */
    private long sessionStartMs = Long.MIN_VALUE;
    private final double[] phases = new double[3];
    private final double[] periods = new double[3];

    public AltitudeCurve(long seed) {
        reseed(seed);
        recomputePeriods();
    }

    public void reseed(long seed) {
        this.seed = seed == 0L ? 1L : seed;
        Random random = new Random(this.seed);
        for (int i = 0; i < phases.length; i++) {
            phases[i] = random.nextDouble() * (Math.PI * 2.0);
        }
        recomputePeriods();
    }

    public long seed() {
        return seed;
    }

    public long sessionStartMs() {
        return sessionStartMs;
    }

    public boolean isStarted() {
        return sessionStartMs != Long.MIN_VALUE;
    }

    public void setRange(float min, float max) {
        if (!isValidAltitude(min) || !isValidAltitude(max)) return;
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        if (this.min == min && this.max == max) return;
        this.min = min;
        this.max = max;
        recomputePeriods();
    }

    public void start(long nowMs) {
        sessionStartMs = nowMs;
    }

    public void stop() {
        sessionStartMs = Long.MIN_VALUE;
    }

    public void restore(long seed, float min, float max, long sessionStartMs) {
        reseed(seed);
        setRange(min, max);
        this.sessionStartMs = sessionStartMs;
    }

    public static boolean shouldSpoof(boolean engineOn, boolean altitudeOn) {
        return engineOn && altitudeOn;
    }

    public static boolean isValidAltitude(float value) {
        return Float.isFinite(value) && value >= LIMIT_LOW && value <= LIMIT_HIGH;
    }

    /** 任一端非法则返回 false。颠倒的两端会交换后写入 out。 */
    public static boolean normalizeRange(float min, float max, float[] out) {
        if (out == null || out.length < 2) return false;
        if (!isValidAltitude(min) || !isValidAltitude(max)) return false;
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        out[0] = min;
        out[1] = max;
        return true;
    }

    /**
     * 标准大气压（hPa）。用 getAltitude 的指数的倒数，使
     * altitudeFromPressure(1013.25, pressureHpa(h)) 回到 h。
     */
    public static float pressureHpa(float altitudeMeters) {
        double ratio = 1.0 - (altitudeMeters / 44330.0);
        if (ratio < 1.0e-4) ratio = 1.0e-4;
        double exponent = 1.0 / ALTITUDE_COEF;
        return (float) (STANDARD_ATMOSPHERE_HPA * Math.pow(ratio, exponent));
    }

    /** 与 SensorManager.getAltitude 相同的浮点公式。 */
    public static float altitudeFromPressure(float p0, float p) {
        if (!Float.isFinite(p0) || !Float.isFinite(p) || p0 == 0f) return Float.NaN;
        return 44330.0f * (1.0f - (float) Math.pow(p / p0, ALTITUDE_COEF));
    }

    public double altitudeAt(long nowMs) {
        double mid = ((double) min + max) / 2.0;
        double half = ((double) max - min) / 2.0;
        if (!isStarted() || half <= 0d) return mid;
        double u = (nowMs - sessionStartMs) / 1000.0;
        if (u < 0d) u = 0d;
        double noise = 0d;
        for (int i = 0; i < WEIGHTS.length; i++) {
            double period = periods[i];
            if (period <= 0d) continue;
            noise += WEIGHTS[i] * Math.sin((Math.PI * 2.0) * u / period + phases[i]);
        }
        double value = mid + half * noise;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void recomputePeriods() {
        System.arraycopy(BASE_PERIODS, 0, periods, 0, BASE_PERIODS.length);
        double half = ((double) max - min) / 2.0;
        if (half < 1.0e-4) return;
        double unitSlope = 0d;
        for (int i = 0; i < WEIGHTS.length; i++) {
            unitSlope += WEIGHTS[i] * (Math.PI * 2.0) / periods[i];
        }
        double limit = MAX_CLIMB_MPS / half;
        if (unitSlope > limit && limit > 0d) {
            double scale = unitSlope / limit;
            for (int i = 0; i < periods.length; i++) {
                periods[i] *= scale;
            }
        }
    }
}
