package com.example.shortlink.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortlink.ratelimit.RateLimitProperties.Window;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The outage half of the two policies, which only a unit test can reach: an integration test has a
 * working Redis, and the behaviour that matters is what happens when it stops working.
 */
class RateLimitInterceptorTest {

    private static final Window LIMIT = new Window(2, 60);
    private static final String KEY = "client-key";

    private final RateLimitService service = mock(RateLimitService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/links");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void anOutageStopsCreation() {
        RateLimitInterceptor interceptor = interceptor("create-ip", false);
        unavailable("create-ip:" + KEY);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void anOutageLeavesRedirectsAlone() {
        RateLimitInterceptor interceptor = interceptor("redirect-ip", true);
        unavailable("redirect-ip:" + KEY);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void aSpentBudgetIsRejectedWithTheWaitRedisReported() {
        interceptor("create-ip", false);
        when(service.hit(any(), any())).thenReturn(new RateLimitService.Decision(false, 42));

        assertThatThrownBy(() -> requestThrough("create-ip"))
                .isInstanceOf(RateLimitedException.class)
                .extracting(e -> ((RateLimitedException) e).retryAfterSeconds())
                .isEqualTo(42L);
    }

    /**
     * The bucket is what every Redis counter is keyed by, so its shape is worth pinning: one prefix per
     * limit, so the three limits cannot shadow each other, and the resolved key verbatim behind it.
     */
    @Test
    void theBucketIsThePrefixPlusTheResolvedKey() {
        allow();

        requestThrough("create-ip");
        requestThrough("redirect-ip");

        verify(service).hit(eq("create-ip:" + KEY), eq(LIMIT));
        verify(service).hit(eq("redirect-ip:" + KEY), eq(LIMIT));
    }

    private RateLimitInterceptor interceptor(String prefix, boolean failOpen) {
        return new RateLimitInterceptor(prefix, LIMIT, ignored -> KEY, service, failOpen);
    }

    private void allow() {
        when(service.hit(any(), any())).thenReturn(new RateLimitService.Decision(true, 0));
    }

    private void requestThrough(String prefix) {
        interceptor(prefix, false).preHandle(request, response, new Object());
    }

    private void unavailable(String bucket) {
        when(service.hit(eq(bucket), any()))
                .thenThrow(new RateLimitUnavailableException(bucket, new IllegalStateException("redis down")));
    }
}
