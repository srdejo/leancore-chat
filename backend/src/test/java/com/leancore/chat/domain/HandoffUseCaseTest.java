package com.leancore.chat.domain;

import com.leancore.chat.domain.exception.ConversationConflictException;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.ConversationMode;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.support.InMemoryMessageStore;
import com.leancore.chat.domain.usecase.HandoffUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffUseCaseTest {

    private InMemoryMessageStore store;
    private List<ChatEvent> events;
    private HandoffUseCase useCase;
    private UUID conversation;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessageStore();
        events = Collections.synchronizedList(new ArrayList<>());
        IMessageBroadcastPort broadcast = new IMessageBroadcastPort() {
            @Override
            public void publish(ChatEvent event) {
                events.add(event);
            }

            @Override
            public Flux<ChatEvent> stream(UUID conversationId) {
                return Flux.empty();
            }
        };
        useCase = new HandoffUseCase(store, store, broadcast);
        conversation = store.newConversation();
        store.append(NewMessage.fromCustomer(conversation, UUID.randomUUID(), "Ana", "Hola")).block();
    }

    @Test
    void takingOverAssignsTheAgentAndNotifiesWithASequencedSystemMessage() {
        StepVerifier.create(useCase.takeOver(conversation, "  Luis "))
                .assertNext(taken -> {
                    assertThat(taken.mode()).isEqualTo(ConversationMode.HUMAN);
                    assertThat(taken.agentName()).isEqualTo("Luis");
                })
                .verifyComplete();

        MessageModel notice = store.messages(conversation).getLast();
        assertThat(notice.seq()).isEqualTo(2);
        assertThat(notice.senderRole()).isEqualTo(SenderRole.SYSTEM);
        assertThat(notice.content()).isEqualTo("Luis se unió a la conversación. Ahora estás hablando con una persona.");
        assertThat(events).containsExactly(
                new ChatEvent.MessageEvent(notice),
                new ChatEvent.ModeEvent(conversation, ConversationMode.HUMAN, "Luis"));
    }

    @Test
    void anotherAgentGetsAConflictAndTheSameAgentIsIdempotent() {
        useCase.takeOver(conversation, "Luis").block();

        StepVerifier.create(useCase.takeOver(conversation, "Marta"))
                .verifyErrorSatisfies(error -> assertThat(((ConversationConflictException) error).agentName()).isEqualTo("Luis"));
        StepVerifier.create(useCase.takeOver(conversation, "Luis"))
                .assertNext(same -> assertThat(same.agentName()).isEqualTo("Luis"))
                .verifyComplete();
        assertThat(store.messages(conversation)).hasSize(2); // a single notice
    }

    @Test
    void handingBackReturnsToTheBotFromTheCurrentSeq() {
        useCase.takeOver(conversation, "Luis").block();
        store.append(NewMessage.fromCustomer(conversation, UUID.randomUUID(), "Ana", "¿Sigues?")).block();

        StepVerifier.create(useCase.handBack(conversation, "Luis"))
                .assertNext(released -> {
                    assertThat(released.mode()).isEqualTo(ConversationMode.BOT);
                    assertThat(released.agentName()).isNull();
                    assertThat(released.botResumeAfterSeq()).isEqualTo(3);
                })
                .verifyComplete();
        assertThat(store.messages(conversation).getLast().content())
                .isEqualTo("El asistente automático retoma la conversación.");
        assertThat(events.getLast()).isEqualTo(new ChatEvent.ModeEvent(conversation, ConversationMode.BOT, null));
    }

    @Test
    void onlyTheAssignedAgentCanHandItBack() {
        useCase.takeOver(conversation, "Luis").block();

        StepVerifier.create(useCase.handBack(conversation, "Marta")).verifyError(ConversationConflictException.class);
        assertThat(store.findById(conversation).block().agentName()).isEqualTo("Luis");
    }

    @Test
    void handingBackAConversationTheBotAttendsDoesNothing() {
        StepVerifier.create(useCase.handBack(conversation, "Luis"))
                .assertNext(same -> assertThat(same.mode()).isEqualTo(ConversationMode.BOT))
                .verifyComplete();
        assertThat(store.messages(conversation)).hasSize(1);
    }

    @Test
    void rejectsBlankAgentAndUnknownConversation() {
        StepVerifier.create(useCase.takeOver(conversation, " ")).verifyError(ValidationException.class);
        StepVerifier.create(useCase.takeOver(UUID.randomUUID(), "Luis")).verifyError(ConversationNotFoundException.class);
    }
}
