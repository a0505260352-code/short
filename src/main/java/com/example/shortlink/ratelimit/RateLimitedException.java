package com.example.shortlink.ratelimit;

/**
 * The caller spent its budget. Carries how long to wait so the response can state it instead of making
 * the client guess, which is what turns a rate limit into something a well-behaved client can recover
 * from rather than retry on top of.
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String bucket, long retryAfterSeconds) {
        super("Rate limit reached on bucket " + bucket);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
