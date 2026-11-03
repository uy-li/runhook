package com.uy_li.runhook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按 {@link CadenceCurve} 主动生成计步事件。没有真实传感器回调时步数也会涨。
 * 真实计步器读数到来时只补上尚未积分的那一段时间，不会把同一段时间算两次。
 * 计步器是硬件基线加上累计偏移；计步检测器每个合成步发一次 1.0。
 * 时间戳使用调用方传入的 elapsedRealtimeNanos，严格递增。
 *
 * 屏幕灭或 maxReportLatency 较大时先攒着，吐出的时间戳仍落在采样时刻而不是吐出时刻。
 * 调用方需要保证同一实例不会并发进入这些方法；本类用 {@link EngineLock} 串行化。
 */
public final class StepEventScheduler {

    public enum Kind {
        DETECTOR, COUNTER
    }

    public static final class Planned {
        public final Kind kind;
        public final long timestampNs;
        public final float value;

        public Planned(Kind kind, long timestampNs, float value) {
            this.kind = kind;
            this.timestampNs = timestampNs;
            this.value = value;
        }
    }

    private static final long SCREEN_OFF_BATCH_US = 1_000_000L;
    private static final long MAX_BATCH_US = 5_000_000L;
    private static final int MAX_STEPS_PER_POLL = 20_000;
    private static final long HARDWARE_WAIT_MS = 2_000L;

    private final CadenceCurve curve;
    private final StepAccumulator accumulator;
    private final ArrayList<Planned> held = new ArrayList<>();

    private long lastIntegratedMs;
    private float hardware;
    private boolean hardwareSeen;
    private long noHardwareSince;
    private double detectorCredit;
    private long lastTimestampNs;
    private float lastCounter = Float.NaN;
    private long lastCounterEmitNs;
    private long batchStartNs = -1L;
    private boolean screenWasInteractive = true;
    private boolean resetFlag;
    private long emittedDetectors;

    public StepEventScheduler(CadenceCurve curve, StepAccumulator accumulator) {
        this.curve = curve;
        this.accumulator = accumulator;
    }

    /** 新会话从现在起算，关掉或进程死亡的空档不补步。 */
    public void anchor(long nowMs) {
        synchronized (EngineLock.STATE) {
            if (nowMs <= 0L) return;
            if (lastIntegratedMs < nowMs) lastIntegratedMs = nowMs;
            if (accumulator.lastUpdateMs < nowMs) accumulator.lastUpdateMs = nowMs;
            held.clear();
            batchStartNs = -1L;
            if (detectorCredit > 0.999) detectorCredit = 0.999;
        }
    }

    public void noteHardware(float hardwareValue, long nowMs) {
        synchronized (EngineLock.STATE) {
            applyHardware(hardwareValue, nowMs);
        }
    }

    /**
     * 真实 TYPE_STEP_COUNTER。返回应写回事件的读数（硬件基线 + 偏移）。
     * 硬件计数回退（重启）时清掉偏移，这时允许读数变小。
     */
    public float onRealCounter(float hardwareValue, long timestampNs, long nowMs) {
        synchronized (EngineLock.STATE) {
            if (timestampNs == Long.MIN_VALUE) return reportedLocked();
            if (!applyHardware(hardwareValue, nowMs)) {
                return reportedLocked();
            }
            accrue(nowMs);
            return publishCounter(accumulator.reported(hardware));
        }
    }

    public boolean consumeReset() {
        synchronized (EngineLock.STATE) {
            boolean value = resetFlag;
            resetFlag = false;
            return value;
        }
    }

    public long monotonicTimestamp(long timestampNs) {
        synchronized (EngineLock.STATE) {
            return monotonicTimestampLocked(timestampNs);
        }
    }

    public float reported() {
        synchronized (EngineLock.STATE) {
            return reportedLocked();
        }
    }

    public boolean hardwareSeen() {
        synchronized (EngineLock.STATE) {
            return hardwareSeen;
        }
    }

    public long emittedDetectors() {
        synchronized (EngineLock.STATE) {
            return emittedDetectors;
        }
    }

    /**
     * @param samplingPeriodUs 小于等于 0 时每个步都带一条计步器事件
     * @param maxReportLatencyUs 屏幕亮着时的攒包上限；灭屏至少攒 1 秒，最多 5 秒
     */
    public List<Planned> poll(long nowMs, long nowNs, boolean screenInteractive,
                              int samplingPeriodUs, int maxReportLatencyUs) {
        synchronized (EngineLock.STATE) {
            if (nowNs <= 0L) nowNs = nowMs * 1_000_000L;
            long fromMs = lastIntegratedMs;
            accrue(nowMs);
            promoteHardwareIfWaited(nowMs);
            long fromNs = nowNs - Math.max(0L, nowMs - Math.max(fromMs, 0L)) * 1_000_000L;
            if (fromNs < 0L) fromNs = 0L;
            if (lastTimestampNs > fromNs) fromNs = lastTimestampNs;
            generate(fromNs, nowNs, samplingPeriodUs);

            boolean becameInteractive = screenInteractive && !screenWasInteractive;
            screenWasInteractive = screenInteractive;
            long latencyUs = maxReportLatencyUs;
            if (latencyUs < 0L) latencyUs = 0L;
            if (!screenInteractive && latencyUs < SCREEN_OFF_BATCH_US) latencyUs = SCREEN_OFF_BATCH_US;
            if (latencyUs > MAX_BATCH_US) latencyUs = MAX_BATCH_US;

            if (held.isEmpty()) {
                batchStartNs = -1L;
                return Collections.emptyList();
            }
            if (batchStartNs < 0L) batchStartNs = nowNs;
            boolean inactive = !curve.isActive(nowMs);
            boolean due = latencyUs == 0L
                    || becameInteractive
                    || inactive
                    || nowNs - batchStartNs >= latencyUs * 1000L;
            if (!due) return Collections.emptyList();

            List<Planned> out = new ArrayList<>(held);
            held.clear();
            batchStartNs = -1L;
            return out;
        }
    }

