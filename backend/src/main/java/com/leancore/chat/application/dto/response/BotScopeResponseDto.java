package com.leancore.chat.application.dto.response;

import java.time.Instant;
import java.util.List;

public record BotScopeResponseDto(
        int version,
        String topic,
        String description,
        List<String> subtopics,
        String refusalMessage,
        Instant createdAt
) {
}
