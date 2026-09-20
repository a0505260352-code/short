package com.example.shortlink.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Charges the peer address. Behind a proxy that address is the proxy's, which would collapse every
 * visitor into one bucket and hand a single client an unlimited budget — so this is the one class to
 * change when the deployment decides which hop is trusted.
 */
@Component
public class IpKeyResolver implements RateLimitKeyResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
