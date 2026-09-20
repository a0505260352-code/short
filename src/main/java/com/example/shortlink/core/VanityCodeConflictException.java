package com.example.shortlink.core;

/** The requested custom code is already taken. -> 409 */
public class VanityCodeConflictException extends RuntimeException {

    public VanityCodeConflictException(String code) {
        super("Custom code " + code + " is already in use");
    }
}
