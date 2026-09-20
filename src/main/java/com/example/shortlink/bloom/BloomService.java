package com.example.shortlink.bloom;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * RedisBloom-backed membership set over every short code ever issued.
 *
 * <p>This is an accelerator, never the arbiter. It answers "definitely absent" or "probably present",
 * and a probable-present is settled by probing the database unique index. Bloom filters support no
 * delete, so codes belonging to expired or deleted links stay in the set forever. The only cost of
 * that accumulation is an occasional extra {@code existsByCode} query; it can never cause a wrong
 * redirect or a lost link, because {@code t_short_link} is the source of truth. Do not replace it
 * with a deletable structure: a set that can silently lose entries turns into duplicate short codes.
 *
 * <p>Commands go through {@code EVAL} because Lettuce 6.x exposes no typed Bloom API and its
 * {@code CommandType} enum has no {@code BF_*} constants, while Redis Open Source 8 ships the
 * {@code bf} module, so {@code redis.call('BF.*')} resolves server-side.
 */
@Component
public class BloomService {

    private static final Logger log = LoggerFactory.getLogger(BloomService.class);

    private static final RedisScript<Long> RESERVE = RedisScript.of(
            "redis.call('BF.RESERVE', KEYS[1], ARGV[1], ARGV[2], 'EXPANSION', ARGV[3]); return 1",
            Long.class);
    private static final RedisScript<Long> ADD = RedisScript.of(
            "return redis.call('BF.ADD', KEYS[1], ARGV[1])", Long.class);
    private static final RedisScript<Long> EXISTS = RedisScript.of(
            "return redis.call('BF.EXISTS', KEYS[1], ARGV[1])", Long.class);

    private final StringRedisTemplate redis;
    private final BloomProperties props;

    public BloomService(StringRedisTemplate redis, BloomProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Sizes the filter up front. {@code BF.ADD} would auto-create one with a small default capacity
     * anyway, so a failure here only costs memory planning: {@link #add} and {@link #possiblyExists}
     * keep working against the auto-created filter.
     */
    @PostConstruct
    void reserve() {
        try {
            redis.execute(RESERVE, List.of(props.key()),
                    String.valueOf(props.falsePositiveRate()),
                    String.valueOf(props.expectedCapacity()),
                    String.valueOf(props.expansion()));
        } catch (DataAccessException e) {
            // Steady state is "item exists"; anything else surfaces on the first add/exists call.
            log.debug("BF.RESERVE {} skipped: {}", props.key(), e.getMessage());
        }
    }

    public boolean possiblyExists(String code) {
        Long exists = redis.execute(EXISTS, List.of(props.key()), code);
        return exists != null && exists == 1L;
    }

    /**
     * A failed insert into the filter leaves the code absent from it, which costs one extra
     * {@code existsByCode} probe on the next collision. It must not fail an already-committed row.
     */
    public void add(String code) {
        try {
            redis.execute(ADD, List.of(props.key()), code);
        } catch (DataAccessException e) {
            log.warn("BF.ADD failed for code, filter may now miss it (DB still arbitrates): {}", e.getMessage());
        }
    }
}
