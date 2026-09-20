package com.example.shortlink.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * One bucket, one limit, one policy for what an outage means.
 *
 * <p>The two policies are not interchangeable and neither is a default:
 * <ul>
 *   <li><b>Creation fails closed.</b> Without a counter there is no bound on how many rows, codes and
 *       cache entries a single client can mint, and the cleanup bill for that is larger than the
 *       outage it would ride through.</li>
 *   <li><b>Redirects fail open.</b> The limit there exists to keep a flood off the database, and the
 *       database is not what broke. Refusing visitors would trade a partial outage for a total one.</li>
 * </ul>
 *
 * <p>Not a Spring bean: {@link com.example.shortlink.config.WebConfig} registers one instance per
 * bucket, because a limit is a property of the route rather than of the application.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final String bucketPrefix;
    private final RateLimitProperties.Window limit;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitService service;
    private final boolean failOpen;

    public RateLimitInterceptor(String bucketPrefix, RateLimitProperties.Window limit,
                               RateLimitKeyResolver keyResolver, RateLimitService service, boolean failOpen) {
        this.bucketPrefix = bucketPrefix;
        this.limit = limit;
        this.keyResolver = keyResolver;
        this.service = service;
        this.failOpen = failOpen;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String bucket = bucketPrefix + ":" + keyResolver.resolve(request);
        RateLimitService.Decision decision;
        try {
            decision = service.hit(bucket, limit);
        } catch (RateLimitUnavailableException e) {
            if (!failOpen) {
                throw e;
            }
            // Once per request, which is once per visitor while Redis is down: enough to explain the
            // counters being wrong, not enough to become the outage's loudest symptom.
            log.warn("Redirect allowed without a rate limit: {}", e.getMessage());
            return true;
        }
        if (!decision.allowed()) {
            throw new RateLimitedException(bucket, decision.retryAfterSeconds());
        }
        return true;
    }
}
