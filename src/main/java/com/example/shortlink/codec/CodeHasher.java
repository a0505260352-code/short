package com.example.shortlink.codec;

import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.MurmurHash3;

/**
 * MurmurHash3 x86 32-bit -> fixed-width Base62. The hash is read as an unsigned int, so the code space
 * is the full 2^32, while 6 Base62 characters span 62^6 — strictly larger, which is what makes a
 * collision possible but never forces a wider code.
 */
public final class CodeHasher {

    public static final int HASH_CODE_LENGTH = 6;

    private CodeHasher() {
    }

    public static int hash(String input) {
        return MurmurHash3.hash32x86(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String code(String input) {
        return Base62.encode(hash(input), HASH_CODE_LENGTH);
    }
}
