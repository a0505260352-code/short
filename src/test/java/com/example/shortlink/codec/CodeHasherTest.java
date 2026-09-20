package com.example.shortlink.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The golden values below pin the hash-to-code mapping. They are not derived from the implementation:
 * changing the hash variant, the alphabet order or the width will break this test even though the code
 * still "works", which is the point — every already-issued short link depends on the mapping staying
 * put.
 */
class CodeHasherTest {

    @Test
    void hashesAreGolden() {
        assertThat(CodeHasher.hash("https://example.com")).isEqualTo(-929933992);
        assertThat(CodeHasher.code("https://example.com")).isEqualTo("3fjKsi");
        assertThat(CodeHasher.code("https://example.com/")).isEqualTo("0QRiCZ");
        assertThat(CodeHasher.code("http://a.co")).isEqualTo("3XZnqp");
        assertThat(CodeHasher.code("https://very.long.url.example.org/some/path?query=1&x=2#frag"))
                .isEqualTo("0tso0d");
    }

    @Test
    void trailingSlashIsADifferentLinkAndGetsADifferentCode() {
        assertThat(CodeHasher.code("https://example.com")).isNotEqualTo(CodeHasher.code("https://example.com/"));
    }

    @Test
    void saltingAfterACollisionProducesADifferentCodeAtTheSameWidth() {
        String url = "https://example.com";
        String salted = url + UUID.randomUUID();
        assertThat(CodeHasher.code(salted)).isNotEqualTo(CodeHasher.code(url)).hasSize(6);
    }

    @Test
    void everyCodeIsSixBase62Characters() {
        for (int i = 0; i < 5_000; i++) {
            assertThat(CodeHasher.code("https://host.example/path/" + i + "?x=" + i * 7))
                    .hasSize(CodeHasher.HASH_CODE_LENGTH)
                    .matches("[0-9A-Za-z]{6}");
        }
    }

    @Test
    void spreadIsWideEnoughToKeepRetriesRare() {
        int samples = 100_000;
        Set<String> codes = new HashSet<>(samples * 2);
        for (int i = 0; i < samples; i++) {
            codes.add(CodeHasher.code("https://example.com/thing/" + i));
        }
        // Birthday expectation over 2^32 is ~1.16 duplicates for this sample size; anything near 100
        // duplicates would mean the mapping collapsed into a narrow band.
        assertThat(codes).hasSizeGreaterThan(samples - 100);
    }

    @Test
    void emptyInputHashesToZero() {
        // URL validation rejects an empty target long before hashing; pinned so the all-zero code
        // coming out of hash 0 is a known property rather than a surprise.
        assertThat(CodeHasher.hash("")).isEqualTo(0);
        assertThat(CodeHasher.code("")).isEqualTo("000000");
    }
}
