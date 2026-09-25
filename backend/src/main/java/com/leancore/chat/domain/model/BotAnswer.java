package com.leancore.chat.domain.model;

/**
 * Structured answer from the LLM: whether the question is inside the configured topic, and the reply.
 * provider/model tell which LLM produced it (null when unknown).
 */
public record BotAnswer(boolean inScope, String reply, String provider, String model) {

    public BotAnswer(boolean inScope, String reply) {
        this(inScope, reply, null, null);
    }
}
