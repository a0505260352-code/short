package com.example.shortlink.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * {@code code} is the optional custom (vanity) short code; blank means "generate one".
 */
public record CreateLinkRequest(
        @NotBlank(message = "url is required") @Size(max = 2048) String url,
        @Size(max = 12) String code,
        Instant validUntil) {
}
