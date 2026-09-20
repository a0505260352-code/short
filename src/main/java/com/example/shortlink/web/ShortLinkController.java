package com.example.shortlink.web;

import com.example.shortlink.core.ShortLinkService;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import com.example.shortlink.web.dto.CreateLinkRequest;
import com.example.shortlink.web.dto.LinkResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
public class ShortLinkController {

    private final ShortLinkService service;
    private final String baseUrl;

    public ShortLinkController(ShortLinkService service, @Value("${shortlink.base-url}") String baseUrl) {
        this.service = service;
        this.baseUrl = baseUrl;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        LocalDateTime validUntil = request.validUntil() == null
                ? null
                : LocalDateTime.ofInstant(request.validUntil(), ZoneOffset.UTC);
        ShortLinkEntity created = service.create(request.url(), request.code(), validUntil);
        LinkResponse body = LinkResponse.of(created, baseUrl);
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }

    @GetMapping("/{code}/stats")
    public LinkResponse stats(@PathVariable String code) {
        return LinkResponse.of(service.requireByCode(code), baseUrl);
    }
}
