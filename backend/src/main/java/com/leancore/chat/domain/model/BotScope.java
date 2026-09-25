package com.leancore.chat.domain.model;

import java.time.Instant;
import java.util.List;

public record BotScope(
        Integer version,
        String topic,
        String description,
        List<String> subtopics,
        String refusalMessage,
        Instant createdAt
) {
    public BotScope {
        subtopics = subtopics == null ? List.of() : List.copyOf(subtopics);
    }
}
