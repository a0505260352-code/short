package com.example.shortlink.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which bucket a request should be charged to.
 *
 * <p>This is the seam left for authentication: once a caller has an API key the bucket becomes the key
 * or its owner rather than the peer address, and only this bean changes — the interceptor and the way
 * limits are registered stay as they are.
 */
@FunctionalInterface
public interface RateLimitKeyResolver {

    String resolve(HttpServletRequest request);
}
