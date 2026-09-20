package com.example.shortlink.core;

import com.example.shortlink.codec.CodeHasher;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Acceptance rules for user-supplied codes.
 *
 * <p>Six characters are refused outright rather than probed for collisions. Hash codes are always
 * exactly six long, so keeping that length for the generated family makes the two families disjoint by
 * length alone: code type is then readable from the code without a query, and a vanity code can never
 * be reissued later as a hash.
 *
 * <p>A vanity code that equals a route prefix is not a routing hazard — literal patterns win over
 * {@code /{code}} — it is a link that silently never redirects. The reserved set is therefore checked
 * against the actual handler mappings at startup, so adding a top-level route without reserving its
 * prefix fails fast instead of leaving a hole.
 */
@Component
public class VanityCodePolicy {

    private static final Pattern SHAPE = Pattern.compile("[0-9A-Za-z]{4,12}");

    private static final Set<String> RESERVED =
            Set.of("api", "actuator", "health", "metrics", "info", "error");

    private final List<RequestMappingInfoHandlerMapping> mappings;

    public VanityCodePolicy(List<RequestMappingInfoHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    public void validate(String code) {
        if (!SHAPE.matcher(code).matches()) {
            throw new BadRequestException("custom code must be 4-12 characters of 0-9, A-Z or a-z");
        }
        if (code.length() == CodeHasher.HASH_CODE_LENGTH) {
            throw new BadRequestException("6-character codes are reserved for generated links");
        }
        if (RESERVED.contains(code.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("this code is reserved");
        }
    }

    @EventListener
    public void assertReservedSetCoversEveryRoutePrefix(ApplicationReadyEvent event) {
        for (RequestMappingInfoHandlerMapping mapping : mappings) {
            mapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(path -> {
                String root = rootSegment(path);
                if (root != null && !RESERVED.contains(root.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException(
                            "Route /" + root + "/... is exposed but the prefix is not a reserved code;"
                                    + " add \"" + root + "\" to VanityCodePolicy.RESERVED");
                }
            }));
        }
    }

    /** The literal first segment of a pattern, or null when it is a path variable or the root. */
    private static String rootSegment(String pattern) {
        String[] parts = pattern.split("/");
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        String first = parts[1];
        return first.indexOf('{') >= 0 ? null : first;
    }
}
