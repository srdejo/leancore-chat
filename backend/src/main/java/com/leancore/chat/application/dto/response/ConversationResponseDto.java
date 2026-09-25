package com.leancore.chat.application.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponseDto(
        UUID id,
        String customerName,
        long lastSeq,
        String lastSenderRole,
        String lastPreview,
        Instant createdAt,
        Instant lastActivityAt,
        String mode,
        String agentName
) {
}
