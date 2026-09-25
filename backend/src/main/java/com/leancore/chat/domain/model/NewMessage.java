package com.leancore.chat.domain.model;

import java.util.UUID;

/** A message to append; the server assigns seq and createdAt. */
public record NewMessage(
        UUID conversationId,
        UUID clientMessageId,
        SenderRole senderRole,
        String senderName,
        String content,
        Long replyToSeq,
        Integer botScopeVersion,
        String botProvider,
        String botModel
) {
    public static NewMessage fromCustomer(UUID conversationId, UUID clientMessageId, String senderName, String content) {
        return new NewMessage(conversationId, clientMessageId, SenderRole.CUSTOMER, senderName, content, null, null, null, null);
    }

    /** A message from the server itself (e.g. "the assistant is not available"). */
    public static NewMessage fromSystem(UUID conversationId, UUID clientMessageId, String senderName, String content,
                                        Long replyToSeq) {
        return new NewMessage(conversationId, clientMessageId, SenderRole.SYSTEM, senderName, content, replyToSeq, null, null, null);
    }
}
