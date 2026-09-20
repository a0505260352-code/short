package com.example.shortlink.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param sweepBatchSize rows one {@code UPDATE} flips to EXPIRED. Bounded because the sweep is a
 *                       single statement over an index range and an unbounded one would hold locks on
 *                       every expired row at once, which is a lot of rows after an outage.
 */
@ConfigurationProperties(prefix = "shortlink.expiry")
public record ExpiryProperties(int sweepBatchSize) {
}
