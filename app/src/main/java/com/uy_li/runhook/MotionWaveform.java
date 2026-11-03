package com.uy_li.runhook;

/**
 * 与 {@link CadenceCurve} 的积分相位对齐的运动波形。
 * 重力、加速度、线加速度、陀螺和姿态用同一组姿态角，因此线性加速度严格等于加速度减重力。
 * 垂直冲击含基频、二次、三次谐波，并在着地相位叠一个不对称的足跟着地尖峰；
 * 左右摆动和角速度用步频的一半。噪声是高斯的，慢漂移只改变姿态，不把重力模长拉离 g。
 *
 * 公式与 native 侧 runhook_native.cpp 保持一致；改这里时两边一起改。
 */
public final class MotionWaveform {

    public static final float GRAVITY = 9.80665f;
    /** 垂直基频、二次谐波、三次谐波、足跟着地尖峰（米/秒²）。 */
    public static final float H1 = 1.15f;
    public static final float H2 = 0.72f;
    public static final float H3 = 0.28f;
    public static final float HEEL = 2.60f;
    public static final float LATERAL = 0.42f;
    public static final float FORWARD = 0.25f;
    public static final float NOISE_XY = 0.035f;
    public static final float NOISE_Z = 0.05f;

    public final float tiltRollRad;
    public final float tiltPitchRad;
    public final int noiseSeed;

    public MotionWaveform(long sessionSeed) {
        long seed = sessionSeed == 0L ? 1L : sessionSeed;
        java.util.Random random = new java.util.Random(seed);
        tiltRollRad = (float) ((random.nextDouble() - 0.5) * 0.34);
        tiltPitchRad = (float) ((random.nextDouble() - 0.5) * 0.48);
        noiseSeed = random.nextInt();
    }

    /** 确定性高斯噪声，标准差约 1。bucket 相同则结果相同。 */
    public static float unitGaussian(int seed, long bucket, int channel) {
        long x = bucket * 0x9E3779B97F4A7C15L
                ^ ((long) seed * 0xBF58476D1CE4E5B9L)
                ^ (channel * 0x94D049BB133111EBL);
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        long y = x * 0xD1B54A32D192ED03L + (channel + 1L) * 0x9E3779B97F4A7C15L;
        y ^= (y >>> 33);
        y *= 0xBF58476D1CE4E5B9L;
        y ^= (y >>> 29);
        double u1 = ((x >>> 11) & 0x1FFFFFL) / (double) 0x200000L;
        double u2 = ((y >>> 11) & 0x1FFFFFL) / (double) 0x200000L;
        if (u1 < 1.0e-7) u1 = 1.0e-7;
        return (float) (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(Math.PI * 2.0 * u2));
    }

