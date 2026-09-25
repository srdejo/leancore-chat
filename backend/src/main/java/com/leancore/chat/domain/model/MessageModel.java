package com.leancore.chat.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted message; its seq is the only ordering criterion inside a conversation.
 * botScopeVersion, botProvider and botModel are only set on BOT replies.
 */
public record MessageModel(
        UUID conversationId,
        long seq,
        UUID clientMessageId,
        SenderRole senderRole,
        String senderName,
        String content,
        Long replyToSeq,
        Integer botScopeVersion,
        String botProvider,
        String botModel,
        Instant createdAt
) {
}
