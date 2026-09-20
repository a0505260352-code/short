package com.example.shortlink.web.dto;

import com.example.shortlink.persistence.entity.ShortLinkEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record LinkResponse(
        String code,
        String shortUrl,
        String originalUrl,
        String codeType,
        String status,
        Instant validUntil,
        Instant createdAt,
        long clickCount) {

    public static LinkResponse of(ShortLinkEntity entity, String baseUrl) {
        return new LinkResponse(
                entity.getCode(),
                baseUrl + "/" + entity.getCode(),
                entity.getOriginalUrl(),
                codeType(entity.getCodeType()),
                status(entity.getStatus()),
                toInstant(entity.getValidUntil()),
                toInstant(entity.getCreatedAt()),
                entity.getClickCount());
    }

    /** DATETIME columns hold no offset and the connection is configured as UTC, so this is a relabel. */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String codeType(int value) {
        return value == ShortLinkEntity.CODE_TYPE_VANITY ? "VANITY" : "HASH";
    }

    private static String status(int value) {
        return switch (value) {
            case ShortLinkEntity.STATUS_ACTIVE -> "ACTIVE";
            case ShortLinkEntity.STATUS_EXPIRED -> "EXPIRED";
            default -> "DELETED";
        };
    }
}
