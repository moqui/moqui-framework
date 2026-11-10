/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Change 1: supplied data time for isolated acceptance runs, separate from real duration clocks. */
public final class TestDataClock {
    public static final String ENV = "MOQUI_TEST_DATA_CLOCK_FILE";
    private static final java.util.concurrent.atomic.AtomicBoolean ACK = new java.util.concurrent.atomic.AtomicBoolean();
    private TestDataClock() { }

    public static long nowMillis() {
        String setting = System.getenv(ENV);
        long millis = fromSetting(setting);
        if (setting != null && ACK.compareAndSet(false, true))
            System.err.println("MOQUI_TEST_DATA_CLOCK_FILE active epoch_ms=" + millis);
        return millis;
    }

    static long fromSetting(String setting) {
        if (setting == null) return System.currentTimeMillis();
        try {
            return readAssignment(Path.of(setting));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid MOQUI_TEST_DATA_CLOCK_FILE assignment; no wall-clock fallback", e);
        }
    }

    /** Every read validates the current atomically published assignment; a bad update never reuses old time. */
    public static long readAssignment(Path path) throws IOException {
        if (!path.isAbsolute() || !Files.isRegularFile(path))
            throw new IOException("Test data clock requires an absolute regular file");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(33); }
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        if (!text.matches("[0-9]{1,15}"))
            throw new IOException("Test data clock requires positive ASCII epoch milliseconds");
        long millis = Long.parseLong(text);
        if (millis < 1 || millis > 253402300799999L)
            throw new IOException("Test data clock epoch is outside years 1970 through 9999");
        return millis;
    }
}
