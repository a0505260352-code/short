package com.example.shortlink.ratelimit;

/**
 * Redis could not answer, so the budget is unknown. Only a caller that refuses writes on an unknown
 * budget turns this into a failure; see the two paths in {@link RateLimitInterceptor}.
 */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String bucket, Throwable cause) {
        super("Rate limit backend unavailable for bucket " + bucket, cause);
    }
}
