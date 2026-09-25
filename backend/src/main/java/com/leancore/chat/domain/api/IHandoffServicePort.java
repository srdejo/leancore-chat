package com.leancore.chat.domain.api;

import com.leancore.chat.domain.model.ConversationModel;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** A human agent takes a conversation from the bot and hands it back (design D15). */
public interface IHandoffServicePort {

    /** ConversationConflictException when another agent attends it; no effect for the same agent. */
    Mono<ConversationModel> takeOver(UUID conversationId, String agentName);

    /** ConversationConflictException when another agent attends it; no effect when the bot already does. */
    Mono<ConversationModel> handBack(UUID conversationId, String agentName);
}
