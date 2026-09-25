package com.leancore.chat.integration;

import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SendResult;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceIT extends IntegrationTestBase {

    @Autowired
    private IConversationServicePort conversations;

    @Autowired
    private IMessagePersistencePort messages;

    @Test
    void createsListsByActivityAndReadsHistoryAfterSeq() throws InterruptedException {
        ConversationModel older = conversations.createConversation("Ana").block();
        Thread.sleep(20);
        ConversationModel newer = conversations.createConversation("Luis").block();
        for (int i = 1; i <= 7; i++) {
            append(older.id(), "m" + i).block();
        }

        List<UUID> order = conversations.listConversations().map(ConversationModel::id).collectList().block();
        assertThat(order.indexOf(older.id())).isLessThan(order.indexOf(newer.id()));

        List<MessageModel> after3 = conversations.getHistory(older.id(), 3L, null).collectList().block();
        assertThat(after3).extracting(MessageModel::seq).containsExactly(4L, 5L, 6L, 7L);

        ConversationModel reloaded = conversations.getConversation(older.id()).block();
        assertThat(reloaded.lastSeq()).isEqualTo(7);
        assertThat(reloaded.lastPreview()).isEqualTo("m7");
        assertThat(reloaded.lastSenderRole()).isEqualTo(SenderRole.CUSTOMER);
    }

    @Test
    void fiftyConcurrentMessagesGetDenseSeqsOneToFifty() {
        UUID conversation = conversations.createConversation("Ana").block().id();

        List<SendResult> results = Flux.range(1, 50)
                .flatMap(i -> append(conversation, "m" + i).subscribeOn(Schedulers.parallel()), 50)
                .collectList()
                .block();

        assertThat(results).allMatch(result -> !result.duplicate());
        List<Long> stored = conversations.getHistory(conversation, 0L, 500).map(MessageModel::seq).collectList().block();
        assertThat(stored).containsExactlyElementsOf(LongStream.rangeClosed(1, 50).boxed().toList());
    }

    @Test
    void sameClientMessageIdSentFiveTimesInParallelIsStoredOnce() {
        UUID conversation = conversations.createConversation("Ana").block().id();
        UUID clientMessageId = UUID.randomUUID();

        List<SendResult> results = Flux.range(1, 5)
                .flatMap(i -> messages.append(NewMessage.fromCustomer(conversation, clientMessageId, "Ana", "Hola"))
                        .subscribeOn(Schedulers.parallel()), 5)
                .collectList()
                .block();

        assertThat(results).extracting(result -> result.message().seq()).containsOnly(1L);
        assertThat(results).filteredOn(SendResult::duplicate).hasSize(4);
        assertThat(conversations.getHistory(conversation, 0L, 500).collectList().block()).hasSize(1);
        // The losers' seq increments were rolled back: the next message is 2, not 6.
        assertThat(append(conversation, "siguiente").block().message().seq()).isEqualTo(2L);
    }

    private reactor.core.publisher.Mono<SendResult> append(UUID conversation, String content) {
        return messages.append(NewMessage.fromCustomer(conversation, UUID.randomUUID(), "Ana", content));
    }
}
