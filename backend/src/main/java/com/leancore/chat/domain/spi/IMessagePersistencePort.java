package com.leancore.chat.domain.spi;

import com.leancore.chat.domain.model.HistoryQuery;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SendResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface IMessagePersistencePort {

    /**
     * Assigns the next seq of the conversation and inserts the message in one transaction.
     * The seq increment is guarded by the conversation mode, under the same row lock: BOT replies (and
     * SYSTEM notices replying to a customer message) need BOT mode, AGENT messages need HUMAN mode assigned
     * to their sender; otherwise ConversationModeException and no seq is consumed.
     * If (conversationId, clientMessageId) already exists, returns the stored message with duplicate = true.
     * Errors with ConversationNotFoundException when the conversation does not exist.
     */
    Mono<SendResult> append(NewMessage message);

    /** Messages with seq > afterSeq, ascending, at most limit. */
    Flux<MessageModel> findAfter(HistoryQuery query);

    /** The last {@code limit} messages with seq < beforeSeq, in ascending seq order. */
    Flux<MessageModel> findRecentBefore(UUID conversationId, long beforeSeq, int limit);

    /** Customer messages with seq > afterSeq that no BOT/SYSTEM message answers (via reply_to_seq), ascending seq. */
    Flux<MessageModel> findUnansweredCustomerMessages(UUID conversationId, long afterSeq);

    /** Conversations in BOT mode, active since the given instant, with unanswered customer messages for the bot. */
    Flux<UUID> findConversationsWithUnansweredMessages(Instant activeSince);
}
