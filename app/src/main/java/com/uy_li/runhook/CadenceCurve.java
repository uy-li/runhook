package com.uy_li.runhook;

import java.util.Random;

/**
 * 时间的纯函数：同一随机种子和区间下，步频只由时间决定，不随采样次数变化。
 * 开启后约 90 秒从区间下沿平滑爬升，之后在区间内按数十秒周期缓慢漂移；
 * 关闭时用约 12 秒降回下沿，窗口结束后积分严格为 0。
 * 波形相位是频率对时间的积分，频率变化时相位连续。
 */
public final class CadenceCurve {

    public static final double WARMUP_SEC = 90.0;
    public static final double COOLDOWN_SEC = 12.0;
    public static final long COOLDOWN_MS = 12_000L;
    public static final float GRAVITY_MPS2 = 9.81f;
    public static final float Z_AMPLITUDE = 3.5f;
    public static final float Y_AMPLITUDE = 1.5f;

    private static final double[] WEIGHTS = {0.50, 0.30, 0.20};

    public enum Mode {
        STOPPED, RUNNING, COOLING
    }

    private long seed = 1L;
    private float min = 172f;
    private float max = 182f;
    private double centerFrac = 0.5;
    private double center = 172d;
    private double amplitude;
    private final double[] periods = new double[3];
    private final double[] phases = new double[3];
    private final double[] omegas = new double[3];

    private boolean running;
    /** {@link Long#MIN_VALUE} 表示还没有会话。0 是合法的起始时间。 */
    private long sessionStartMs = Long.MIN_VALUE;
    private long cooldownStartMs = -1L;
    private float cooldownFrom = 172f;

    private long cacheEndMs = Long.MIN_VALUE;
    private double cacheCadenceSeconds;

    public CadenceCurve(long seed) {
        reseed(seed);
        recomputeBand();
    }

