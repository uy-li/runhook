package com.uy_li.runhook;

import java.util.Locale;

/**
 * 改写 NMEA GGA 语句里的海拔字段并重算校验和。
 * 识别所有 talker（GP/GN/GL/GA/GB/BD 等）的 GGA，其它语句原样返回。
 */
public final class NmeaRewriter {

    private NmeaRewriter() {
    }

    public static String rewrite(String sentence, double altitudeMeters) {
        if (sentence == null || sentence.isEmpty()) return sentence;
        if (Double.isNaN(altitudeMeters) || Double.isInfinite(altitudeMeters)) return sentence;

        int dollar = sentence.indexOf('$');
        if (dollar < 0) return sentence;
        int star = sentence.indexOf('*', dollar + 1);
        int payloadEnd = star >= 0 ? star : endOfLine(sentence, dollar + 1);
        if (payloadEnd <= dollar + 1) return sentence;

        String payload = sentence.substring(dollar + 1, payloadEnd);
        String[] fields = splitComma(payload);
        if (fields.length < 10 || !isGga(fields[0])) return sentence;

        fields[9] = String.format(Locale.US, "%.1f", altitudeMeters);
        StringBuilder rebuilt = new StringBuilder(payload.length() + 8);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) rebuilt.append(',');
            rebuilt.append(fields[i]);
        }
        int checksum = checksum(rebuilt);

        String suffix;
        if (star >= 0) {
            int after = star + 1;
            if (after + 1 < sentence.length() && isHex(sentence.charAt(after)) && isHex(sentence.charAt(after + 1))) {
                after += 2;
            }
            suffix = sentence.substring(after);
        } else {
            suffix = sentence.substring(payloadEnd);
        }
        return sentence.substring(0, dollar) + '$' + rebuilt
                + String.format(Locale.US, "*%02X", checksum)
                + suffix;
    }

    public static boolean checksumValid(String sentence) {
        if (sentence == null) return false;
        int dollar = sentence.indexOf('$');
        int star = sentence.indexOf('*');
        if (dollar < 0 || star <= dollar + 1 || star + 2 >= sentence.length()) return false;
        int expect = checksum(sentence.substring(dollar + 1, star));
        int actual = parseHex(sentence.charAt(star + 1), sentence.charAt(star + 2));
        return actual >= 0 && actual == expect;
    }

    static int checksum(CharSequence payload) {
        int value = 0;
        for (int i = 0; i < payload.length(); i++) {
            value ^= payload.charAt(i);
        }
        return value & 0xFF;
    }

    private static boolean isGga(String type) {
        return type != null && type.length() >= 5 && type.regionMatches(type.length() - 3, "GGA", 0, 3);
    }

    private static int endOfLine(String sentence, int from) {
        for (int i = from; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (c == '\r' || c == '\n') return i;
        }
        return sentence.length();
    }

    private static String[] splitComma(String payload) {
        int count = 1;
        for (int i = 0; i < payload.length(); i++) {
            if (payload.charAt(i) == ',') count++;
        }
        String[] fields = new String[count];
        int start = 0;
        int index = 0;
        for (int i = 0; i < payload.length(); i++) {
            if (payload.charAt(i) == ',') {
                fields[index++] = payload.substring(start, i);
                start = i + 1;
            }
        }
        fields[index] = payload.substring(start);
        return fields;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static int parseHex(char hi, char lo) {
        int h = hex(hi);
        int l = hex(lo);
        if (h < 0 || l < 0) return -1;
        return (h << 4) | l;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}
