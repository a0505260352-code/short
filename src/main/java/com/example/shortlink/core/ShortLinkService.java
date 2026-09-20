package com.example.shortlink.core;

import com.example.shortlink.bloom.BloomService;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import com.example.shortlink.persistence.mapper.ShortLinkMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Creates links and answers the question the redirect path asks on every hit: may this code forward
 * right now?
 */
@Service
public class ShortLinkService {

    private final ShortLinkMapper mapper;
    private final ShortCodeGenerator generator;
    private final BloomService bloom;
    private final LinkCache cache;
    private final UrlValidator urlValidator;
    private final VanityCodePolicy vanityPolicy;
    private final Clock clock;

    public ShortLinkService(ShortLinkMapper mapper, ShortCodeGenerator generator, BloomService bloom,
                            LinkCache cache, UrlValidator urlValidator, VanityCodePolicy vanityPolicy,
                            Clock clock) {
        this.mapper = mapper;
        this.generator = generator;
        this.bloom = bloom;
        this.cache = cache;
        this.urlValidator = urlValidator;
        this.vanityPolicy = vanityPolicy;
        this.clock = clock;
    }

    public ShortLinkEntity create(String url, String vanityCode, LocalDateTime validUntil) {
        String target = urlValidator.validate(url);
        if (validUntil != null && !validUntil.isAfter(now())) {
            throw new BadRequestException("validUntil must be in the future");
        }
        ShortLinkEntity stored = vanityCode == null || vanityCode.isBlank()
                ? createHashed(target, validUntil)
                : createVanity(target, vanityCode, validUntil);
        // Read the row back rather than returning the object we inserted: created_at, updated_at and
        // click_count are server-assigned, and the response should report what is actually stored.
        return requireByCode(stored.getCode());
    }

    private ShortLinkEntity createHashed(String target, LocalDateTime validUntil) {
        return generator.issue(draft(target, ShortLinkEntity.CODE_TYPE_HASH, validUntil));
    }

    /**
     * A taken vanity code is a 409, never a re-roll: the caller asked for this exact code, so handing
     * back something else silently would create a different link than the one they tried to make.
     */
    private ShortLinkEntity createVanity(String target, String vanityCode, LocalDateTime validUntil) {
        vanityPolicy.validate(vanityCode);
        ShortLinkEntity entity = draft(target, ShortLinkEntity.CODE_TYPE_VANITY, validUntil);
        entity.setCode(vanityCode);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new VanityCodeConflictException(vanityCode);
        }
        // The filter holds every issued code, vanity included. Hash codes are always 6 chars and the
        // policy rejects 6-char vanity codes, so today the two sets cannot collide; registering the
        // vanity code keeps that invariant true if the length reservation is ever relaxed.
        bloom.add(vanityCode);
        // A code that 404ed a moment ago may well sit in the filter as a known-absent sentinel.
        cache.evict(vanityCode);
        return entity;
    }

    public String resolveTargetOrThrow(String code) {
        return switch (cache.find(code)) {
            case LinkCache.Cached.Present present -> present.url();
            case LinkCache.Cached.KnownAbsent ignored -> throw new LinkNotFoundException();
            case LinkCache.Cached.NotCached ignored -> loadFromDb(code);
        };
    }

    private String loadFromDb(String code) {
        ShortLinkEntity entity = mapper.findByCode(code);
        if (entity == null || !isLive(entity)) {
            cache.storeAbsent(code);
            throw new LinkNotFoundException();
        }
        cache.store(code, entity.getOriginalUrl(), entity.getValidUntil());
        return entity.getOriginalUrl();
    }

    public ShortLinkEntity requireByCode(String code) {
        ShortLinkEntity entity = mapper.findByCode(code);
        if (entity == null) {
            throw new LinkNotFoundException();
        }
        return entity;
    }

    private boolean isLive(ShortLinkEntity entity) {
        return entity.getStatus() != null && entity.getStatus() == ShortLinkEntity.STATUS_ACTIVE
                && (entity.getValidUntil() == null || entity.getValidUntil().isAfter(now()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ShortLinkEntity draft(String target, int codeType, LocalDateTime validUntil) {
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setOriginalUrl(target);
        entity.setCodeType(codeType);
        entity.setStatus(ShortLinkEntity.STATUS_ACTIVE);
        entity.setValidUntil(validUntil);
        return entity;
    }
}
