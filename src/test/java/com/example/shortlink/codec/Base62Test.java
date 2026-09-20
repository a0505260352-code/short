package com.example.shortlink.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class Base62Test {

    @Test
    void padsLeftWithZeroDigit() {
        assertThat(Base62.encode(0, 6)).isEqualTo("000000");
        assertThat(Base62.encode(1, 6)).isEqualTo("000001");
        assertThat(Base62.encode(61, 6)).isEqualTo("00000z");
    }

    @Test
    void readsIntAsUnsignedSoNegativeHashesStayInDigitRange() {
        assertThat(Base62.encode(-1, 6)).isEqualTo("4gfFC3");
        assertThat(Base62.encode(Integer.MIN_VALUE, 6)).isEqualTo("2LKcb2");
        assertThat(Base62.encode(Integer.MAX_VALUE, 6)).isEqualTo("2LKcb1");
    }

    @Test
    void refusesWidthsThatWouldSilentlyTruncate() {
        // 62^2 = 3844, so 4000 needs three digits; a two-digit render would drop a whole hash value.
        assertThat(Base62.encode(3843, 2)).isEqualTo("zz");
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> Base62.encode(3844, 2));
    }
}
