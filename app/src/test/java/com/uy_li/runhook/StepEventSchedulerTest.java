package com.uy_li.runhook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepEventSchedulerTest {

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
    public void stepCountMatchesIntegratedCadenceAndTimestampsStayMonotonic() {
        CadenceCurve curve = running(9L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 1_000L;
        sched.anchor(t0);
        assertEquals(2000f, sched.onRealCounter(2000f, 1_000L, t0), 0.01f);

        long t1 = t0 + 30_000L;
        List<StepEventScheduler.Planned> events = sched.poll(t1, t1 * 1_000_000L, true, 0, 0);
        double expected = curve.creditedSteps(t0, t1, MAX_GAP);
        assertEquals(expected, acc.boost, 0.05f);
        assertEquals(expected, count(events, StepEventScheduler.Kind.DETECTOR), 1.01);
        assertEquals(expected, lastCounter(events) - 2000f, 0.05f);
        assertMonotonic(events);
        assertTrue(events.get(events.size() - 1).timestampNs <= t1 * 1_000_000L + 5_000_000L);
    }

    @Test
    public void realCounterInterleaveDoesNotDoubleCount() {
        CadenceCurve curve = running(4L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 5_000L;
        sched.anchor(t0);
        sched.onRealCounter(2000f, 10_000L, t0);

        long t1 = t0 + 10_000L;
        List<StepEventScheduler.Planned> first = sched.poll(t1, t1 * 1_000_000L, true, 0, 0);
        float mid = lastCounter(first);
        double expectedFirst = curve.creditedSteps(t0, t1, MAX_GAP);
        assertEquals(expectedFirst, mid - 2000f, 0.05f);
        assertEquals(expectedFirst, acc.boost, 0.05f);

        float again = sched.onRealCounter(2000f, t1 * 1_000_000L, t1);
        assertEquals(mid, again, 0.05f);
        assertEquals(expectedFirst, acc.boost, 0.05f);

        float walked = sched.onRealCounter(2004f, t1 * 1_000_000L + 10L, t1);
        assertEquals(mid + 4f, walked, 0.05f);
        assertEquals(expectedFirst, acc.boost, 0.05f);

        long t2 = t1 + 10_000L;
        List<StepEventScheduler.Planned> second = sched.poll(t2, t2 * 1_000_000L, true, 0, 0);
        double expectedSecond = curve.creditedSteps(t1, t2, MAX_GAP);
        assertEquals(expectedFirst + expectedSecond, acc.boost, 0.05f);
        assertEquals(walked + (float) expectedSecond, lastCounter(second), 0.05f);
        assertEquals(expectedSecond, count(second, StepEventScheduler.Kind.DETECTOR), 1.01);

        List<StepEventScheduler.Planned> all = new ArrayList<>(first);
        all.addAll(second);
        assertMonotonic(all);
        float previous = 2000f;
        for (StepEventScheduler.Planned event : all) {
            if (event.kind != StepEventScheduler.Kind.COUNTER) continue;
            assertTrue(event.value + 1e-3f >= previous);
            previous = event.value;
        }
        assertTrue(walked + 1e-3f >= previous - (float) expectedSecond - 0.05f || walked <= previous);
    }

    @Test
    public void deadProcessGapIsNotBackfilled() {
        CadenceCurve curve = running(8L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long woke = 100_000L;
        sched.anchor(woke);
        sched.onRealCounter(50f, 1L, woke);
        long later = woke + 10_000L;
        sched.poll(later, later * 1_000_000L, true, 0, 0);
        double onlyAlive = curve.creditedSteps(woke, later, MAX_GAP);
        double fromBoot = curve.creditedSteps(1_000L, later, MAX_GAP);
        assertEquals(onlyAlive, acc.boost, 0.05f);
        assertTrue(fromBoot > onlyAlive * 2f);
    }

    @Test
    public void longGapIsCappedAtTwelveHours() {
        CadenceCurve curve = running(3L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 1_000L;
        sched.anchor(t0);
        sched.onRealCounter(10f, 1L, t0);
        long far = t0 + (long) ((MAX_GAP + 3600f) * 1000f);
        sched.poll(far, far * 1_000_000L, true, 0, 0);
        assertEquals(curve.creditedSteps(t0, far, MAX_GAP), acc.boost, 0.5f);
        assertTrue(acc.boost <= MAX_GAP * (MAX / 60f) + 1f);
    }

    @Test
    public void cooldownStopsFurtherSteps() {
        CadenceCurve curve = running(13L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 1_000L;
        sched.anchor(t0);
        sched.onRealCounter(800f, 1L, t0);
        long off = t0 + 20_000L;
        sched.poll(off, off * 1_000_000L, true, 0, 0);
        float before = sched.reported();
        curve.beginCooldown(off);

        long coolEnd = off + CadenceCurve.COOLDOWN_MS;
        List<StepEventScheduler.Planned> during = sched.poll(coolEnd, coolEnd * 1_000_000L, true, 0, 0);
        assertTrue(count(during, StepEventScheduler.Kind.DETECTOR) > 0);
        float mid = sched.reported();
        assertTrue(mid > before + 1f);

        long later = coolEnd + 30_000L;
        List<StepEventScheduler.Planned> after = sched.poll(later, later * 1_000_000L, true, 0, 0);
        assertEquals(0, count(after, StepEventScheduler.Kind.DETECTOR), 0.01);
        assertEquals(mid, sched.reported(), 0.02f);
        assertEquals(acc.boost, acc.boost, 0f);
        float frozen = acc.boost;
        sched.poll(later + 60_000L, (later + 60_000L) * 1_000_000L, true, 0, 0);
        assertEquals(frozen, acc.boost, 0.001f);
    }

    @Test
    public void screenOffBatchesHistoricalTimestampsWithoutLosingSteps() {
        CadenceCurve curve = running(21L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 120_000L;
        sched.anchor(t0);
        sched.onRealCounter(3000f, 1L, t0);

        int early = 0;
        long cursor = t0;
        for (int i = 0; i < 4; i++) {
            cursor += 200L;
            early += count(sched.poll(cursor, cursor * 1_000_000L, false, 0, 0),
                    StepEventScheduler.Kind.DETECTOR);
        }
        assertEquals(0, early);

        List<StepEventScheduler.Planned> flushed = new ArrayList<>();
        long end = t0 + 3_000L;
        while (cursor < end) {
            cursor += 200L;
            flushed.addAll(sched.poll(cursor, cursor * 1_000_000L, false, 0, 0));
        }
        flushed.addAll(sched.poll(cursor, cursor * 1_000_000L, true, 0, 0));
        int detectors = count(flushed, StepEventScheduler.Kind.DETECTOR);
        double expected = curve.creditedSteps(t0, cursor, MAX_GAP);
        assertEquals(expected, detectors, 2.0);
        assertEquals(expected, acc.boost, 0.05f);
        assertTrue(detectors > 0);
        assertMonotonic(flushed);
        long flushNow = cursor * 1_000_000L;
        boolean historical = false;
        for (StepEventScheduler.Planned event : flushed) {
            assertTrue(event.timestampNs <= flushNow + 2_000_000L);
            if (event.timestampNs < flushNow - 500_000_000L) historical = true;
        }
        assertTrue(historical);
        float previous = 3000f;
        for (StepEventScheduler.Planned event : flushed) {
            if (event.kind != StepEventScheduler.Kind.COUNTER) continue;
            assertTrue("counter " + event.value + " after " + previous, event.value + 1e-3f >= previous);
            previous = event.value;
        }
    }

    @Test
    public void hardwareResetClearsBoostAndAllowsTheCounterToDrop() {
        CadenceCurve curve = running(2L, 0L);
        StepAccumulator acc = new StepAccumulator();
        StepEventScheduler sched = new StepEventScheduler(curve, acc);
        long t0 = 2_000L;
        sched.anchor(t0);
        sched.onRealCounter(500f, 1L, t0);
        sched.poll(t0 + 8_000L, (t0 + 8_000L) * 1_000_000L, true, 0, 0);
        assertTrue(acc.boost > 1f);

        float dropped = sched.onRealCounter(4f, 50_000L, t0 + 9_000L);
        assertTrue(sched.consumeReset());
        assertEquals(0f, acc.boost, 0.001f);
        assertEquals(4f, dropped, 0.01f);
        assertEquals(4f, sched.reported(), 0.01f);
    }

    @Test
    public void timestampsNeverMoveBackwards() {
        CadenceCurve curve = running(1L, 0L);
        StepEventScheduler sched = new StepEventScheduler(curve, new StepAccumulator());
        long first = sched.monotonicTimestamp(5_000_000L);
        long older = sched.monotonicTimestamp(1_000L);
        long same = sched.monotonicTimestamp(first);
        assertTrue(older > first);
        assertTrue(same > older);
    }

    private static int count(List<StepEventScheduler.Planned> events, StepEventScheduler.Kind kind) {
        int n = 0;
        for (StepEventScheduler.Planned event : events) {
            if (event.kind == kind) n++;
        }
        return n;
    }

    private static float lastCounter(List<StepEventScheduler.Planned> events) {
        float value = Float.NaN;
        for (StepEventScheduler.Planned event : events) {
            if (event.kind == StepEventScheduler.Kind.COUNTER) value = event.value;
        }
        return value;
    }

    private static void assertMonotonic(List<StepEventScheduler.Planned> events) {
        long previous = Long.MIN_VALUE;
        for (StepEventScheduler.Planned event : events) {
            assertTrue(event.timestampNs + " after " + previous, event.timestampNs > previous);
            previous = event.timestampNs;
        }
    }
}
