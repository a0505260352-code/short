package com.example.shortlink.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The buckets this service enforces. Limits stay out of the Lua script on purpose: raising one is a
 * configuration change, not a change to a script cached inside Redis.
 */
@ConfigurationProperties(prefix = "shortlink.ratelimit")
public record RateLimitProperties(
        int createPerIpLimit,
        int createPerIpWindowSeconds,
        int createGlobalLimit,
        int createGlobalWindowSeconds,
        int redirectPerIpLimit,
        int redirectPerIpWindowSeconds) {

    public Window createPerIp() {
        return new Window(createPerIpLimit, createPerIpWindowSeconds);
    }

    public Window createGlobal() {
        return new Window(createGlobalLimit, createGlobalWindowSeconds);
    }

    public Window redirectPerIp() {
        return new Window(redirectPerIpLimit, redirectPerIpWindowSeconds);
    }

    public record Window(int limit, int windowSeconds) {

        public Duration window() {
            return Duration.ofSeconds(windowSeconds);
        }
    }
}
