package com.example.shortlink.core;

import com.example.shortlink.bloom.BloomService;
import com.example.shortlink.codec.CodeHasher;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import com.example.shortlink.persistence.mapper.ShortLinkMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Issues hash codes through a retry ring with two independent triggers.
 *
 * <ol>
 *   <li>{@code BF.EXISTS} as a cheap pre-filter. A definite "absent" skips the database probe entirely;
 *       a "probably present" is confirmed against {@code uk_code} before anything is spent rehashing.
 *   <li>{@link DuplicateKeyException} from the insert itself. This is the trigger that carries the
 *       system when the filter cannot be trusted, which is every time it is cold, restarted, flushed,
 *       or lost with the Redis instance.
 * </ol>
 *
 * <p>The unique index is the only source of truth. The filter is an optimisation over it, so a
 * collision it fails to predict costs one failed insert and one extra round, not a duplicate code.
 * Conversely a false positive costs a redundant {@code existsByCode} query. Neither outcome can issue
 * the same code twice.
 *
 * <p>On a collision the salt is appended to the <em>hash input</em> and the whole pipeline re-runs, so
 * every attempt emits a fresh fixed-width code. Appending to the produced code instead would grow it
 * past the width the redirect routes assume.
 */
@Component
public class ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGenerator.class);

    private final BloomService bloom;
    private final ShortLinkMapper mapper;
    private final CodeProperties props;

    public ShortCodeGenerator(BloomService bloom, ShortLinkMapper mapper, CodeProperties props) {
        this.bloom = bloom;
        this.mapper = mapper;
        this.props = props;
    }

    public ShortLinkEntity issue(ShortLinkEntity draft) {
        String url = draft.getOriginalUrl();
        String hashInput = url;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            String code = CodeHasher.code(hashInput);
            draft.setCode(code);
            if (bloom.possiblyExists(code) && mapper.existsByCode(code)) {
                hashInput = url + UUID.randomUUID();
                continue;
            }
            try {
                mapper.insert(draft);
            } catch (DuplicateKeyException e) {
                hashInput = url + UUID.randomUUID();
                continue;
            }
            bloom.add(code);
            return draft;
        }
        // No URL here: an exhausted ring is a capacity signal and the caller's target address must not
        // end up in a log line that ops dashboards scrape.
        log.warn("Code space exhausted after {} attempts", props.maxAttempts());
        throw new CodeExhaustedException(props.maxAttempts());
    }
}
