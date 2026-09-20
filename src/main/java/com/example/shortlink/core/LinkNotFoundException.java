package com.example.shortlink.core;

/** No live row for this code, or none at all. -> 404 */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException() {
        // The offending code stays out of the message: it is caller-controlled text and it is already
        // in the request line of the access log.
        super("link not found");
    }
}

