package com.example.shortlink.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param queueCapacity    how many clicks may wait in memory before the consumer starts stalling the
 *                         broker's flow control
 * @param maxBatchRows     rows per {@code INSERT}, and the most a single flush tick drains
 * @param flushIntervalMs  how long an incomplete batch may sit before being written
 * @param offerTimeoutMs   how long a consumer thread waits for queue space before giving up on one event
 */
@ConfigurationProperties(prefix = "shortlink.click")
public record ClickProperties(
        int queueCapacity,
        int maxBatchRows,
        long flushIntervalMs,
        long offerTimeoutMs) {
}
