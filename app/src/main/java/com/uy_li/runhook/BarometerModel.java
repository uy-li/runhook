package com.uy_li.runhook;

/**
 * 在标准大气压高公式的结果上叠加慢漂移和高斯噪声。
 * 漂移是时间的连续函数；噪声按 50ms 分桶，同一个时间戳得到同一个值。
 * GPS / MSL 海拔跟气压用同一个目标高度，但噪声相互独立。
 */
public final class BarometerModel {

    private final int seed;

    public BarometerModel(long seed) {
        long mixed = seed == 0L ? 1L : seed;
        this.seed = (int) (mixed ^ (mixed >>> 32));
    }

    public int seed() {
        return seed;
    }

    /** 约 0.01–0.03 hPa 的噪声，再加振幅约 0.012 hPa 的慢漂移。 */
    public float pressureHpa(double altitudeMeters, long timeMs) {
        float base = AltitudeCurve.pressureHpa((float) altitudeMeters);
        double seconds = timeMs / 1000.0;
        double drift = 0.012 * Math.sin(seconds * (Math.PI * 2.0) / 53.0 + (seed & 15) * 0.2);
        float noise = MotionWaveform.unitGaussian(seed, timeMs / 50L, 1) * 0.012f;
        if (noise > 0.036f) noise = 0.036f;
        if (noise < -0.036f) noise = -0.036f;
        return base + (float) drift + noise;
    }

    public double gpsAltitudeMeters(double altitudeMeters, long timeMs) {
        return altitudeMeters + independent(timeMs, 61.0, 0.35, 0.8, 2, 0.7);
    }

    public double mslAltitudeMeters(double altitudeMeters, long timeMs) {
        return altitudeMeters + independent(timeMs, 71.0, 0.30, 0.7, 4, 1.3);
    }

    /** 常见手机气压计/GPS 垂直精度，大约 1.5–8 米。 */
    public float verticalAccuracyMeters(long timeMs) {
        double seconds = timeMs / 1000.0;
        double value = 3.4
                + 0.7 * Math.sin(seconds * 0.05 + 0.4)
                + Math.abs(MotionWaveform.unitGaussian(seed, timeMs / 500L, 5)) * 0.45;
        if (value < 1.5) value = 1.5;
        if (value > 12.0) value = 12.0;
        return (float) value;
    }

    private double independent(long timeMs, double periodSec, double driftAmp, double noiseAmp,
                               int channel, double phase) {
        double seconds = timeMs / 1000.0;
        double drift = driftAmp * Math.sin(seconds * (Math.PI * 2.0) / periodSec + phase);
        double noise = MotionWaveform.unitGaussian(seed, timeMs / 200L, channel) * noiseAmp;
        if (noise > noiseAmp * 2.5) noise = noiseAmp * 2.5;
        if (noise < -noiseAmp * 2.5) noise = -noiseAmp * 2.5;
        return drift + noise;
    }
}
