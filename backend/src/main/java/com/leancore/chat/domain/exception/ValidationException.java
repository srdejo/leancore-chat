package com.leancore.chat.domain.exception;

/** Invalid input rejected by a domain rule; maps to HTTP 400 / WebSocket ERROR VALIDATION. */
public class ValidationException extends DomainException {
    public ValidationException(String message) {
        super(message);
    }
}
