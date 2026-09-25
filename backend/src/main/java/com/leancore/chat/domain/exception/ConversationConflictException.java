package com.leancore.chat.domain.exception;

/** Take over / hand back rejected because another agent attends the conversation. Maps to HTTP 409. */
public class ConversationConflictException extends DomainException {

    private final String agentName;

    public ConversationConflictException(String agentName) {
        super("La conversación la atiende " + agentName + ".");
        this.agentName = agentName;
    }

    public String agentName() {
        return agentName;
    }
}
