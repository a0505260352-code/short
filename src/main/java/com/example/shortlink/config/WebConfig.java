package com.example.shortlink.config;

import com.example.shortlink.ratelimit.RateLimitInterceptor;
import com.example.shortlink.ratelimit.RateLimitKeyResolver;
import com.example.shortlink.ratelimit.RateLimitProperties;
import com.example.shortlink.ratelimit.RateLimitService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where each limit is attached. Two interceptors guard creation because a per-client limit alone still
 * lets the whole internet create rows together; the redirect path needs no global ceiling because its
 * per-client limit is already the cheapest thing on that route, and a shared counter there would let
 * one noisy client shut the redirect out for everyone.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitService service;
    private final RateLimitProperties props;
    private final RateLimitKeyResolver ipResolver;

    public WebConfig(RateLimitService service, RateLimitProperties props, RateLimitKeyResolver ipResolver) {
        this.service = service;
        this.props = props;
        this.ipResolver = ipResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimitKeyResolver shared = request -> "global";
        registry.addInterceptor(new RateLimitInterceptor("create-ip", props.createPerIp(), ipResolver,
                        service, false))
                .addPathPatterns("/api/links");
        registry.addInterceptor(new RateLimitInterceptor("create-global", props.createGlobal(), shared,
                        service, false))
                .addPathPatterns("/api/links");
        registry.addInterceptor(new RateLimitInterceptor("redirect-ip", props.redirectPerIp(), ipResolver,
                        service, true))
                // /error is a single-segment path, so the /{code} pattern would otherwise match it too:
                // a rejected visitor would then get a 429 where the answer was supposed to be 404, and
                // every mistyped code would quietly spend part of that client's redirect budget.
                .addPathPatterns("/{code}")
                .excludePathPatterns("/error");
    }
}
