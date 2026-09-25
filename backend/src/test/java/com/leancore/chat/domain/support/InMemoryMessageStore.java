package com.leancore.chat.domain.support;

import com.leancore.chat.domain.exception.ConversationModeException;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.model.ConversationMode;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.HistoryQuery;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SendResult;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of the conversation and message tables: same seq, idempotency and mode-guard rules as the
 * SQL adapters (design D2, D3 and D15).
 */
public class InMemoryMessageStore implements IMessagePersistencePort, IConversationPersistencePort {

    private final List<MessageModel> messages = new ArrayList<>();
    private final Map<UUID, ConversationModel> conversations = new ConcurrentHashMap<>();

    public UUID newConversation() {
        ConversationModel conversation = ConversationModel.newConversation("Ana");
        conversations.put(conversation.id(), conversation);
        return conversation.id();
    }

    public synchronized List<MessageModel> messages(UUID conversationId) {
        return messages.stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .sorted(Comparator.comparingLong(MessageModel::seq))
                .toList();
    }

    @Override
    public Mono<SendResult> append(NewMessage message) {
        return Mono.fromCallable(() -> appendNow(message));
    }

    private synchronized SendResult appendNow(NewMessage message) {
        ConversationModel conversation = conversations.get(message.conversationId());
        if (conversation == null) {
            throw new ConversationNotFoundException();
        }
        var existing = messages.stream()
                .filter(m -> m.conversationId().equals(message.conversationId())
                        && m.clientMessageId().equals(message.clientMessageId()))
                .findFirst();
        if (existing.isPresent()) {
            return new SendResult(existing.get(), true);
        }
        checkMode(conversation, message);
        long seq = messages(message.conversationId()).size() + 1L;
        MessageModel stored = new MessageModel(message.conversationId(), seq, message.clientMessageId(),
                message.senderRole(), message.senderName(), message.content(), message.replyToSeq(),
                message.botScopeVersion(), message.botProvider(), message.botModel(), Instant.now());
        messages.add(stored);
        conversations.put(conversation.id(), withLastSeq(conversation, seq));
        return new SendResult(stored, false);
    }

    private static void checkMode(ConversationModel conversation, NewMessage message) {
        boolean botWork = message.senderRole() == SenderRole.BOT
                || (message.senderRole() == SenderRole.SYSTEM && message.replyToSeq() != null);
        if (botWork && conversation.mode() != ConversationMode.BOT) {
            throw new ConversationModeException();
        }
        if (message.senderRole() == SenderRole.AGENT && !conversation.attendedBy(message.senderName())) {
            throw new ConversationModeException();
        }
    }

    @Override
    public Flux<MessageModel> findAfter(HistoryQuery query) {
        return Flux.defer(() -> Flux.fromIterable(messages(query.conversationId()).stream()
                .filter(message -> message.seq() > query.afterSeq())
                .limit(query.limit())
                .toList()));
    }

    @Override
    public Flux<MessageModel> findRecentBefore(UUID conversationId, long beforeSeq, int limit) {
        return Flux.defer(() -> {
            List<MessageModel> before = messages(conversationId).stream().filter(m -> m.seq() < beforeSeq).toList();
            return Flux.fromIterable(before.subList(Math.max(0, before.size() - limit), before.size()));
        });
    }

    @Override
    public Flux<MessageModel> findUnansweredCustomerMessages(UUID conversationId, long afterSeq) {
        return Flux.defer(() -> {
            List<MessageModel> all = messages(conversationId);
            return Flux.fromIterable(all.stream()
                    .filter(m -> m.senderRole() == SenderRole.CUSTOMER && m.seq() > afterSeq)
                    .filter(m -> all.stream().noneMatch(r -> Long.valueOf(m.seq()).equals(r.replyToSeq())))
                    .toList());
        });
    }

    @Override
    public Flux<UUID> findConversationsWithUnansweredMessages(Instant activeSince) {
        return Flux.fromIterable(conversations.values())
                .filter(conversation -> conversation.mode() == ConversationMode.BOT)
                .filterWhen(c -> findUnansweredCustomerMessages(c.id(), c.botResumeAfterSeq()).hasElements())
                .map(ConversationModel::id);
    }

    // --- IConversationPersistencePort

    @Override
    public Mono<ConversationModel> insert(ConversationModel conversation) {
        conversations.put(conversation.id(), conversation);
        return Mono.just(conversation);
    }

    @Override
    public Mono<ConversationModel> findById(UUID id) {
        return Mono.justOrEmpty(conversations.get(id));
    }

    @Override
    public Flux<ConversationModel> findAllByActivity() {
        return Flux.fromIterable(conversations.values());
    }

    @Override
    public synchronized Mono<ConversationModel> takeOver(UUID id, String agentName) {
        ConversationModel c = conversations.get(id);
        if (c == null || c.mode() != ConversationMode.BOT) {
            return Mono.empty();
        }
        ConversationModel taken = new ConversationModel(c.id(), c.customerName(), c.lastSeq(), c.lastSenderRole(),
                c.lastPreview(), c.createdAt(), c.lastActivityAt(), ConversationMode.HUMAN, agentName, c.botResumeAfterSeq());
        conversations.put(id, taken);
        return Mono.just(taken);
    }

    @Override
    public synchronized Mono<ConversationModel> release(UUID id, String agentName) {
        ConversationModel c = conversations.get(id);
        if (c == null || !c.attendedBy(agentName)) {
            return Mono.empty();
        }
        ConversationModel released = new ConversationModel(c.id(), c.customerName(), c.lastSeq(), c.lastSenderRole(),
                c.lastPreview(), c.createdAt(), c.lastActivityAt(), ConversationMode.BOT, null, c.lastSeq());
        conversations.put(id, released);
        return Mono.just(released);
    }

    private static ConversationModel withLastSeq(ConversationModel c, long seq) {
        return new ConversationModel(c.id(), c.customerName(), seq, c.lastSenderRole(), c.lastPreview(),
                c.createdAt(), c.lastActivityAt(), c.mode(), c.agentName(), c.botResumeAfterSeq());
    }
}
