package com.leancore.chat.domain.model;

import java.util.UUID;

public record HistoryQuery(UUID conversationId, long afterSeq, int limit) {
}
