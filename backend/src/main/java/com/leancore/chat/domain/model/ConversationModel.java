package com.leancore.chat.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * @param mode              BOT (the assistant answers) or HUMAN (agentName answers)
 * @param botResumeAfterSeq the bot only answers customer messages with a greater seq
 */
public record ConversationModel(
        UUID id,
        String customerName,
        long lastSeq,
        SenderRole lastSenderRole,
        String lastPreview,
        Instant createdAt,
        Instant lastActivityAt,
        ConversationMode mode,
        String agentName,
        long botResumeAfterSeq
) {
    public static ConversationModel newConversation(String customerName) {
        return new ConversationModel(UUID.randomUUID(), customerName, 0, null, null, null, null,
                ConversationMode.BOT, null, 0);
    }

    public boolean attendedBy(String agent) {
        return mode == ConversationMode.HUMAN && agent != null && agent.equals(agentName);
    }
}
