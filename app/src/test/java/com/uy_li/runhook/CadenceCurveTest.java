package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CadenceCurveTest {

    private static final float MIN = 160f;
    private static final float MAX = 190f;
    private static final float MAX_GAP = StepAccumulator.MAX_GAP_SEC;

    private static CadenceCurve running(long seed, long startMs) {
        CadenceCurve curve = new CadenceCurve(seed);
        curve.setRange(MIN, MAX);
        curve.start(startMs);
        return curve;
    }

    @Test
    public void staysInsideRangeThroughWarmupCruiseAndCooldown() {
        CadenceCurve curve = running(7L, 1_000L);
        for (long t = 1_000L; t <= 1_000L + 240_000L; t += 250L) {
            double cadence = curve.cadenceAt(t);
            assertTrue("cadence " + cadence + " at " + t, cadence >= MIN - 1e-6 && cadence <= MAX + 1e-6);
        }
        curve.beginCooldown(241_000L);
        for (long t = 241_000L; t <= 241_000L + CadenceCurve.COOLDOWN_MS; t += 200L) {
            double cadence = curve.cadenceAt(t);
            assertTrue(cadence >= MIN - 1e-6 && cadence <= MAX + 1e-6);
        }
    }

    @Test
    public void warmupStartsAtTheFloorAndRisesIntoTheRange() {
        CadenceCurve curve = running(11L, 0L);
        assertEquals(MIN, curve.cadenceAt(0L), 0.02);
        double early = average(curve, 0L, 12_000L);
        double late = average(curve, 68_000L, 90_000L);
        assertTrue("early " + early + " late " + late, late > early);
        assertTrue(late - early > 0.15 * (MAX - MIN));
    }

    @Test
    public void driftIsContinuousAndHasNoJumps() {
        CadenceCurve curve = running(21L, 5_000L);
        double previous = curve.cadenceAt(5_000L);
        double maxStep = 0d;
        for (long t = 5_050L; t <= 5_000L + 180_000L; t += 50L) {
            double cadence = curve.cadenceAt(t);
            maxStep = Math.max(maxStep, Math.abs(cadence - previous));
            previous = cadence;
        }
        // 50ms 内的变化远小于区间宽度，排除阶跃。
        assertTrue("max 50ms step " + maxStep, maxStep < Math.max(0.35, (MAX - MIN) * 0.02));
    }

    @Test
    public void sameSeedIsDeterministicAndIndependentOfQueryOrder() {
        CadenceCurve forward = running(99L, 0L);
        CadenceCurve backward = running(99L, 0L);
        double[] times = {0d, 1_000d, 30_000d, 90_000d, 150_000d};
        for (int i = times.length - 1; i >= 0; i--) {
            long t = (long) times[i];
            assertEquals(forward.cadenceAt(t), backward.cadenceAt(t), 0d);
            assertEquals(forward.phaseAt(t), backward.phaseAt(t), 1e-8);
            assertEquals(forward.creditedSteps(1_000L, t, MAX_GAP),
                    backward.creditedSteps(1_000L, t, MAX_GAP), 1e-6);
        }
        CadenceCurve other = running(100L, 0L);
        boolean differs = false;
        for (long t = 20_000L; t <= 160_000L; t += 10_000L) {
            if (Math.abs(forward.cadenceAt(t) - other.cadenceAt(t)) > 0.05) differs = true;
        }
        assertTrue(differs);
    }

    @Test
    public void phaseIsTheIntegralOfFrequency() {
        CadenceCurve curve = running(3L, 10_000L);
        long t0 = 20_000L;
        long t1 = 55_000L;
        double steps = curve.creditedSteps(t0, t1, MAX_GAP);
        assertEquals(curve.phaseAt(t0) + Math.PI * 2.0 * steps, curve.phaseAt(t1), 1e-4);

        double phaseDelta = curve.phaseAt(t0 + 1_000L) - curve.phaseAt(t0);
        double expected = Math.PI * 2.0 * (curve.cadenceAt(t0 + 500L) / 60.0);
        assertEquals(expected, phaseDelta, Math.abs(expected) * 0.08 + 1e-4);
        assertTrue(curve.phaseAt(t1) > curve.phaseAt(t0));
    }

    @Test
    public void stepsMatchSampledCadenceAndSplitGapsAddUp() {
        CadenceCurve curve = running(5L, 0L);
        long t0 = 30_000L;
        long t1 = 60_000L;
        double credited = curve.creditedSteps(t0, t1, MAX_GAP);
        double sampled = 0d;
        long step = 200L;
        for (long t = t0; t < t1; t += step) {
            long next = Math.min(t1, t + step);
            double mid = curve.cadenceAt(t + (next - t) / 2L);
            sampled += mid / 60.0 * ((next - t) / 1000.0);
        }
        assertEquals(sampled, credited, 0.15);

        double oneShot = curve.creditedSteps(t0, t0 + 30_000L, MAX_GAP);
        double split = curve.creditedSteps(t0, t0 + 7_000L, MAX_GAP)
                + curve.creditedSteps(t0 + 7_000L, t0 + 30_000L, MAX_GAP);
        assertEquals(oneShot, split, 1e-4);
    }

    @Test
    public void gapIsCappedAndUnanchoredOrStoppedIntervalsAddNothing() {
        CadenceCurve curve = running(8L, 1_000L);
        long start = 120_000L;
        double capped = curve.creditedSteps(start, start + (long) (MAX_GAP * 1000f), MAX_GAP);
        double longer = curve.creditedSteps(start, start + (long) ((MAX_GAP + 3600f) * 1000f), MAX_GAP);
        assertEquals(capped, longer, 1e-3);
        assertTrue(capped > 0d);
        assertEquals(0d, curve.creditedSteps(0L, start + 10_000L, MAX_GAP), 0d);
        assertEquals(0d, curve.creditedSteps(-5L, start + 10_000L, MAX_GAP), 0d);

        curve.stopImmediate();
        assertEquals(0d, curve.creditedSteps(start, start + 60_000L, MAX_GAP), 0d);
        assertFalse(curve.isActive(start + 1_000L));
    }

    @Test
    public void cooldownRampsDownThenStepsStopEvenAcrossALongGap() {
        CadenceCurve curve = running(13L, 0L);
        long off = 150_000L;
        double before = curve.cadenceAt(off);
        curve.beginCooldown(off);
        assertEquals(CadenceCurve.Mode.COOLING, curve.modeAt(off + 1_000L));
        double mid = curve.cadenceAt(off + CadenceCurve.COOLDOWN_MS / 2L);
        double end = curve.cadenceAt(off + CadenceCurve.COOLDOWN_MS - 1L);
        assertTrue(mid <= before + 0.05);
        assertTrue(end <= mid + 0.05);
        assertEquals(MIN, end, (MAX - MIN) * 0.2 + 0.5);

        double during = curve.creditedSteps(off, off + 3_600_000L, MAX_GAP);
        double onlyWindow = curve.creditedSteps(off, off + CadenceCurve.COOLDOWN_MS, MAX_GAP);
        assertEquals(onlyWindow, during, 1e-4);
        assertTrue(during > 0d);
        double ceiling = (before / 60.0) * CadenceCurve.COOLDOWN_SEC + 0.05;
        assertTrue("cooldown steps " + during, during <= ceiling);

        long after = off + CadenceCurve.COOLDOWN_MS + 1_000L;
        assertEquals(CadenceCurve.Mode.STOPPED, curve.modeAt(after));
        assertFalse(curve.isActive(after));
        assertEquals(0d, curve.creditedSteps(after, after + 86_400_000L, MAX_GAP), 0d);

        double frozen = curve.phaseAt(after);
        assertEquals(frozen, curve.phaseAt(after + 60_000L), 1e-6);
        // 重复关闭不能把冷却窗口再拉长。
        curve.beginCooldown(after + 5_000L);
        assertEquals(0d, curve.creditedSteps(after, after + 30_000L, MAX_GAP), 0d);
    }

    @Test
    public void equalBoundsStayConstantAndReversedRangeIsSwapped() {
        CadenceCurve curve = new CadenceCurve(1L);
        curve.setRange(175f, 175f);
        curve.start(0L);
        assertEquals(175d, curve.cadenceAt(0L), 1e-6);
        assertEquals(175d, curve.cadenceAt(120_000L), 1e-6);

        curve.setRange(200f, 150f);
        curve.start(0L);
        for (long t = 0L; t <= 30_000L; t += 1_000L) {
            double cadence = curve.cadenceAt(t);
            assertTrue(cadence >= 150d - 1e-6 && cadence <= 200d + 1e-6);
        }
    }

    @Test
    public void integratedStepsDoNotAccumulateWhileTheEngineIsOff() {
        StepAccumulator acc = new StepAccumulator();
        CadenceCurve curve = running(4L, 1_000L);
        acc.lastUpdateMs = 1_000L;
        long now = 21_000L;
        acc.advanceIntegrated(500f, now, true, curve.creditedSteps(1_000L, now, MAX_GAP));
        assertTrue(acc.boost > 10f);
        float held = acc.boost;

        curve.beginCooldown(now);
        acc.advanceIntegrated(500f, now + 3_600_000L, true,
                curve.creditedSteps(now, now + 3_600_000L, MAX_GAP));
        assertTrue(acc.boost > held);
        assertTrue(acc.boost - held < (MAX / 60f) * CadenceCurve.COOLDOWN_SEC + 1f);
        float afterCooldown = acc.boost;

        long off = now + 3_600_000L;
        assertFalse(curve.isActive(off));
        acc.advanceIntegrated(500f, off + 120_000L, curve.isActive(off),
                curve.creditedSteps(off, off + 120_000L, MAX_GAP));
        assertEquals(afterCooldown, acc.boost, 0.001f);

        acc.advanceIntegrated(500f, off + 240_000L, false, 10_000d);
        assertEquals(afterCooldown, acc.boost, 0.001f);
        assertEquals(500f + afterCooldown, acc.reported(500f), 0.001f);
    }

    private static double average(CadenceCurve curve, long from, long to) {
        double sum = 0d;
        int n = 0;
        for (long t = from; t < to; t += 500L) {
            sum += curve.cadenceAt(t);
            n++;
        }
        return sum / n;
    }
}
