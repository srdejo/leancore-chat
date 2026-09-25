package com.leancore.chat.domain;

import com.leancore.chat.domain.api.IBotReplyServicePort;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.SendResult;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.usecase.MessageUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageUseCaseTest {

    private final UUID conversationId = UUID.randomUUID();
    private final UUID clientMessageId = UUID.randomUUID();

    private IMessagePersistencePort persistence;
    private IMessageBroadcastPort broadcast;
    private IBotReplyServicePort bot;
    private MessageUseCase useCase;

    @BeforeEach
    void setUp() {
        persistence = mock(IMessagePersistencePort.class);
        broadcast = mock(IMessageBroadcastPort.class);
        bot = mock(IBotReplyServicePort.class);
        useCase = new MessageUseCase(persistence, broadcast, bot);
    }

    @Test
    void newMessageIsPublishedAfterPersistingAndTriggersTheBot() {
        MessageModel stored = stored(7);
        when(persistence.append(any())).thenReturn(Mono.just(new SendResult(stored, false)));

        StepVerifier.create(useCase.sendCustomerMessage(conversationId, clientMessageId, "Ana", "Hola"))
                .expectNextMatches(result -> result.message().seq() == 7 && !result.duplicate())
                .verifyComplete();

        verify(broadcast).publish(new ChatEvent.MessageEvent(stored));
        verify(bot).requestReplies(conversationId);
    }

    @Test
    void duplicateIsAcknowledgedButNeitherPublishedNorAnswered() {
        when(persistence.append(any())).thenReturn(Mono.just(new SendResult(stored(7), true)));

        StepVerifier.create(useCase.sendCustomerMessage(conversationId, clientMessageId, "Ana", "Hola"))
                .expectNextMatches(result -> result.message().seq() == 7 && result.duplicate())
                .verifyComplete();

        verifyNoInteractions(broadcast, bot);
    }

    @Test
    void persistenceFailureIsNotPublished() {
        when(persistence.append(any())).thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(useCase.sendCustomerMessage(conversationId, clientMessageId, "Ana", "Hola"))
                .verifyError(IllegalStateException.class);

        verifyNoInteractions(broadcast, bot);
    }

    @Test
    void rejectsEmptyTooLongOrUnidentifiedContent() {
        StepVerifier.create(useCase.sendCustomerMessage(conversationId, clientMessageId, "Ana", "  "))
                .verifyError(ValidationException.class);
        StepVerifier.create(useCase.sendCustomerMessage(conversationId, clientMessageId, "Ana", "x".repeat(2001)))
                .verifyError(ValidationException.class);
        StepVerifier.create(useCase.sendCustomerMessage(conversationId, null, "Ana", "Hola"))
                .verifyError(ValidationException.class);

        verify(persistence, never()).append(any());
    }

    private MessageModel stored(long seq) {
        return new MessageModel(conversationId, seq, clientMessageId, SenderRole.CUSTOMER, "Ana", "Hola",
                null, null, null, null, Instant.now());
    }
}
