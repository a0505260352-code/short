package com.example.shortlink.ratelimit;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Fixed-window counters, one round trip per request.
 *
 * <p>Counting happens inside Redis because the alternative — counting in each instance and comparing
 * against the limit locally — makes the limit grow with the instance count, and this service is meant
 * to scale out.
 *
 * <p>Deliberately free of any policy about what to do when Redis is unreachable: a caller that guards a
 * write must refuse, a caller that guards a redirect must not. See {@link RateLimitInterceptor}.
 */
@Service
public class RateLimitService {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> WINDOW =
            RedisScript.of(new ClassPathResource("lua/rate_limit.lua"), List.class);

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision hit(String bucket, RateLimitProperties.Window limit) {
        long windowMillis = limit.window().toMillis();
        List<?> counted;
        try {
            counted = redis.execute(WINDOW, List.of(bucket), String.valueOf(windowMillis));
        } catch (DataAccessException e) {
            throw new RateLimitUnavailableException(bucket, e);
        }
        if (counted == null || counted.size() < 2) {
            throw new RateLimitUnavailableException(bucket, new IllegalStateException("unexpected reply"));
        }
        try {
            long count = asLong(counted.get(0));
            long remainingMillis = asLong(counted.get(1));
            return new Decision(count <= limit.limit(), retryAfterSeconds(remainingMillis));
        } catch (RuntimeException e) {
            throw new RateLimitUnavailableException(bucket, e);
        }
    }

    /**
     * {@code StringRedisTemplate} runs the reply through its value serializer, so a Lua number arrives as
     * a {@code String} rather than the {@code Long} the script returned. Both shapes are accepted: the
     * reply type is a property of the template, and this class should not 500 if that changes.
     */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong((String) value);
    }

    /**
     * Rounded up and floored at one second: a sub-second value reads as "retry immediately" to clients,
     * which is the opposite of what a rejection means.
     */
    private static long retryAfterSeconds(long remainingMillis) {
        return Math.max(1L, (remainingMillis + 999) / 1000);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
