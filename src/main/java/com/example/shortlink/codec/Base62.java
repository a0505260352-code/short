package com.example.shortlink.codec;

/**
 * Fixed-width Base62 over the 32-bit hash space.
 */
public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private Base62() {
    }

    /**
     * Renders {@code value} as exactly {@code width} characters, zero-padded on the left, reading the
     * int as unsigned so the whole 2^32 range is reachable.
     */
    public static String encode(int value, int width) {
        char[] out = new char[width];
        long v = Integer.toUnsignedLong(value);
        for (int i = width - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (v % 62)];
            v /= 62;
        }
        if (v != 0) {
            throw new IllegalStateException("width " + width + " cannot hold unsigned int " + value);
        }
        return new String(out);
    }
}


