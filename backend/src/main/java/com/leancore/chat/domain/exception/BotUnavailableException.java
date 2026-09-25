package com.leancore.chat.domain.exception;

/** The LLM cannot be used: no API key, provider error or timeout. */
public class BotUnavailableException extends DomainException {
    public BotUnavailableException(String message) {
        super(message);
    }
}
