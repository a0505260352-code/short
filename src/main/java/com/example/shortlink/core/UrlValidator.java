package com.example.shortlink.core;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Decides whether a caller may point a short link at a target.
 *
 * <p>Blocking non-public address classes matters here for reasons beyond an open redirect: the service
 * runs inside the same network as MySQL, Redis and RocketMQ, so a target of {@code http://127.0.0.1:9876}
 * turns every redirect into a server-side request the operator never intended to allow.
 *
 * <p>The host is resolved and <em>every</em> returned address must be public, and a name that does not
 * resolve is rejected rather than waved through — an unverifiable target is treated as unsafe.
 *
 * <p>Known gap: resolution happens at creation time, so DNS rebinding can still move a previously public
 * host onto an internal address afterwards. Closing that needs a fetch-and-validate step or an egress
 * proxy, which is out of scope for v1.
 */
@Component
public class UrlValidator {

    private static final int MAX_LENGTH = 2_000;

    public String validate(String rawUrl) {
        String url = rawUrl == null ? null : rawUrl.trim();
        if (url == null || url.isEmpty()) {
            throw new BadRequestException("url is required");
        }
        if (url.length() > MAX_LENGTH) {
            throw new BadRequestException("url exceeds " + MAX_LENGTH + " characters");
        }
        URI uri = parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BadRequestException("only http and https targets are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new BadRequestException("url must not carry credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url must name a host");
        }
        requirePublicAddress(host);
        return url;
    }

    private URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new BadRequestException("url is not parseable: " + e.getReason());
        }
    }

    private void requirePublicAddress(String host) {
        String normalised = host.toLowerCase(Locale.ROOT);
        if (normalised.startsWith("[") && normalised.endsWith("]")) {
            normalised = normalised.substring(1, normalised.length() - 1);
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalised);
        } catch (UnknownHostException e) {
            throw new BadRequestException("host does not resolve: " + host);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new BadRequestException("target host is not publicly reachable: " + host);
            }
        }
    }

    /**
     * {@code InetAddress} has no "is this routable" predicate, and the built-in site-local check misses
     * two ranges that matter on the networks this service runs inside: carrier-grade NAT (100.64/10) and
     * IPv6 unique local addressing (fc00::/7), which Java's deprecated {@code isSiteLocalAddress} does
     * not report for.
     */
    private static boolean isNonPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            return (octets[0] & 0xFF) == 100 && (octets[1] & 0xC0) == 64;
        }
        return (octets[0] & 0xFE) == 0xFC;
    }
}
