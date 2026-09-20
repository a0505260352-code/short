package com.example.shortlink.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param horizonDays   how far ahead of today day partitions are pre-created; a partition that already
 *                      holds rows must be split by rewriting them, so staying ahead keeps every split
 *                      working on an empty {@code pmax}
 * @param retentionDays how long click rows survive before their whole partition is dropped
 */
@ConfigurationProperties(prefix = "shortlink.partition")
public record PartitionProperties(int horizonDays, int retentionDays) {
}