    public Sample sample(double phaseRad, double stepHz, long timeNs) {
        long bucket = timeNs / 20_000_000L;
        if (bucket < 0L) bucket = -bucket;
        double seconds = timeNs * 1.0e-9;
        double drift = 0.012 * Math.sin(seconds * (Math.PI * 2.0) / 23.0 + ((noiseSeed & 7) * 0.17));
        double half = phaseRad * 0.5;
        double roll = tiltRollRad + 0.020 * Math.sin(half) + drift;
        double pitch = tiltPitchRad + 0.015 * Math.cos(half);
        double yaw = 0.012 * Math.sin(half + 0.4);

        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        float gx = (float) (GRAVITY * cr * sp);
        float gy = (float) (-GRAVITY * sr);
        float gz = (float) (GRAVITY * cr * cp);

        double wrapped = Math.atan2(Math.sin(phaseRad), Math.cos(phaseRad));
        double heel = Math.exp(-0.5 * Math.pow(wrapped / 0.28, 2));
        if (wrapped < 0.0) heel *= 0.35;
        double vertical = H1 * Math.sin(phaseRad)
                + H2 * Math.sin(2.0 * phaseRad - 0.55)
                + H3 * Math.sin(3.0 * phaseRad + 0.35)
                + HEEL * heel;
        float n0 = unitGaussian(noiseSeed, bucket, 1);
        float n1 = unitGaussian(noiseSeed, bucket, 2);
        float n2 = unitGaussian(noiseSeed, bucket, 3);
        float lx = (float) (LATERAL * Math.sin(half) + n0 * NOISE_XY);
        float ly = (float) (FORWARD * Math.cos(phaseRad) + n1 * NOISE_XY);
        float lz = (float) (vertical + n2 * NOISE_Z);

        float ax = gx + lx;
        float ay = gy + ly;
        float az = gz + lz;

        double scale = stepHz <= 0.0 ? 1.0 : Math.max(0.65, Math.min(1.7, stepHz / 3.0));
        float wx = (float) ((0.11 * scale) * Math.cos(half) + unitGaussian(noiseSeed, bucket, 4) * 0.008);
        float wy = (float) ((-0.07 * scale) * Math.sin(half) + unitGaussian(noiseSeed, bucket, 5) * 0.008);
        float wz = (float) ((0.045 * scale) * Math.cos(half + 0.4) + unitGaussian(noiseSeed, bucket, 6) * 0.006);

        float bax = (float) (0.02 * Math.sin(seconds * 0.07 + 0.3));
        float bay = (float) (0.015 * Math.cos(seconds * 0.05));
        float baz = (float) (0.018 * Math.sin(seconds * 0.04 + 1.1));
        float bwx = (float) (0.002 * Math.sin(seconds * 0.03));
        float bwy = (float) (0.0015 * Math.cos(seconds * 0.04));
        float bwz = (float) (0.001 * Math.sin(seconds * 0.02 + 0.6));

        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);
        double cp2 = Math.cos(pitch * 0.5);
        double sp2 = Math.sin(pitch * 0.5);
        double cr2 = Math.cos(roll * 0.5);
        double sr2 = Math.sin(roll * 0.5);
        float qw = (float) (cr2 * cp2 * cy + sr2 * sp2 * sy);
        float qx = (float) (sr2 * cp2 * cy - cr2 * sp2 * sy);
        float qy = (float) (cr2 * sp2 * cy + sr2 * cp2 * sy);
        float qz = (float) (cr2 * cp2 * sy - sr2 * sp2 * cy);

        float bx = 20.5f;
        float by = 4.0f;
        float bz = -41.0f;
        float tx = 2f * (qy * bz - qz * by);
        float ty = 2f * (qz * bx - qx * bz);
        float tz = 2f * (qx * by - qy * bx);
        float mx = (float) (bx + qw * tx + (qy * tz - qz * ty) + unitGaussian(noiseSeed, bucket, 7) * 0.15);
        float my = (float) (by + qw * ty + (qz * tx - qx * tz) + unitGaussian(noiseSeed, bucket, 8) * 0.15);
        float mz = (float) (bz + qw * tz + (qx * ty - qy * tx) + unitGaussian(noiseSeed, bucket, 9) * 0.15);

        return new Sample(ax, ay, az, gx, gy, gz, lx, ly, lz,
                bax, bay, baz, wx, wy, wz, bwx, bwy, bwz,
                qx, qy, qz, qw, mx, my, mz);
    }

    public static final class Sample {
        public final float ax, ay, az;
        public final float gx, gy, gz;
        public final float lx, ly, lz;
        public final float bax, bay, baz;
        public final float wx, wy, wz;
        public final float bwx, bwy, bwz;
        /** Android 旋转向量：x,y,z = 轴 * sin(θ/2)，w = cos(θ/2)。 */
        public final float qx, qy, qz, qw;
        public final float mx, my, mz;

        public Sample(float ax, float ay, float az,
                      float gx, float gy, float gz,
                      float lx, float ly, float lz,
                      float bax, float bay, float baz,
                      float wx, float wy, float wz,
                      float bwx, float bwy, float bwz,
                      float qx, float qy, float qz, float qw,
                      float mx, float my, float mz) {
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.lx = lx;
            this.ly = ly;
            this.lz = lz;
            this.bax = bax;
            this.bay = bay;
            this.baz = baz;
            this.wx = wx;
            this.wy = wy;
            this.wz = wz;
            this.bwx = bwx;
            this.bwy = bwy;
            this.bwz = bwz;
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            this.mx = mx;
            this.my = my;
            this.mz = mz;
        }
    }
}
