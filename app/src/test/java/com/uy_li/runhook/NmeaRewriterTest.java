package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NmeaRewriterTest {

    @Test
    public void ggaAltitudeIsReplacedAndChecksumRecomputed() {
        String input = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n";
        String output = NmeaRewriter.rewrite(input, 123.4);
        assertTrue(output.startsWith("$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,123.4,M,46.9,M,,*"));
        assertTrue(output.endsWith("\r\n"));
        assertTrue(NmeaRewriter.checksumValid(output));
        assertFalse(output.contains("*47"));
        assertEquals(output, NmeaRewriter.rewrite(output, 123.4));

        int star = output.indexOf('*');
        int expect = 0;
        for (int i = 1; i < star; i++) expect ^= output.charAt(i);
        int actual = Integer.parseInt(output.substring(star + 1, star + 3), 16);
        assertEquals(expect & 0xFF, actual);
    }

    @Test
    public void otherTalkersAreRewrittenAndOtherSentencesAreNot() {
        for (String talker : new String[]{"GN", "GL", "GA", "GB", "BD"}) {
            String input = "$" + talker + "GGA,1,2,3,4,5,6,7,8,9.0,M,1.0,M,,*00";
            String output = NmeaRewriter.rewrite(input, -12.5);
            assertTrue(output.startsWith("$" + talker + "GGA,"));
            assertTrue(output.contains(",-12.5,M,"));
            assertTrue(NmeaRewriter.checksumValid(output));
        }
        String rmc = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        assertEquals(rmc, NmeaRewriter.rewrite(rmc, 10));
        String shortSentence = "$GPGGA,1,2,3*00";
        assertEquals(shortSentence, NmeaRewriter.rewrite(shortSentence, 10));
        assertEquals("not nmea", NmeaRewriter.rewrite("not nmea", 10));
    }

    @Test
    public void missingChecksumGetsOne() {
        String output = NmeaRewriter.rewrite("$GNGGA,1,2,3,4,5,6,7,8,,M,1,M,,", 50);
        assertTrue(output.contains(",50.0,M,"));
        assertTrue(NmeaRewriter.checksumValid(output));
    }
}
