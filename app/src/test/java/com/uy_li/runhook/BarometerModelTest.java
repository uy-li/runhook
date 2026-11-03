package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BarometerModelTest {

    @Test
    public void pressureStaysOnTheStandardAtmosphereWithContinuousDrift() {
        BarometerModel model = new BarometerModel(42L);
        float altitude = 120f;
        float base = AltitudeCurve.pressureHpa(altitude);
        assertEquals(model.pressureHpa(altitude, 1234L), model.pressureHpa(altitude, 1234L), 0f);

        float previous = model.pressureHpa(altitude, 0L);
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        for (long t = 0; t <= 60_000L; t += 50L) {
            float pressure = model.pressureHpa(altitude, t);
            float back = AltitudeCurve.altitudeFromPressure(AltitudeCurve.STANDARD_ATMOSPHERE_HPA, pressure);
            assertEquals(altitude, back, 1.0f);
            assertEquals(pressure, model.pressureHpa(altitude, t + 1), 0.002f);
            if (t > 0) assertTrue(Math.abs(pressure - previous) < 0.12f);
            double delta = pressure - base;
            sum += delta;
            sumSq += delta * delta;
            n++;
            previous = pressure;
        }
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        assertTrue(variance > 1.0e-6);
        assertTrue(variance < 0.01);
    }

    @Test
    public void gpsAndMslTrackTheSameTargetWithIndependentNoise() {
        BarometerModel model = new BarometerModel(8L);
        double altitude = 86.5;
        boolean differ = false;
        for (long t = 0; t <= 30_000L; t += 200L) {
            double gps = model.gpsAltitudeMeters(altitude, t);
            double msl = model.mslAltitudeMeters(altitude, t);
            assertEquals(altitude, gps, 4.0);
            assertEquals(altitude, msl, 4.0);
            float pressure = model.pressureHpa(altitude, t);
            float fromPressure = AltitudeCurve.altitudeFromPressure(
                    AltitudeCurve.STANDARD_ATMOSPHERE_HPA, pressure);
            assertEquals(altitude, fromPressure, 1.0f);
            assertTrue(Math.abs(gps - fromPressure) < 5.0);
            if (Math.abs(gps - msl) > 0.05) differ = true;
            float accuracy = model.verticalAccuracyMeters(t);
            assertTrue(accuracy >= 1.5f && accuracy <= 12f);
        }
        assertTrue(differ);
    }
}
