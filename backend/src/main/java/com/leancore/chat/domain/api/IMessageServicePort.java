package com.leancore.chat.domain.api;

import com.leancore.chat.domain.model.SendResult;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IMessageServicePort {

    /**
     * Appends a customer message. Idempotent by (conversation, clientMessageId): a retry returns the
     * original seq and is neither broadcast again nor answered again by the bot.
     */
    Mono<SendResult> sendCustomerMessage(UUID conversationId, UUID clientMessageId, String senderName, String content);

    /** Appends a message from the agent attending the conversation; ConversationModeException if not assigned. */
    Mono<SendResult> sendAgentMessage(UUID conversationId, UUID clientMessageId, String agentName, String content);
}
