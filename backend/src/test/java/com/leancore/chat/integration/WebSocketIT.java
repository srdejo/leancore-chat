package com.leancore.chat.integration;

import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WebSocketIT extends IntegrationTestBase {

    @Autowired
    private IConversationServicePort conversations;

    @Autowired
    private IMessagePersistencePort messages;

    @Test
    void customerGetsAckAndBothParticipantsGetTheMessage() {
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0));
             var observer = WsTestClient.connect(wsUri(conversation, "OBSERVER", "Admin", 0))) {
            customer.awaitSynced();
            observer.awaitSynced();

            UUID id = customer.send("Hola");

            await().until(() -> !customer.ofType("ACK").isEmpty());
            JsonNode ack = customer.ofType("ACK").getFirst();
            assertThat(ack.get("clientMessageId").asString()).isEqualTo(id.toString());
            assertThat(ack.get("seq").asLong()).isEqualTo(1);
            observer.awaitMessages(1);
            JsonNode message = observer.ofType("MESSAGE").getFirst().get("message");
            assertThat(message.get("content").asString()).isEqualTo("Hola");
            assertThat(message.get("senderRole").asString()).isEqualTo("CUSTOMER");
        }
    }

    @Test
    void retryOfTheSameMessageIsAcknowledgedWithTheOriginalSeqAndNotRebroadcast() {
        UUID conversation = newConversation();
        fakeBot.delay(Duration.ofSeconds(30)); // keep the bot quiet during this test
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            UUID id = UUID.randomUUID();
            customer.send(id, "Hola");
            customer.send(id, "Hola");

            await().until(() -> customer.ofType("ACK").size() == 2);
            assertThat(customer.ofType("ACK")).extracting(ack -> ack.get("seq").asLong()).containsExactly(1L, 1L);
            await().during(Duration.ofMillis(300)).until(() -> customer.messageSeqs().equals(List.of(1L)));
        }
    }

    @Test
    void observerCannotSend() {
        UUID conversation = newConversation();
        try (var observer = WsTestClient.connect(wsUri(conversation, "OBSERVER", "Admin", 0))) {
            observer.awaitSynced();
            observer.send("intento");

            await().until(() -> !observer.ofType("ERROR").isEmpty());
            assertThat(observer.ofType("ERROR").getFirst().get("code").asString()).isEqualTo("READ_ONLY");
            assertThat(conversations.getHistory(conversation, 0L, 10).collectList().block()).isEmpty();
        }
    }

    @Test
    void invalidContentIsRejectedWithValidationError() {
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("   ");
            customer.sendRaw("esto no es json");

            await().until(() -> customer.ofType("ERROR").size() == 2);
            assertThat(customer.ofType("ERROR")).extracting(error -> error.get("code").asString())
                    .containsExactly("VALIDATION", "VALIDATION");
        }
    }

    @Test
    void unknownConversationOrInvalidParametersCloseTheSocket() {
        try (var unknown = WsTestClient.connect(wsUri(UUID.randomUUID(), "CUSTOMER", "Ana", 0));
             var badRole = WsTestClient.connect(wsUri(newConversation(), "ADMIN", "Ana", 0))) {
            await().until(() -> unknown.closeStatus.get() != null && badRole.closeStatus.get() != null);
            assertThat(unknown.closeStatus.get().getCode()).isEqualTo(4404);
            assertThat(badRole.closeStatus.get().getCode()).isEqualTo(4400);
        }
    }

    @Test
    void reconnectingWithLastSeqReceivesTheMissedMessagesThenLiveOnes() {
        UUID conversation = newConversation();
        for (int i = 1; i <= 13; i++) {
            append(conversation, "m" + i);
        }

        try (var agent = WsTestClient.connect(wsUri(conversation, "OBSERVER", "Admin", 10))) {
            agent.awaitSynced();
            assertThat(agent.messageSeqs()).containsExactly(11L, 12L, 13L);
            assertThat(agent.ofType("SYNCED").getFirst().get("lastSeq").asLong()).isEqualTo(13);

            append(conversation, "m14");
            agent.awaitMessages(4);
            assertThat(agent.messageSeqs()).containsExactly(11L, 12L, 13L, 14L);
        }
    }

    @Test
    void messagesAcceptedDuringTheReplayArriveExactlyOnceAndInOrder() {
        UUID conversation = newConversation();
        // SYSTEM messages: the bot never answers them, so the expected seqs are exactly 1..1230.
        Flux.range(1, 1200)
                .concatMap(i -> messages.append(systemMessage(conversation, "m" + i)))
                .blockLast();

        try (var observer = WsTestClient.connect(wsUri(conversation, "OBSERVER", "Admin", 0))) {
            // While the 1200-message history is being replayed in pages, 30 more are committed.
            Flux.range(1, 30)
                    .flatMap(i -> messages.append(systemMessage(conversation, "live" + i))
                            .doOnNext(result -> publish(result.message()))
                            .subscribeOn(Schedulers.parallel()), 4)
                    .blockLast();

            await().atMost(Duration.ofSeconds(20)).until(() -> observer.messageSeqs().size() >= 1230);
            List<Long> received = observer.messageSeqs();
            assertThat(received.stream().distinct().count()).isEqualTo(received.size());
            assertThat(received.stream().sorted().toList())
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, 1230).boxed().toList());
        }
    }

    @Test
    void typingSignalsAreNotReplayedOnReconnect() {
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("Hola");
            customer.awaitMessages(2); // question + bot reply
            assertThat(customer.ofType("TYPING")).isNotEmpty();
        }

        try (var again = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            again.awaitSynced();
            again.awaitMessages(2);
            await().during(Duration.ofMillis(300)).until(() -> again.ofType("TYPING").isEmpty());
            assertThat(again.ofType("MESSAGE").get(1).get("message").get("senderRole").asString()).isEqualTo("BOT");
        }
    }

    @Autowired
    private IMessageBroadcastPort broadcast;

    private void publish(MessageModel message) {
        broadcast.publish(new ChatEvent.MessageEvent(message));
    }

    private UUID newConversation() {
        return conversations.createConversation("Ana").block().id();
    }

    private static NewMessage systemMessage(UUID conversation, String content) {
        return NewMessage.fromSystem(conversation, UUID.randomUUID(), "Sistema", content, null);
    }

    private void append(UUID conversation, String content) {
        publish(messages.append(systemMessage(conversation, content)).block().message());
    }
}
