package com.leancore.chat.domain.usecase;

import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.HistoryQuery;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.Texts;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ConversationUseCase implements IConversationServicePort {

    private final IConversationPersistencePort conversationPersistencePort;
    private final IMessagePersistencePort messagePersistencePort;

    public ConversationUseCase(IConversationPersistencePort conversationPersistencePort,
                               IMessagePersistencePort messagePersistencePort) {
        this.conversationPersistencePort = conversationPersistencePort;
        this.messagePersistencePort = messagePersistencePort;
    }

    @Override
    public Mono<ConversationModel> createConversation(String customerName) {
        return Mono.fromCallable(() -> Texts.requireText(customerName, "El nombre", DomainConstants.NAME_MAX_LENGTH))
                .map(ConversationModel::newConversation)
                .flatMap(conversationPersistencePort::insert);
    }

    @Override
    public Mono<ConversationModel> getConversation(UUID id) {
        return conversationPersistencePort.findById(id)
                .switchIfEmpty(Mono.error(new ConversationNotFoundException()));
    }

    @Override
    public Flux<ConversationModel> listConversations() {
        return conversationPersistencePort.findAllByActivity();
    }

    @Override
    public Flux<MessageModel> getHistory(UUID conversationId, Long afterSeq, Integer limit) {
        return Mono.fromCallable(() -> toQuery(conversationId, afterSeq, limit))
                .flatMapMany(query -> getConversation(conversationId)
                        .thenMany(messagePersistencePort.findAfter(query)));
    }

    private HistoryQuery toQuery(UUID conversationId, Long afterSeq, Integer limit) {
        long after = afterSeq == null ? 0 : afterSeq;
        if (after < 0) {
            throw new ValidationException("afterSeq no puede ser negativo.");
        }
        int size = limit == null ? DomainConstants.HISTORY_DEFAULT_LIMIT : limit;
        if (size < 1) {
            throw new ValidationException("limit debe ser mayor que 0.");
        }
        return new HistoryQuery(conversationId, after, Math.min(size, DomainConstants.HISTORY_MAX_LIMIT));
    }
}
