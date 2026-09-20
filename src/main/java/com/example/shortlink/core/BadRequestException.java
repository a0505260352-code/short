package com.example.shortlink.core;

/** The request itself is unusable: bad target URL, rejected short code, malformed validity. -> 400 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
