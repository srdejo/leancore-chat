package com.leancore.chat.application.dto.response;

import java.time.Instant;
import java.util.UUID;

public record MessageResponseDto(
        long seq,
        UUID clientMessageId,
        String senderRole,
        String senderName,
        String content,
        Long replyToSeq,
        Integer botScopeVersion,
        String botProvider,
        String botModel,
        Instant createdAt
) {
}
