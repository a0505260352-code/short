package com.example.shortlink.core;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redirect-path read cache over Redis, with an explicit three-state answer.
 *
 * <p>{@link Cached.KnownAbsent} is the load-bearing state: without it a stream of random codes walks
 * straight to the database on every request, because there is nothing to cache and therefore nothing to
 * hit. One empty-string marker per code caps that at one query per negative TTL.
 *
 * <p>Entry lifetime is clamped to the link's own {@code valid_until}, so expiry needs no invalidation
 * step at all — an entry cannot outlive the row it points at.
 */
@Component
public class LinkCache {

    private static final String KEY_PREFIX = "sl:c:";
    private static final String ABSENT_MARKER = "";

    private final StringRedisTemplate redis;
    private final CacheProperties props;
    private final Clock clock;

    public LinkCache(StringRedisTemplate redis, CacheProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public sealed interface Cached {
        record Present(String url) implements Cached {
        }

        record KnownAbsent() implements Cached {
        }

        record NotCached() implements Cached {
        }
    }

    public Cached find(String code) {
        String value = redis.opsForValue().get(KEY_PREFIX + code);
        if (value == null) {
            return new Cached.NotCached();
        }
        return value.isEmpty() ? new Cached.KnownAbsent() : new Cached.Present(value);
    }

    public void store(String code, String url, LocalDateTime validUntil) {
        Duration ttl = lifetimeOf(validUntil);
        if (ttl == null) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + code, url, ttl);
    }

    public void storeAbsent(String code) {
        redis.opsForValue().set(KEY_PREFIX + code, ABSENT_MARKER, Duration.ofSeconds(props.negativeTtlSeconds()));
    }

    public void evict(String code) {
        redis.delete(KEY_PREFIX + code);
    }

    /**
     * Null means "do not cache": a link that expires inside the current second would otherwise be
     * written with a round-down-to-zero TTL, and a one-second grace on the cached copy is exactly the
     * outliving-the-row this clamp exists to prevent.
     */
    private Duration lifetimeOf(LocalDateTime validUntil) {
        Duration max = Duration.ofSeconds(props.maxTtlSeconds());
        if (validUntil == null) {
            return max;
        }
        Duration left = Duration.between(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), validUntil);
        if (left.compareTo(Duration.ofSeconds(1)) < 0) {
            return null;
        }
        return left.compareTo(max) > 0 ? max : left;
    }
}