    public void reseed(long seed) {
        this.seed = seed == 0L ? 1L : seed;
        Random random = new Random(this.seed);
        centerFrac = 0.42 + 0.16 * random.nextDouble();
        for (int i = 0; i < 3; i++) {
            phases[i] = random.nextDouble() * (Math.PI * 2.0);
        }
        periods[0] = 22.0 + 12.0 * random.nextDouble();
        periods[1] = 38.0 + 16.0 * random.nextDouble();
        periods[2] = 58.0 + 24.0 * random.nextDouble();
        for (int i = 0; i < 3; i++) {
            omegas[i] = (Math.PI * 2.0) / periods[i];
        }
        recomputeBand();
        invalidateCache();
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

    public long cooldownStartMs() {
        return cooldownStartMs;
    }

    public float cooldownFrom() {
        return cooldownFrom;
    }

    public void setRange(float min, float max) {
        if (!Float.isFinite(min) || !Float.isFinite(max)) return;
        if (max < min) {
            float swap = min;
            min = max;
            max = swap;
        }
        if (this.min == min && this.max == max) return;
        this.min = min;
        this.max = max;
        recomputeBand();
        invalidateCache();
    }

    public void start(long nowMs) {
        running = true;
        sessionStartMs = nowMs;
        cooldownStartMs = -1L;
        cooldownFrom = min;
        invalidateCache();
    }

    /** 已经在冷却则不延长。未开始时无操作。 */
    public void beginCooldown(long nowMs) {
        if (modeAt(nowMs) != Mode.RUNNING) return;
        cooldownFrom = (float) cadenceAt(nowMs);
        cooldownStartMs = nowMs;
        invalidateCache();
    }

    public void stopImmediate() {
        running = false;
        sessionStartMs = Long.MIN_VALUE;
        cooldownStartMs = -1L;
        invalidateCache();
    }

    public void restoreRunning(long seed, float min, float max, long sessionStartMs) {
        reseed(seed);
        setRange(min, max);
        this.sessionStartMs = sessionStartMs;
        this.running = sessionStartMs != Long.MIN_VALUE;
        this.cooldownStartMs = -1L;
        invalidateCache();
    }

    public Mode modeAt(long nowMs) {
        if (!running || !isStarted()) return Mode.STOPPED;
        if (nowMs < sessionStartMs) return Mode.STOPPED;
        if (cooldownStartMs >= 0L) {
            if (nowMs < cooldownStartMs) return Mode.RUNNING;
            if (nowMs < cooldownStartMs + COOLDOWN_MS) return Mode.COOLING;
            return Mode.STOPPED;
        }
        return Mode.RUNNING;
    }

    public boolean isActive(long nowMs) {
        Mode mode = modeAt(nowMs);
        return mode == Mode.RUNNING || mode == Mode.COOLING;
    }

    public double cadenceAt(long nowMs) {
        if (!isStarted()) return min;
        if (cooldownStartMs >= 0L && nowMs >= cooldownStartMs) {
            return cooldownCadence(nowMs);
        }
        double u = (nowMs - sessionStartMs) / 1000.0;
        if (u < 0.0) return min;
        return cadenceU(u);
    }

    /** 从会话起点积分得到的相位，单位弧度。冷却结束后冻结。 */
    public double phaseAt(long nowMs) {
        return (Math.PI * 2.0) * (cadenceSecondsUntil(clampedEnd(nowMs)) / 60.0);
    }

    public float waveY(long nowMs) {
        return (float) (Math.cos(phaseAt(nowMs)) * Y_AMPLITUDE);
    }

    public float waveZ(long nowMs) {
        return GRAVITY_MPS2 + (float) (Math.sin(phaseAt(nowMs)) * Z_AMPLITUDE);
    }

    /**
     * 把 {@code [lastMs, nowMs]} 里引擎确实开着的那一段换成步数。
     * lastMs &lt;= 0（进程重启后还没锚定）返回 0，避免把关机或进程死亡的空档补上。
     * 单次最多计 {@code maxGapSec} 秒，且不会越过冷却结束时刻。
     */
    public double creditedSteps(long lastMs, long nowMs, float maxGapSec) {
        if (lastMs <= 0L || nowMs <= lastMs || !isStarted()) return 0d;
        if (!isActive(lastMs)) return 0d;
        long end = nowMs;
        if (maxGapSec > 0f) {
            double maxMs = (double) maxGapSec * 1000.0;
            if ((double) (nowMs - lastMs) > maxMs) {
                end = lastMs + (long) maxMs;
            }
        }
        long activeEnd = activeUntilMs();
        if (end > activeEnd) end = activeEnd;
        if (end <= lastMs) return 0d;
        double seconds = cadenceSecondsBetween(lastMs, end);
        if (seconds <= 0d || Double.isNaN(seconds) || Double.isInfinite(seconds)) return 0d;
        return seconds / 60.0;
    }

    private long activeUntilMs() {
        if (!isStarted() || !running) return sessionStartMs;
        if (cooldownStartMs >= 0L) return cooldownStartMs + COOLDOWN_MS;
        return Long.MAX_VALUE;
    }

    private long clampedEnd(long nowMs) {
        if (!isStarted() || nowMs <= sessionStartMs) return isStarted() ? sessionStartMs : Long.MIN_VALUE;
        long end = nowMs;
        if (cooldownStartMs >= 0L) {
            long coolEnd = cooldownStartMs + COOLDOWN_MS;
            if (end > coolEnd) end = coolEnd;
        } else if (!running) {
            return sessionStartMs;
        }
        return end;
    }

    private double cadenceSecondsUntil(long endMs) {
        if (!isStarted() || endMs <= sessionStartMs) return 0d;
        if (cacheEndMs == Long.MIN_VALUE) {
            cacheCadenceSeconds = cadenceSecondsBetween(sessionStartMs, endMs);
            cacheEndMs = endMs;
            return cacheCadenceSeconds;
        }
        if (endMs == cacheEndMs) return cacheCadenceSeconds;
        if (endMs > cacheEndMs) {
            cacheCadenceSeconds += cadenceSecondsBetween(cacheEndMs, endMs);
            cacheEndMs = endMs;
            return cacheCadenceSeconds;
        }
        if (cacheEndMs - endMs <= 180_000L) {
            return cacheCadenceSeconds - cadenceSecondsBetween(endMs, cacheEndMs);
        }
        return cadenceSecondsBetween(sessionStartMs, endMs);
    }

    private double cadenceSecondsBetween(long t0, long t1) {
        if (t1 <= t0 || !isStarted()) return 0d;
        if (t0 < sessionStartMs) t0 = sessionStartMs;
        if (t1 <= t0) return 0d;
        if (cooldownStartMs >= 0L && t0 >= cooldownStartMs) {
            return integrateCooldown(t0, t1);
        }
        if (cooldownStartMs >= 0L && t1 > cooldownStartMs) {
            return integrateSession(t0, cooldownStartMs) + integrateCooldown(cooldownStartMs, t1);
        }
        return integrateSession(t0, t1);
    }

    private double integrateSession(long t0, long t1) {
        if (t0 < sessionStartMs) t0 = sessionStartMs;
        if (t1 <= t0) return 0d;
        double u0 = (t0 - sessionStartMs) / 1000.0;
        double u1 = (t1 - sessionStartMs) / 1000.0;
        return integrateU(u0, u1);
    }

    private double integrateU(double u0, double u1) {
        if (u1 <= u0) return 0d;
        double total = 0d;
        double cursor = Math.max(0d, u0);
        if (cursor < WARMUP_SEC && cursor < u1) {
            double warmupEnd = Math.min(WARMUP_SEC, u1);
            total += integrateWarmup(cursor, warmupEnd);
            cursor = warmupEnd;
        }
        if (cursor < u1) {
            total += integrateCruise(cursor, u1);
        }
        return total;
    }

    private double integrateWarmup(double a, double b) {
        double span = b - a;
        if (span <= 0d) return 0d;
        int n = (int) Math.ceil(span / 0.05);
        if (n < 1) n = 1;
        double h = span / n;
        double sum = 0.5 * cadenceU(a) + 0.5 * cadenceU(b);
        for (int i = 1; i < n; i++) {
            sum += cadenceU(a + i * h);
        }
        return sum * h;
    }

    private double integrateCruise(double a, double b) {
        double sum = center * (b - a);
        for (int i = 0; i < 3; i++) {
            double w = omegas[i];
            sum += amplitude * WEIGHTS[i]
                    * (Math.cos(w * a + phases[i]) - Math.cos(w * b + phases[i])) / w;
        }
        return sum;
    }

    private double integrateCooldown(long t0, long t1) {
        long coolEnd = cooldownStartMs + COOLDOWN_MS;
        if (t0 < cooldownStartMs) t0 = cooldownStartMs;
        if (t1 > coolEnd) t1 = coolEnd;
        if (t1 <= t0) return 0d;
        double e0 = (t0 - cooldownStartMs) / 1000.0;
        double e1 = (t1 - cooldownStartMs) / 1000.0;
        double from = cooldownFrom;
        double delta = min - from;
        return from * (e1 - e0) + delta * (smoothAntideriv(e1) - smoothAntideriv(e0));
    }

    private double smoothAntideriv(double seconds) {
        if (seconds <= 0d) return 0d;
        if (seconds >= COOLDOWN_SEC) seconds = COOLDOWN_SEC;
        double p = seconds / COOLDOWN_SEC;
        double p2 = p * p;
        return COOLDOWN_SEC * (p2 * p - 0.5 * p2 * p2);
    }

    private double cooldownCadence(long nowMs) {
        double elapsed = (nowMs - cooldownStartMs) / 1000.0;
        if (elapsed <= 0d) return clamp(cooldownFrom);
        if (elapsed >= COOLDOWN_SEC) return min;
        double s = smoothstep(elapsed / COOLDOWN_SEC);
        return clamp(cooldownFrom + (min - cooldownFrom) * s);
    }

    /** 热身把噪声一并淡入，所以全程落在 [min, max]，起点就是下沿。 */
    private double cadenceU(double u) {
        if (u < 0d) u = 0d;
        double fade = u >= WARMUP_SEC ? 1d : smoothstep(u / WARMUP_SEC);
        double noise = 0d;
        for (int i = 0; i < 3; i++) {
            noise += WEIGHTS[i] * Math.sin(omegas[i] * u + phases[i]);
        }
        double cruise = center + amplitude * noise;
        return clamp((1.0 - fade) * min + fade * cruise);
    }

    private double clamp(double value) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void recomputeBand() {
        double span = (double) max - min;
        center = min + span * centerFrac;
        amplitude = Math.min(center - min, max - center);
        if (amplitude < 0d) amplitude = 0d;
    }

    private void invalidateCache() {
        cacheEndMs = Long.MIN_VALUE;
        cacheCadenceSeconds = 0d;
    }

    private static double smoothstep(double p) {
        if (p <= 0d) return 0d;
        if (p >= 1d) return 1d;
        return p * p * (3.0 - 2.0 * p);
    }
}
