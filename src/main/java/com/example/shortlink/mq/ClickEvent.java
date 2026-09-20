package com.example.shortlink.mq;

/**
 * Wire format of the {@code short-link-click} topic.
 *
 * <p>{@code eventId} is minted by the producer, not the broker, and survives redelivery unchanged, which
 * is what makes the consumer's {@code INSERT IGNORE} against {@code uk_event} idempotent.
 * {@code occurredAt} is epoch milliseconds rather than an {@code Instant} because it also selects the
 * MySQL partition the row lands in, and a producer that restamped it on retry would move the duplicate
 * into a different partition and defeat that unique key.
 */
public record ClickEvent(
        String eventId,
        String code,
        long occurredAt,
        String clientIp,
        String userAgent,
        String referer) {
}
