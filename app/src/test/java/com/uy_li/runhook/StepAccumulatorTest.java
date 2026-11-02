package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StepAccumulatorTest {

    @Test
    public void disabledDoesNotIncreaseAndDoesNotTouchRealValue() {
        StepAccumulator acc = new StepAccumulator();
        acc.advance(1000f, 1_000L, false, 3f);
        acc.advance(1000f, 61_000L, false, 3f);

        assertEquals(0f, acc.boost, 0.001f);
        assertFalse(acc.shouldRewrite());
        assertEquals(1000f, acc.reported(1000f), 0.001f);
    }

    @Test
    public void enabledAddsCadenceWithoutCountingTimeSpentOff() {
        StepAccumulator acc = new StepAccumulator();
        acc.advance(1000f, 1_000L, false, 2f);
        acc.lastUpdateMs = 1_000L;
        // 模拟重新打开时把时间基准拨到现在，关闭期间的 100 秒不能补上。
        acc.lastUpdateMs = 101_000L;
        acc.advance(1000f, 111_000L, true, 2f);

        assertEquals(20f, acc.boost, 0.001f);
        assertTrue(acc.shouldRewrite());
        assertEquals(1020f, acc.reported(1000f), 0.001f);
    }

    @Test
    public void hardwareResetDropsBoostButSmallJitterDoesNot() {
        StepAccumulator acc = new StepAccumulator();
        acc.advance(5000f, 1_000L, true, 2f);
        acc.advance(5000f, 11_000L, true, 2f);
        assertTrue(acc.boost > 0f);

        boolean jitterReset = acc.advance(4999.5f, 12_000L, true, 2f);
        assertFalse(jitterReset);
        assertTrue(acc.boost > 0f);

        boolean reset = acc.advance(10f, 13_000L, false, 2f);
        assertTrue(reset);
        assertEquals(0f, acc.boost, 0.001f);
        assertFalse(acc.shouldRewrite());
        assertEquals(10f, acc.reported(10f), 0.001f);
    }

    @Test
    public void heldOffsetSurvivesWhileOffSoValueDoesNotFall() {
        StepAccumulator acc = new StepAccumulator();
        acc.advance(1000f, 1_000L, true, 2f);
        acc.advance(1000f, 11_000L, true, 2f);
        float boosted = acc.reported(1000f);

        acc.advance(1100f, 71_000L, false, 2f);
        assertEquals(boosted - 1000f, acc.boost, 0.001f);
        assertEquals(1100f + acc.boost, acc.reported(1100f), 0.001f);
        assertTrue(acc.reported(1100f) > 1100f);
    }

    @Test
    public void singleGapIsCapped() {
        StepAccumulator acc = new StepAccumulator();
        acc.advance(100f, 1_000L, true, 1f);
        acc.advance(100f, 1_000L + (long) ((StepAccumulator.MAX_GAP_SEC + 3600f) * 1000f), true, 1f);
        assertEquals(StepAccumulator.MAX_GAP_SEC, acc.boost, 0.1f);
    }

    @Test
    public void cadenceKeepsAnyPositiveValueAndRejectsInvalid() {
        assertEquals(10f, ConfigStore.sanitizeCadence(10f, 172f), 0.001f);
        assertEquals(400f, ConfigStore.sanitizeCadence(400f, 172f), 0.001f);
        assertEquals(180f, ConfigStore.sanitizeCadence(180f, 172f), 0.001f);
        assertEquals(172f, ConfigStore.sanitizeCadence(Float.NaN, 172f), 0.001f);
        assertEquals(172f, ConfigStore.sanitizeCadence(Float.POSITIVE_INFINITY, 172f), 0.001f);
        assertEquals(172f, ConfigStore.sanitizeCadence(0f, 172f), 0.001f);
        assertEquals(172f, ConfigStore.sanitizeCadence(-5f, 172f), 0.001f);
        assertEquals(ConfigStore.DEFAULT_MIN, ConfigStore.sanitizeCadence(Float.NaN, Float.NaN), 0.001f);

        assertNull(ConfigStore.parseCadence(null));
        assertNull(ConfigStore.parseCadence(""));
        assertNull(ConfigStore.parseCadence("  "));
        assertNull(ConfigStore.parseCadence("abc"));
        assertNull(ConfigStore.parseCadence("0"));
        assertNull(ConfigStore.parseCadence("-2"));
        assertEquals(50f, ConfigStore.parseCadence("50"), 0.001f);
        assertEquals(175.5f, ConfigStore.parseCadence(" 175.5 "), 0.001f);
    }
}
