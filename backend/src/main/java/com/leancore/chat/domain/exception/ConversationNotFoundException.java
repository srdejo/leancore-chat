package com.leancore.chat.domain.exception;

public class ConversationNotFoundException extends DomainException {
    public ConversationNotFoundException() {
        super("La conversación no existe.");
    }
}
