package org.moqui.util;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/** Coordinator-seeded application entropy. Predictable test credentials: NEVER enable in production. */
public final class DeterministicRandom {
    private DeterministicRandom() { }

    public static byte[] bytes(String domain, int length) {
        if (length < 0 || (DeterministicClock.isEnabled() && length > 4096))
            throw new IllegalArgumentException("Invalid random length");
        if (length == 0) return new byte[0];
        if (DeterministicClock.isEnabled()) return DeterministicClock.get().randomBytes(domain, length);
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static UUID uuid(String domain) {
        if (!DeterministicClock.isEnabled()) return UUID.randomUUID();
        byte[] bytes = bytes(domain, 16);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static String string(String domain, int length) {
        return StringUtilities.getRandomString(length, null, domain);
    }

    public static double unit(String domain) {
        if (!DeterministicClock.isEnabled()) return Math.random();
        return (ByteBuffer.wrap(bytes(domain, 8)).getLong() >>> 11) * 0x1.0p-53;
    }

    public static int nextInt(String domain, int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Random bound must be positive");
        if (!DeterministicClock.isEnabled()) return new SecureRandom().nextInt(bound);
        long limit = (1L << 32) - (1L << 32) % bound;
        while (true) {
            long value = Integer.toUnsignedLong(ByteBuffer.wrap(bytes(domain, 4)).getInt());
            if (value < limit) return (int) (value % bound);
        }
    }
}
