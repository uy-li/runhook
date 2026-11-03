package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MotionWaveformTest {

    @Test
    public void linearAccelerationIsAccelMinusGravity() {
        MotionWaveform wave = new MotionWaveform(42L);
        for (double phase = 0; phase < Math.PI * 4; phase += 0.17) {
            MotionWaveform.Sample sample = wave.sample(phase, 3.0, 1_000_000_000L);
            assertEquals(sample.gx + sample.lx, sample.ax, 1e-4f);
            assertEquals(sample.gy + sample.ly, sample.ay, 1e-4f);
            assertEquals(sample.gz + sample.lz, sample.az, 1e-4f);
            assertEquals(sample.ax, (sample.ax + sample.bax) - sample.bax, 1e-4f);
            assertEquals(sample.ay, (sample.ay + sample.bay) - sample.bay, 1e-4f);
            assertEquals(sample.az, (sample.az + sample.baz) - sample.baz, 1e-4f);
            assertEquals(sample.wx, (sample.wx + sample.bwx) - sample.bwx, 1e-4f);
            float gravity = (float) Math.sqrt(sample.gx * sample.gx + sample.gy * sample.gy + sample.gz * sample.gz);
            assertEquals(MotionWaveform.GRAVITY, gravity, 0.02f);
            float quat = (float) Math.sqrt(sample.qx * sample.qx + sample.qy * sample.qy
                    + sample.qz * sample.qz + sample.qw * sample.qw);
            assertEquals(1f, quat, 1e-3f);
            assertTrue(Float.isFinite(sample.mx) && Float.isFinite(sample.wx));
        }
    }

    @Test
    public void verticalImpactIsAsymmetricAndLateralSwayIsHalfFrequency() {
        MotionWaveform wave = new MotionWaveform(7L);
        long timeNs = 5_000_000_000L;
        float max = Float.NEGATIVE_INFINITY;
        float min = Float.POSITIVE_INFINITY;
        for (double phase = 0; phase < Math.PI * 2; phase += 0.02) {
            float lz = wave.sample(phase, 2.8, timeNs).lz;
            if (lz > max) max = lz;
            if (lz < min) min = lz;
        }
        assertTrue("peak " + max + " trough " + min, max > 1f && max + min > 0.2f);

        MotionWaveform.Sample atPi = wave.sample(Math.PI, 2.8, timeNs);
        MotionWaveform.Sample atThreePi = wave.sample(Math.PI * 3, 2.8, timeNs);
        assertEquals(MotionWaveform.LATERAL * 2f, atPi.lx - atThreePi.lx, 0.02f);

        MotionWaveform.Sample near = wave.sample(1.0, 2.8, timeNs);
        MotionWaveform.Sample next = wave.sample(1.02, 2.8, timeNs);
        assertTrue(Math.abs(near.az - next.az) < 1.2f);
    }

    @Test
    public void noiseIsGaussianRatherThanUniform() {
        double sum = 0;
        double sumSq = 0;
        int n = 400;
        boolean tail = false;
        for (int i = 0; i < n; i++) {
            float value = MotionWaveform.unitGaussian(99, i, 3);
            sum += value;
            sumSq += value * value;
            if (Math.abs(value) > 2f) tail = true;
        }
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        assertTrue("mean " + mean, Math.abs(mean) < 0.25);
        assertTrue("var " + variance, variance > 0.6 && variance < 1.6);
        assertTrue(tail);
    }
}
