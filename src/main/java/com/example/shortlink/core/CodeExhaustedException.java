package com.example.shortlink.core;

/**
 * The retry ring produced a colliding code on every attempt. Maps to 503: the request is valid, the
 * code space just would not accommodate it right now, and a retry may succeed.
 */
public class CodeExhaustedException extends RuntimeException {

    public CodeExhaustedException(int attempts) {
        super("No free short code after " + attempts + " attempts");
    }
}
