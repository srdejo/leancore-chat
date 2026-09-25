package com.leancore.chat.domain.usecase;

import com.leancore.chat.domain.api.IHandoffServicePort;
import com.leancore.chat.domain.exception.ConversationConflictException;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.ConversationMode;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.Texts;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Mode changes are conditional updates (only one of two simultaneous take-overs wins) followed by a
 * SYSTEM notice with its own seq, so every view sees the change at the same point of the conversation.
 */
public class HandoffUseCase implements IHandoffServicePort {

    static final String JOINED = "%s se unió a la conversación. Ahora estás hablando con una persona.";
    static final String BOT_RESUMES = "El asistente automático retoma la conversación.";

    private final IConversationPersistencePort conversationPersistencePort;
    private final IMessagePersistencePort messagePersistencePort;
    private final IMessageBroadcastPort messageBroadcastPort;

    public HandoffUseCase(IConversationPersistencePort conversationPersistencePort,
                          IMessagePersistencePort messagePersistencePort,
                          IMessageBroadcastPort messageBroadcastPort) {
        this.conversationPersistencePort = conversationPersistencePort;
        this.messagePersistencePort = messagePersistencePort;
        this.messageBroadcastPort = messageBroadcastPort;
    }

    @Override
    public Mono<ConversationModel> takeOver(UUID conversationId, String agentName) {
        return Mono.fromCallable(() -> Texts.requireText(agentName, "El nombre del agente", DomainConstants.NAME_MAX_LENGTH))
                .flatMap(agent -> conversationPersistencePort.takeOver(conversationId, agent)
                        .flatMap(taken -> announce(taken, JOINED.formatted(agent)))
                        .switchIfEmpty(Mono.defer(() -> current(conversationId).flatMap(conversation ->
                                conversation.attendedBy(agent)
                                        ? Mono.just(conversation)
                                        : Mono.error(new ConversationConflictException(conversation.agentName()))))));
    }

    @Override
    public Mono<ConversationModel> handBack(UUID conversationId, String agentName) {
        return Mono.fromCallable(() -> Texts.requireText(agentName, "El nombre del agente", DomainConstants.NAME_MAX_LENGTH))
                .flatMap(agent -> conversationPersistencePort.release(conversationId, agent)
                        .flatMap(released -> announce(released, BOT_RESUMES))
                        .switchIfEmpty(Mono.defer(() -> current(conversationId).flatMap(conversation ->
                                conversation.mode() == ConversationMode.BOT
                                        ? Mono.just(conversation)
                                        : Mono.error(new ConversationConflictException(conversation.agentName()))))));
    }

    private Mono<ConversationModel> current(UUID conversationId) {
        return conversationPersistencePort.findById(conversationId)
                .switchIfEmpty(Mono.error(new ConversationNotFoundException()));
    }

    /** Notice first (it carries the seq), then the ephemeral mode event for the views' state. */
    private Mono<ConversationModel> announce(ConversationModel conversation, String notice) {
        NewMessage message = NewMessage.fromSystem(conversation.id(), UUID.randomUUID(),
                DomainConstants.SYSTEM_SENDER_NAME, notice, null);
        return messagePersistencePort.append(message)
                .doOnNext(result -> {
                    messageBroadcastPort.publish(new ChatEvent.MessageEvent(result.message()));
                    messageBroadcastPort.publish(new ChatEvent.ModeEvent(conversation.id(), conversation.mode(),
                            conversation.agentName()));
                })
                .thenReturn(conversation);
    }
}
