package com.example.shortlink.mq;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

/**
 * Hands a click to the broker and walks away.
 *
 * <p>Nothing here may cost the redirect path a millisecond it does not already spend, so the binding
 * publishes one-way (see the {@code send-type} setting in {@code application.yml}): no broker round
 * trip is waited for and no send is retried. The visible consequence is that a click the broker did not
 * accept is not detectable here, which is the intended trade — a statistics pipeline that can fail a
 * redirect is worse than one that can lose a count.
 */
@Component
public class ClickProducer {

    static final String CLICK_OUT_BINDING = "click-out-0";

    private static final Logger log = LoggerFactory.getLogger(ClickProducer.class);

    private final StreamBridge bridge;
    private final Clock clock;

    public ClickProducer(StreamBridge bridge, Clock clock) {
        this.bridge = bridge;
        this.clock = clock;
    }

    public void publish(String code, String clientIp, String userAgent, String referer) {
        ClickEvent event = new ClickEvent(UUID.randomUUID().toString(), code, clock.instant().toEpochMilli(),
                clientIp, cap(userAgent, 512), cap(referer, 1024));
        try {
            if (!bridge.send(CLICK_OUT_BINDING, event)) {
                log.warn("Click event declined by the binding, code={}", code);
            }
        } catch (RuntimeException e) {
            // Reaching this line means the broker is unhealthy. The visitor already has their redirect.
            log.warn("Click event dropped for code={}: {}", code, e.toString());
        }
    }

    /**
     * Client-supplied headers are unbounded; a value wider than its column would fail the insert and
     * take the rest of the batch down with it.
     */
    private static String cap(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
