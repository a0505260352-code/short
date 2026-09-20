package com.example.shortlink.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    /**
     * Injected rather than called as {@code Clock.systemUTC()} so expiry decisions — the cache TTL clamp
     * and the live-link check — can be moved through time in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
