package com.leancore.chat.domain.exception;

/**
 * A message was rejected because the conversation is not in the mode its sender needs: a bot reply
 * while a human attends it, or an agent message from someone the conversation is not assigned to.
 */
public class ConversationModeException extends DomainException {
    public ConversationModeException() {
        super("La conversación no está asignada a este remitente.");
    }
}
