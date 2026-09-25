package com.leancore.chat.domain.api;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IBotReplyServicePort {

    /**
     * Schedules the bot to answer every customer message of the conversation that has no reply yet.
     * Work is serialized per conversation; calling it again while work is pending is harmless.
     */
    void requestReplies(UUID conversationId);

    /** Schedules every recently active conversation that has unanswered customer messages. */
    Mono<Void> recoverPendingReplies();
}
