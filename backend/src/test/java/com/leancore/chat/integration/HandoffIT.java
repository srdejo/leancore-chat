package com.leancore.chat.integration;

import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.SenderRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HandoffIT extends IntegrationTestBase {

    @Autowired
    private IConversationServicePort conversations;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void takeOverNotifiesTheCustomerAndOnlyTheAssignedAgentCanWrite() {
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0));
             var luis = WsTestClient.connect(wsUri(conversation, "AGENT", "Luis", 0));
             var marta = WsTestClient.connect(wsUri(conversation, "AGENT", "Marta", 0))) {
            customer.awaitSynced();
            luis.awaitSynced();
            marta.awaitSynced();
            assertThat(customer.ofType("SYNCED").getFirst().get("mode").asString()).isEqualTo("BOT");

            takeOver(conversation, "Luis").expectStatus().isOk()
                    .expectBody().jsonPath("$.mode").isEqualTo("HUMAN").jsonPath("$.agentName").isEqualTo("Luis");

            await().until(() -> !customer.ofType("MODE").isEmpty());
            JsonNode mode = customer.ofType("MODE").getFirst();
            assertThat(mode.get("mode").asString()).isEqualTo("HUMAN");
            assertThat(mode.get("agentName").asString()).isEqualTo("Luis");
            customer.awaitMessages(1);
            assertThat(customer.ofType("MESSAGE").getFirst().get("message").get("content").asString())
                    .isEqualTo("Luis se unió a la conversación. Ahora estás hablando con una persona.");

            luis.send("Hola Ana, ¿en qué te ayudo?");
            marta.send("Yo también quiero escribir");

            await().until(() -> !luis.ofType("ACK").isEmpty() && !marta.ofType("ERROR").isEmpty());
            assertThat(marta.ofType("ERROR").getFirst().get("code").asString()).isEqualTo("NOT_ASSIGNED");
            customer.awaitMessages(2);
            JsonNode agentMessage = customer.ofType("MESSAGE").get(1).get("message");
            assertThat(agentMessage.get("senderRole").asString()).isEqualTo("AGENT");
            assertThat(agentMessage.get("senderName").asString()).isEqualTo("Luis");
        }
    }

    @Test
    void theBotDoesNotAnswerWhileTheAgentAttends() {
        UUID conversation = newConversation();
        takeOver(conversation, "Luis").expectStatus().isOk();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("¿Hay alguien?");
            await().until(() -> !customer.ofType("ACK").isEmpty());
            await().during(Duration.ofMillis(500)).until(() -> customer.ofType("TYPING").isEmpty());
        }
        assertThat(history(conversation)).extracting(MessageModel::senderRole)
                .containsExactly(SenderRole.SYSTEM, SenderRole.CUSTOMER);
    }

    @Test
    void aSlowBotReplyInterruptedByTheTakeOverIsDroppedWithoutSeqGaps() {
        fakeBot.delay(Duration.ofMillis(800));
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("pregunta lenta");
            await().until(() -> !customer.ofType("TYPING").isEmpty()); // the bot is generating
            takeOver(conversation, "Luis").expectStatus().isOk();

            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                    .until(() -> history(conversation).size() == 2);
        }
        List<MessageModel> stored = history(conversation);
        assertThat(stored).extracting(MessageModel::senderRole).containsExactly(SenderRole.CUSTOMER, SenderRole.SYSTEM);
        assertThat(stored).extracting(MessageModel::seq).containsExactly(1L, 2L);
    }

    @Test
    void onlyOneOfTwoSimultaneousTakeOversWins() {
        UUID conversation = newConversation();

        List<Integer> statuses = Flux.just("Luis", "Marta")
                .flatMap(agent -> Flux.defer(() -> Flux.just(takeOver(conversation, agent).returnResult(Map.class)
                        .getStatus().value())).subscribeOn(Schedulers.boundedElastic()))
                .collectList().block();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(history(conversation)).hasSize(1); // a single notice
    }

    @Test
    void handBackLetsTheBotAnswerOnlyNewQuestionsAndReconnectingViewsSeeTheMode() {
        UUID conversation = newConversation();
        takeOver(conversation, "Luis").expectStatus().isOk();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            assertThat(customer.ofType("SYNCED").getFirst().get("agentName").asString()).isEqualTo("Luis");
            customer.send("escrita mientras atendía Luis");
            await().until(() -> !customer.ofType("ACK").isEmpty());

            client.post().uri("/api/v1/conversations/{id}/release", conversation).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("agentName", "Marta")).exchange().expectStatus().isEqualTo(409);
            client.post().uri("/api/v1/conversations/{id}/release", conversation).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("agentName", "Luis")).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.mode").isEqualTo("BOT");

            customer.send("nueva pregunta");
            await().until(() -> history(conversation).stream().anyMatch(m -> m.senderRole() == SenderRole.BOT));
        }
        List<MessageModel> stored = history(conversation);
        // 1 take-over notice, 2 question to Luis, 3 hand-back notice, 4 new question, 5 bot reply to 4.
        assertThat(stored).extracting(MessageModel::senderRole).containsExactly(
                SenderRole.SYSTEM, SenderRole.CUSTOMER, SenderRole.SYSTEM, SenderRole.CUSTOMER, SenderRole.BOT);
        assertThat(stored.get(4).replyToSeq()).isEqualTo(4L);
        assertThat(stored).extracting(MessageModel::seq).containsExactlyElementsOf(LongStream.rangeClosed(1, 5).boxed().toList());
    }

    private WebTestClient.ResponseSpec takeOver(UUID conversation, String agent) {
        return client.post().uri("/api/v1/conversations/{id}/takeover", conversation)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("agentName", agent))
                .exchange();
    }

    private List<MessageModel> history(UUID conversation) {
        return conversations.getHistory(conversation, 0L, 500).collectList().block();
    }

    private UUID newConversation() {
        return conversations.createConversation("Ana").block().id();
    }
}
