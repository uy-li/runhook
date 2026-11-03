package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AltitudeCurveTest {

    @Test
    public void pressureMatchesSensorManagerFormula() {
        float[] samples = {-400f, -100f, 0f, 30f, 100f, 500f, 2000f, 8848f};
        for (float altitude : samples) {
            float pressure = AltitudeCurve.pressureHpa(altitude);
            assertTrue(pressure > 200f && pressure < 1200f);
            float back = AltitudeCurve.altitudeFromPressure(AltitudeCurve.STANDARD_ATMOSPHERE_HPA, pressure);
            assertEquals(altitude, back, 0.35f);
        }
        assertTrue(Float.isNaN(AltitudeCurve.altitudeFromPressure(0f, 1000f)));
    }

    @Test
    public void rangeRejectsInvalidSwapsReversedAndAllowsNegatives() {
        assertTrue(AltitudeCurve.isValidAltitude(-500f));
        assertTrue(AltitudeCurve.isValidAltitude(0f));
        assertTrue(AltitudeCurve.isValidAltitude(9000f));
        assertTrue(AltitudeCurve.isValidAltitude(-0.5f));
        assertFalse(AltitudeCurve.isValidAltitude(-500.1f));
        assertFalse(AltitudeCurve.isValidAltitude(9000.1f));
        assertFalse(AltitudeCurve.isValidAltitude(Float.NaN));
        assertFalse(AltitudeCurve.isValidAltitude(Float.POSITIVE_INFINITY));

        float[] out = new float[2];
        assertFalse(AltitudeCurve.normalizeRange(10f, Float.NaN, out));
        assertFalse(AltitudeCurve.normalizeRange(-600f, 10f, out));
        assertTrue(AltitudeCurve.normalizeRange(120f, -20f, out));
        assertEquals(-20f, out[0], 0f);
        assertEquals(120f, out[1], 0f);
        assertTrue(AltitudeCurve.normalizeRange(-500f, 9000f, out));
        assertEquals(-500f, out[0], 0f);
        assertEquals(9000f, out[1], 0f);

        assertNull(ConfigStore.parseAltitude(null));
        assertNull(ConfigStore.parseAltitude(""));
        assertNull(ConfigStore.parseAltitude("  "));
        assertNull(ConfigStore.parseAltitude("abc"));
        assertNull(ConfigStore.parseAltitude("-"));
        assertNull(ConfigStore.parseAltitude("9001"));
        assertNull(ConfigStore.parseAltitude("-501"));
        assertEquals(-12.5f, ConfigStore.parseAltitude(" -12,5 "), 0.001f);
        assertEquals(8848f, ConfigStore.parseAltitude("8848"), 0.001f);
        assertEquals(-500f, ConfigStore.parseAltitude("-500"), 0.001f);
    }

    @Test
    public void driftStaysInRangeAndClimbsSlowly() {
        AltitudeCurve curve = new AltitudeCurve(17L);
        curve.setRange(-40f, 180f);
        curve.start(0L);
        double previous = curve.altitudeAt(0L);
        double lowest = previous;
        double highest = previous;
        for (long t = 1_000L; t <= 3_600_000L; t += 1_000L) {
            double altitude = curve.altitudeAt(t);
            assertTrue(altitude >= -40d - 1e-6 && altitude <= 180d + 1e-6);
            assertTrue("jump " + (altitude - previous),
                    Math.abs(altitude - previous) <= AltitudeCurve.MAX_CLIMB_MPS * 1.02 + 0.05);
            lowest = Math.min(lowest, altitude);
            highest = Math.max(highest, altitude);
            previous = altitude;
        }
        assertTrue("drift span " + (highest - lowest), highest - lowest > 1d);
    }

    @Test
    public void wideRangeIsStillRateLimitedAndDeterministic() {
        AltitudeCurve a = new AltitudeCurve(42L);
        AltitudeCurve b = new AltitudeCurve(42L);
        a.setRange(AltitudeCurve.LIMIT_LOW, AltitudeCurve.LIMIT_HIGH);
        b.setRange(AltitudeCurve.LIMIT_HIGH, AltitudeCurve.LIMIT_LOW);
        a.start(5_000L);
        b.start(5_000L);
        double previous = a.altitudeAt(5_000L);
        for (long t = 8_000L; t <= 1_200_000L; t += 3_000L) {
            double altitude = a.altitudeAt(t);
            assertEquals(altitude, b.altitudeAt(t), 0d);
            assertTrue(altitude >= AltitudeCurve.LIMIT_LOW - 1e-4);
            assertTrue(altitude <= AltitudeCurve.LIMIT_HIGH + 1e-4);
            double dt = 3.0;
            assertTrue(Math.abs(altitude - previous) <= AltitudeCurve.MAX_CLIMB_MPS * dt * 1.02 + 0.05);
            previous = altitude;
        }
        AltitudeCurve other = new AltitudeCurve(43L);
        other.setRange(AltitudeCurve.LIMIT_LOW, AltitudeCurve.LIMIT_HIGH);
        other.start(5_000L);
        boolean differs = false;
        for (long t = 60_000L; t <= 600_000L; t += 60_000L) {
            if (Math.abs(a.altitudeAt(t) - other.altitudeAt(t)) > 0.5) differs = true;
        }
        assertTrue(differs);
    }

    @Test
    public void equalRangeIsConstantAndSpoofRequiresBothSwitches() {
        AltitudeCurve curve = new AltitudeCurve(1L);
        curve.setRange(25f, 25f);
        curve.start(0L);
        assertEquals(25d, curve.altitudeAt(0L), 1e-6);
        assertEquals(25d, curve.altitudeAt(600_000L), 1e-6);
        curve.stop();
        assertEquals(25d, curve.altitudeAt(600_000L), 1e-6);

        assertTrue(AltitudeCurve.shouldSpoof(true, true));
        assertFalse(AltitudeCurve.shouldSpoof(true, false));
        assertFalse(AltitudeCurve.shouldSpoof(false, true));
        assertFalse(AltitudeCurve.shouldSpoof(false, false));
    }
}