    /** @return false 表示这次是硬件复位，调用方不要再积分。 */
    private boolean applyHardware(float hardwareValue, long nowMs) {
        if (!Float.isFinite(hardwareValue)) return false;
        if (!hardwareSeen) {
            hardware = hardwareValue;
            hardwareSeen = true;
            noHardwareSince = 0L;
            accumulator.lastReal = hardwareValue;
            if (lastIntegratedMs <= 0L) {
                lastIntegratedMs = accumulator.lastUpdateMs > 0L ? accumulator.lastUpdateMs : nowMs;
            }
            return true;
        }
        if (hardwareValue + 1f < hardware) {
            accumulator.advanceIntegrated(hardwareValue, nowMs, false, 0d);
            hardware = hardwareValue;
            detectorCredit = 0d;
            held.clear();
            batchStartNs = -1L;
            lastCounter = Float.NaN;
            lastCounterEmitNs = 0L;
            lastIntegratedMs = nowMs;
            resetFlag = true;
            return false;
        }
        hardware = hardwareValue;
        accumulator.lastReal = hardwareValue;
        return true;
    }

    private void accrue(long nowMs) {
        if (lastIntegratedMs <= 0L) {
            lastIntegratedMs = accumulator.lastUpdateMs > 0L ? accumulator.lastUpdateMs : nowMs;
        }
        if (nowMs <= lastIntegratedMs) return;
        long from = lastIntegratedMs;
        boolean count = curve.isActive(from);
        double steps = count ? curve.creditedSteps(from, nowMs, StepAccumulator.MAX_GAP_SEC) : 0d;
        float real = hardwareSeen ? hardware : 0f;
        accumulator.advanceIntegrated(real, nowMs, count, steps);
        if (count && steps > 0d && !Double.isNaN(steps) && !Double.isInfinite(steps)) {
            detectorCredit += steps;
        }
        lastIntegratedMs = nowMs;
    }

    private void promoteHardwareIfWaited(long nowMs) {
        if (hardwareSeen) return;
        if (noHardwareSince == 0L) noHardwareSince = nowMs;
        if (nowMs - noHardwareSince < HARDWARE_WAIT_MS) return;
        hardware = 0f;
        hardwareSeen = true;
        accumulator.lastReal = 0f;
    }

    private void generate(long fromNs, long nowNs, int samplingPeriodUs) {
        if (!hardwareSeen || detectorCredit < 1d) return;
        int whole = (int) Math.floor(detectorCredit + 1e-9);
        if (whole > MAX_STEPS_PER_POLL) whole = MAX_STEPS_PER_POLL;
        if (whole <= 0) return;
        detectorCredit -= whole;

        float end = accumulator.reported(hardware);
        float start = Float.isNaN(lastCounter) ? hardware : lastCounter;
        if (end < start) end = start;
        long span = nowNs - fromNs;
        if (span < whole) span = whole;
        if (batchStartNs < 0L) batchStartNs = fromNs > 0L ? fromNs : nowNs;

        for (int i = 1; i <= whole; i++) {
            long ts = fromNs + (span * i) / whole;
            if (ts <= lastTimestampNs) ts = lastTimestampNs + 1_000L;
            lastTimestampNs = ts;
            held.add(new Planned(Kind.DETECTOR, ts, 1f));
            emittedDetectors++;

            float value = (float) (start + (end - start) * (i / (double) whole));
            if (!Float.isNaN(lastCounter) && value < lastCounter) value = lastCounter;
            long periodNs = samplingPeriodUs <= 0 ? 0L : (long) samplingPeriodUs * 1000L;
            boolean emitCounter = periodNs <= 0L
                    || lastCounterEmitNs == 0L
                    || ts - lastCounterEmitNs >= periodNs
                    || i == whole;
            if (!emitCounter) continue;
            long counterTs = ts + 1L;
            if (counterTs <= lastTimestampNs) counterTs = lastTimestampNs + 1L;
            lastTimestampNs = counterTs;
            lastCounter = value;
            lastCounterEmitNs = counterTs;
            held.add(new Planned(Kind.COUNTER, counterTs, value));
        }
    }

    private float publishCounter(float value) {
        if (!Float.isFinite(value)) return reportedLocked();
        if (Float.isNaN(lastCounter) || value + 1e-3f >= lastCounter) {
            lastCounter = value;
            return value;
        }
        return lastCounter;
    }

    private float reportedLocked() {
        float base = hardwareSeen ? hardware : 0f;
        float value = accumulator.reported(base);
        if (!Float.isNaN(lastCounter) && value < lastCounter) return lastCounter;
        return value;
    }

    private long monotonicTimestampLocked(long timestampNs) {
        if (timestampNs <= lastTimestampNs) {
            timestampNs = lastTimestampNs + 1_000L;
            if (timestampNs < 0L) timestampNs = lastTimestampNs + 1L;
        }
        lastTimestampNs = timestampNs;
        return timestampNs;
    }
}
