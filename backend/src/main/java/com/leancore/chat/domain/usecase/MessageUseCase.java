package com.leancore.chat.domain.usecase;

import com.leancore.chat.domain.api.IBotReplyServicePort;
import com.leancore.chat.domain.api.IMessageServicePort;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SendResult;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.Texts;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class MessageUseCase implements IMessageServicePort {

    private final IMessagePersistencePort messagePersistencePort;
    private final IMessageBroadcastPort messageBroadcastPort;
    private final IBotReplyServicePort botReplyServicePort;

    public MessageUseCase(IMessagePersistencePort messagePersistencePort,
                          IMessageBroadcastPort messageBroadcastPort,
                          IBotReplyServicePort botReplyServicePort) {
        this.messagePersistencePort = messagePersistencePort;
        this.messageBroadcastPort = messageBroadcastPort;
        this.botReplyServicePort = botReplyServicePort;
    }

    @Override
    public Mono<SendResult> sendCustomerMessage(UUID conversationId, UUID clientMessageId, String senderName, String content) {
        return Mono.fromCallable(() -> toNewMessage(conversationId, clientMessageId, senderName, content))
                .flatMap(messagePersistencePort::append)
                .doOnNext(result -> {
                    // A duplicate is a retry of something already delivered and answered: acknowledge only.
                    if (!result.duplicate()) {
                        messageBroadcastPort.publish(new ChatEvent.MessageEvent(result.message()));
                        botReplyServicePort.requestReplies(conversationId);
                    }
                });
    }

    @Override
    public Mono<SendResult> sendAgentMessage(UUID conversationId, UUID clientMessageId, String agentName, String content) {
        // The append itself checks, under the conversation lock, that the agent attends it.
        return Mono.fromCallable(() -> toNewMessage(conversationId, clientMessageId, agentName, content))
                .map(customer -> new NewMessage(conversationId, clientMessageId, SenderRole.AGENT, customer.senderName(),
                        customer.content(), null, null, null, null))
                .flatMap(messagePersistencePort::append)
                .doOnNext(result -> {
                    if (!result.duplicate()) {
                        messageBroadcastPort.publish(new ChatEvent.MessageEvent(result.message()));
                    }
                });
    }

    private NewMessage toNewMessage(UUID conversationId, UUID clientMessageId, String senderName, String content) {
        if (clientMessageId == null) {
            throw new ValidationException("clientMessageId es obligatorio.");
        }
        String name = Texts.requireText(senderName, "El nombre", DomainConstants.NAME_MAX_LENGTH);
        String text = Texts.requireText(content, "El mensaje", DomainConstants.CONTENT_MAX_LENGTH);
        return NewMessage.fromCustomer(conversationId, clientMessageId, name, text);
    }
}
