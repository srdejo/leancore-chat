package com.leancore.chat.domain;

import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.HistoryQuery;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.usecase.ConversationUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationUseCaseTest {

    private IConversationPersistencePort conversations;
    private IMessagePersistencePort messages;
    private ConversationUseCase useCase;

    @BeforeEach
    void setUp() {
        conversations = mock(IConversationPersistencePort.class);
        messages = mock(IMessagePersistencePort.class);
        useCase = new ConversationUseCase(conversations, messages);
        when(conversations.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void createsConversationWithStrippedName() {
        StepVerifier.create(useCase.createConversation("  Ana  "))
                .assertNext(conversation -> {
                    assertThat(conversation.customerName()).isEqualTo("Ana");
                    assertThat(conversation.id()).isNotNull();
                    assertThat(conversation.lastSeq()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void rejectsBlankOrTooLongName() {
        StepVerifier.create(useCase.createConversation("   ")).verifyError(ValidationException.class);
        StepVerifier.create(useCase.createConversation("x".repeat(61))).verifyError(ValidationException.class);
        verify(conversations, never()).insert(any());
    }

    @Test
    void historyOfMissingConversationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(conversations.findById(id)).thenReturn(Mono.empty());
        when(messages.findAfter(any())).thenReturn(Flux.empty());

        StepVerifier.create(useCase.getHistory(id, 0L, 10)).verifyError(ConversationNotFoundException.class);
    }

    @Test
    void historyAppliesDefaultAndMaximumLimit() {
        UUID id = UUID.randomUUID();
        when(conversations.findById(id)).thenReturn(Mono.just(ConversationModel.newConversation("Ana")));
        when(messages.findAfter(any())).thenReturn(Flux.empty());
        ArgumentCaptor<HistoryQuery> query = ArgumentCaptor.forClass(HistoryQuery.class);

        StepVerifier.create(useCase.getHistory(id, null, null)).verifyComplete();
        StepVerifier.create(useCase.getHistory(id, 3L, 10_000)).verifyComplete();

        verify(messages, org.mockito.Mockito.times(2)).findAfter(query.capture());
        assertThat(query.getAllValues().get(0)).isEqualTo(new HistoryQuery(id, 0, 100));
        assertThat(query.getAllValues().get(1)).isEqualTo(new HistoryQuery(id, 3, 500));
    }

    @Test
    void historyRejectsNegativeAfterSeqAndNonPositiveLimit() {
        UUID id = UUID.randomUUID();
        StepVerifier.create(useCase.getHistory(id, -1L, 10)).verifyError(ValidationException.class);
        StepVerifier.create(useCase.getHistory(id, 0L, 0)).verifyError(ValidationException.class);
    }
}
