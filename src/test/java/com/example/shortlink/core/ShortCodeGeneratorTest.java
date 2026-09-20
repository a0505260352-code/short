package com.example.shortlink.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortlink.bloom.BloomService;
import com.example.shortlink.codec.CodeHasher;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import com.example.shortlink.persistence.mapper.ShortLinkMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * The ring has to keep working when the filter is useless, so every test here drives the bloom mock to
 * its least helpful answer and asserts the database unique index still produces a valid code.
 */
class ShortCodeGeneratorTest {

    private static final String URL = "https://example.com/some/long/path";
    private static final DuplicateKeyException DUP = new DuplicateKeyException("1062 duplicate");

    private BloomService bloom;
    private ShortLinkMapper mapper;
    private ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        bloom = mock(BloomService.class);
        mapper = mock(ShortLinkMapper.class);
        generator = new ShortCodeGenerator(bloom, mapper, new CodeProperties(8));
    }

    private ShortLinkEntity draft() {
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setOriginalUrl(URL);
        entity.setCodeType(ShortLinkEntity.CODE_TYPE_HASH);
        entity.setStatus(ShortLinkEntity.STATUS_ACTIVE);
        return entity;
    }

    /**
     * Fails the first {@code failures} inserts with the real duplicate-key signal and records every code
     * as it is handed over. Recording inside the answer is required, not stylistic: the ring writes each
     * attempt into the same entity, so a captor would show the winning code {@code failures + 1} times.
     */
    private List<String> rejectInsertsUntil(int failures) {
        List<String> attempted = new ArrayList<>();
        when(mapper.insert(any(ShortLinkEntity.class))).thenAnswer(invocation -> {
            ShortLinkEntity entity = invocation.getArgument(0);
            attempted.add(entity.getCode());
            if (attempted.size() <= failures) {
                throw DUP;
            }
            return 1;
        });
        return attempted;
    }

    @Test
    void coldBloomStillIssuesACodeThroughTheUniqueIndex() {
        when(bloom.possiblyExists(anyString())).thenReturn(false);
        List<String> attempted = rejectInsertsUntil(2);

        ShortLinkEntity issued = generator.issue(draft());

        assertThat(issued.getCode()).hasSize(6).matches("[0-9A-Za-z]{6}");
        assertThat(attempted).hasSize(3).doesNotHaveDuplicates();
        assertThat(attempted.get(0)).isEqualTo(CodeHasher.code(URL));
        assertThat(issued.getCode()).isEqualTo(attempted.get(2));
        verify(bloom).add(issued.getCode());
    }

    @Test
    void bloomFalsePositiveNeverSkipsTheInsert() {
        when(bloom.possiblyExists(anyString())).thenReturn(true);
        when(mapper.existsByCode(anyString())).thenReturn(false);
        List<String> attempted = rejectInsertsUntil(0);

        ShortLinkEntity issued = generator.issue(draft());

        assertThat(issued.getCode()).isEqualTo(CodeHasher.code(URL));
        assertThat(attempted).containsExactly(CodeHasher.code(URL));
    }

    @Test
    void bloomHitConfirmedByTheRowReHashesInsteadOfFailing() {
        when(bloom.possiblyExists(anyString())).thenReturn(true);
        when(mapper.existsByCode(anyString())).thenReturn(true, false);
        List<String> attempted = rejectInsertsUntil(0);

        ShortLinkEntity issued = generator.issue(draft());

        assertThat(attempted).hasSize(1).doesNotContain(CodeHasher.code(URL));
        assertThat(issued.getCode()).isEqualTo(attempted.get(0));
    }

    @Test
    void collisionOnEveryAttemptExhaustsTheRing() {
        when(bloom.possiblyExists(anyString())).thenReturn(false);
        List<String> attempted = rejectInsertsUntil(Integer.MAX_VALUE);

        assertThatExceptionOfType(CodeExhaustedException.class).isThrownBy(() -> generator.issue(draft()));

        verify(mapper, times(8)).insert(any(ShortLinkEntity.class));
        assertThat(attempted).hasSize(8).doesNotHaveDuplicates();
        verify(bloom, never()).add(anyString());
    }

    @Test
    void everyAttemptIsAFullRehashAtTheSameWidth() {
        when(bloom.possiblyExists(anyString())).thenReturn(false);
        List<String> attempted = rejectInsertsUntil(3);

        generator.issue(draft());

        assertThat(attempted).hasSize(4).doesNotHaveDuplicates();
        assertThat(attempted).allMatch(code -> code.length() == CodeHasher.HASH_CODE_LENGTH);
        assertThat(attempted).allMatch(code -> code.matches("[0-9A-Za-z]{6}"));
    }

    @Test
    void filterIsToldOnlyAboutTheCodeThatActuallyLanded() {
        when(bloom.possiblyExists(anyString())).thenReturn(false);
        List<String> attempted = rejectInsertsUntil(1);

        generator.issue(draft());

        verify(bloom, times(1)).add(anyString());
        verify(bloom).add(attempted.get(1));
        verify(bloom, never()).add(attempted.get(0));
    }
}
