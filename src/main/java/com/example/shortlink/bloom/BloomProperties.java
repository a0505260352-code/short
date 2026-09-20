package com.example.shortlink.bloom;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortlink.bloom")
public record BloomProperties(
        String key,
        double falsePositiveRate,
        long expectedCapacity,
        int expansion) {
}
