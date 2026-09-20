package com.example.shortlink.web;

import com.example.shortlink.core.ShortLinkService;
import com.example.shortlink.mq.ClickProducer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final ShortLinkService service;
    private final ClickProducer clicks;

    public RedirectController(ShortLinkService service, ClickProducer clicks) {
        this.service = service;
        this.clicks = clicks;
    }

    /**
     * 302, and deliberately never 301. A permanent redirect is cached by the browser and then served
     * without ever reaching this service, which would drop every click after the first and leave no way
     * to revoke a link or let it expire.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        URI target = URI.create(service.resolveTargetOrThrow(code));
        // Only a forwarded visit is a click; a 404 is not someone choosing to follow the link.
        // getRemoteAddr() is the peer socket, which behind a proxy is the proxy's own address: reading
        // X-Forwarded-For instead would let a client forge the origin both of this record and of the
        // rate limiter's key, so the trusted hop has to be chosen together with the deployment topology.
        clicks.publish(code, request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader("Referer"));
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }
}
