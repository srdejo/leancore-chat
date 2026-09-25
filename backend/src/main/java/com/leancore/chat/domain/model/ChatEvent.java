package com.leancore.chat.domain.model;

import java.util.UUID;

/** What the live broadcast carries: persisted messages and the ephemeral "bot typing" signal. */
public sealed interface ChatEvent {

    UUID conversationId();

    record MessageEvent(MessageModel message) implements ChatEvent {
        @Override
        public UUID conversationId() {
            return message.conversationId();
        }
    }

    record TypingEvent(UUID conversationId, boolean active) implements ChatEvent {
    }

    /** Ephemeral: who attends the conversation changed (the persisted SYSTEM notice carries the seq). */
    record ModeEvent(UUID conversationId, ConversationMode mode, String agentName) implements ChatEvent {
    }
}
