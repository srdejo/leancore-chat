package com.leancore.chat.integration;

import com.leancore.chat.domain.api.IBotScopeServicePort;
import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.infrastructure.configuration.BotRecoveryRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BotIT extends IntegrationTestBase {

    @Autowired
    private IConversationServicePort conversations;

    @Autowired
    private IMessagePersistencePort messages;

    @Autowired
    private IBotScopeServicePort scopes;

    @Autowired
    private BotRecoveryRunner recoveryRunner;

    @Test
    void customerWritingWhileTheBotAnswersSeesTheSameOrderAsTheObserver() {
        fakeBot.delay(Duration.ofMillis(500));
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0));
             var observer = WsTestClient.connect(wsUri(conversation, "OBSERVER", "Admin", 0))) {
            customer.awaitSynced();
            observer.awaitSynced();

            customer.send("primera");
            await().until(() -> customer.ofType("ACK").size() == 1);
            customer.send("segunda"); // committed while the bot is still generating the first reply

            customer.awaitMessages(4);
            observer.awaitMessages(4);
            assertThat(customer.messageSeqs()).containsExactly(1L, 2L, 3L, 4L);
            assertThat(observer.messageSeqs()).containsExactly(1L, 2L, 3L, 4L);
            assertThat(roles(customer)).containsExactly("CUSTOMER", "CUSTOMER", "BOT", "BOT");
            assertThat(roles(observer)).isEqualTo(roles(customer));
            // The observer (admin) receives which provider and model answered.
            var botFrame = observer.ofType("MESSAGE").get(2).get("message");
            assertThat(botFrame.get("botProvider").asString()).isEqualTo("fake");
            assertThat(botFrame.get("botModel").asString()).isEqualTo("fake-1");
        }

        List<MessageModel> stored = history(conversation);
        assertThat(stored).filteredOn(m -> m.senderRole() == SenderRole.BOT)
                .extracting(MessageModel::replyToSeq).containsExactly(1L, 2L);
        assertThat(stored.get(2).content()).isEqualTo("re: primera");
        assertThat(stored.get(2).botProvider()).isEqualTo("fake");
        assertThat(stored.get(2).botModel()).isEqualTo("fake-1");
        assertThat(stored.get(3).content()).isEqualTo("re: segunda");
    }

    @Test
    void outOfScopeQuestionGetsTheConfiguredRefusal() {
        fakeBot.inScope(false);
        UUID conversation = newConversation();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("¿Qué es un avión?");
            customer.awaitMessages(2);
        }

        String refusal = scopes.getActiveScope().block().refusalMessage();
        assertThat(history(conversation).get(1).content()).isEqualTo(refusal);
    }

    @Test
    void aScopeChangeAppliesFromTheNextReplyAndIsRecordedPerReply() {
        UUID conversation = newConversation();
        int before = scopes.getActiveScope().block().version();
        try (var customer = WsTestClient.connect(wsUri(conversation, "CUSTOMER", "Ana", 0))) {
            customer.awaitSynced();
            customer.send("antes");
            customer.awaitMessages(2);

            BotScope saved = scopes.saveScope(new BotScope(null, "cafeteras", "", List.of("espresso"), "Solo cafeteras.", null)).block();

            customer.send("después");
            customer.awaitMessages(4);
            List<MessageModel> stored = history(conversation);
            assertThat(stored.get(1).botScopeVersion()).isEqualTo(before);
            assertThat(stored.get(3).botScopeVersion()).isEqualTo(saved.version());
        }
    }

    @Test
    void unansweredCustomerMessageGetsExactlyOneReplyOnRecovery() {
        UUID conversation = newConversation();
        // Stored as if the server had stopped right after accepting it: no bot work was scheduled.
        messages.append(NewMessage.fromCustomer(conversation, UUID.randomUUID(), "Ana", "¿Sigues ahí?")).block();

        recoveryRunner.recoverPendingReplies();
        recoveryRunner.recoverPendingReplies();

        await().atMost(Duration.ofSeconds(5)).until(() -> history(conversation).size() == 2);
        await().during(Duration.ofMillis(500)).until(() -> history(conversation).size() == 2);
        assertThat(history(conversation).get(1).replyToSeq()).isEqualTo(1L);
    }

    private List<String> roles(WsTestClient client) {
        return client.ofType("MESSAGE").stream().map(f -> f.get("message").get("senderRole").asString()).toList();
    }

    private List<MessageModel> history(UUID conversation) {
        return conversations.getHistory(conversation, 0L, 500).collectList().block();
    }

    private UUID newConversation() {
        return conversations.createConversation("Ana").block().id();
    }
}
