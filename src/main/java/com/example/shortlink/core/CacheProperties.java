package com.example.shortlink.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortlink.cache")
public record CacheProperties(long maxTtlSeconds, long negativeTtlSeconds) {
}
