package com.example.shortlink.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortlink.code")
public record CodeProperties(int maxAttempts) {
}
