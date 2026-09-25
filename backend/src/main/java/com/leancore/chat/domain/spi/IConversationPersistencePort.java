package com.leancore.chat.domain.spi;

import com.leancore.chat.domain.model.ConversationModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IConversationPersistencePort {

    Mono<ConversationModel> insert(ConversationModel conversation);

    Mono<ConversationModel> findById(UUID id);

    /** Most recent activity first. */
    Flux<ConversationModel> findAllByActivity();

    /** Sets HUMAN mode for the agent only if the conversation is in BOT mode; empty when it was not. */
    Mono<ConversationModel> takeOver(UUID id, String agentName);

    /**
     * Back to BOT mode only if the agent attends it; the bot then only answers seq > current last_seq.
     * Empty when it was not attended by that agent.
     */
    Mono<ConversationModel> release(UUID id, String agentName);
}
