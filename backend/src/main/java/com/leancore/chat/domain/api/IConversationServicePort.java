package com.leancore.chat.domain.api;

import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.MessageModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IConversationServicePort {

    Mono<ConversationModel> createConversation(String customerName);

    Mono<ConversationModel> getConversation(UUID id);

    /** Most recent activity first. */
    Flux<ConversationModel> listConversations();

    /** Messages with seq > afterSeq in ascending order; limit defaults to 100 and is capped at 500. */
    Flux<MessageModel> getHistory(UUID conversationId, Long afterSeq, Integer limit);
}
