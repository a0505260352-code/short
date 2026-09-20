package com.example.shortlink.web;

import com.example.shortlink.core.BadRequestException;
import com.example.shortlink.core.CodeExhaustedException;
import com.example.shortlink.core.LinkNotFoundException;
import com.example.shortlink.core.VanityCodeConflictException;
import com.example.shortlink.ratelimit.RateLimitUnavailableException;
import com.example.shortlink.ratelimit.RateLimitedException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidPayload(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, details);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ProblemDetail notFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(VanityCodeConflictException.class)
    public ProblemDetail conflict(VanityCodeConflictException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CodeExhaustedException.class)
    public ProblemDetail exhausted(CodeExhaustedException e) {
        // 503 not 500: the request was fine, the code space was momentarily not.
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "could not allocate a short code, retry shortly");
    }

    /**
     * {@code ResponseEntity} rather than a bare {@code ProblemDetail} because ProblemDetail carries no
     * arbitrary headers, and a 429 without Retry-After just makes the client guess.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        ProblemDetail body = problem(HttpStatus.TOO_MANY_REQUESTS,
                "rate limit exceeded, retry after " + e.retryAfterSeconds() + "s");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ProblemDetail limiterDown(RateLimitUnavailableException e) {
        // Only fail-closed routes reach here — the redirect path swallows this and serves the click.
        log.warn("Rate limiter unavailable, rejecting request: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service temporarily unavailable, retry shortly");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
