package com.leancore.chat.domain;

import com.leancore.chat.domain.exception.BotUnavailableException;
import com.leancore.chat.domain.exception.UnparseableBotAnswerException;
import com.leancore.chat.domain.model.BotAnswer;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IBotAnswerPort;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.support.InMemoryMessageStore;
import com.leancore.chat.domain.usecase.BotReplyUseCase;
import com.leancore.chat.domain.util.DomainConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BotReplyUseCaseTest {

    private static final BotScope BIKES = new BotScope(3, "bicicletas", "", List.of("mecánica"),
            "Solo bicicletas.", null);

    private InMemoryMessageStore store;
    private RecordingBroadcast broadcast;
    private AtomicReference<IBotAnswerPort> bot;
    private BotReplyUseCase useCase;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessageStore();
        broadcast = new RecordingBroadcast();
        bot = new AtomicReference<>((scope, history, question) -> Mono.just(new BotAnswer(true, "ok")));
        useCase = newUseCase(Duration.ofSeconds(2));
    }

    @Test
    void inScopeAnswerIsPublishedAsBotReplyWithItsScopeVersion() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "  Lubrica cada 200 km.  ")));
        UUID conversation = customerSays("¿Cada cuánto lubrico la cadena?");

        MessageModel reply = awaitReplies(conversation, 1).getFirst();

        assertThat(reply.senderRole()).isEqualTo(SenderRole.BOT);
        assertThat(reply.content()).isEqualTo("Lubrica cada 200 km.");
        assertThat(reply.replyToSeq()).isEqualTo(1L);
        assertThat(reply.botScopeVersion()).isEqualTo(3);
        assertThat(reply.seq()).isEqualTo(2L);
    }

    @Test
    void replyRecordsWhichProviderAndModelProducedIt() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "ok", "openai", "gpt-5-mini")));
        UUID conversation = customerSays("Hola");

        MessageModel reply = awaitReplies(conversation, 1).getFirst();
        assertThat(reply.botProvider()).isEqualTo("openai");
        assertThat(reply.botModel()).isEqualTo("gpt-5-mini");
    }

    @Test
    void refusalsKeepTheProviderThatClassifiedOrFailedToParse() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(false, "", "anthropic", "claude-sonnet-5")));
        UUID refused = customerSays("¿Qué es un avión?");
        bot.set((scope, history, question) ->
                Mono.error(new UnparseableBotAnswerException("no json", "openai", "gpt-5-mini")));
        UUID unparseable = customerSays("Hola");

        MessageModel refusal = awaitReplies(refused, 1).getFirst();
        assertThat(refusal.content()).isEqualTo("Solo bicicletas.");
        assertThat(refusal.botProvider()).isEqualTo("anthropic");
        assertThat(awaitReplies(unparseable, 1).getFirst().botProvider()).isEqualTo("openai");
    }

    @Test
    void systemMessagesHaveNoProvider() {
        bot.set((scope, history, question) -> Mono.error(new BotUnavailableException("no key")));
        UUID conversation = customerSays("Hola");

        MessageModel reply = awaitReplies(conversation, 1).getFirst();
        assertThat(reply.botProvider()).isNull();
        assertThat(reply.botModel()).isNull();
    }

    @Test
    void outOfScopeAnswerUsesTheConfiguredRefusalNotTheLlmText() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(false, "Un avión es una aeronave...")));
        UUID conversation = customerSays("¿Qué es un avión?");

        assertThat(awaitReplies(conversation, 1).getFirst().content()).isEqualTo("Solo bicicletas.");
    }

    @Test
    void unparseableAnswerIsRefused() {
        bot.set((scope, history, question) -> Mono.error(new UnparseableBotAnswerException("no json")));
        UUID conversation = customerSays("Hola");

        MessageModel reply = awaitReplies(conversation, 1).getFirst();
        assertThat(reply.senderRole()).isEqualTo(SenderRole.BOT);
        assertThat(reply.content()).isEqualTo("Solo bicicletas.");
    }

    @Test
    void blankInScopeReplyIsRefused() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "   ")));
        UUID conversation = customerSays("Hola");

        assertThat(awaitReplies(conversation, 1).getFirst().content()).isEqualTo("Solo bicicletas.");
    }

    @Test
    void longReplyIsTruncatedTo2000() {
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "a".repeat(2500))));
        UUID conversation = customerSays("Cuéntame todo");

        assertThat(awaitReplies(conversation, 1).getFirst().content()).hasSize(2000);
    }

    @Test
    void unavailableBotPublishesOneSystemMessage() {
        bot.set((scope, history, question) -> Mono.error(new BotUnavailableException("no key")));
        UUID conversation = customerSays("Hola");

        MessageModel reply = awaitReplies(conversation, 1).getFirst();
        assertThat(reply.senderRole()).isEqualTo(SenderRole.SYSTEM);
        assertThat(reply.content()).isEqualTo(DomainConstants.BOT_UNAVAILABLE_MESSAGE);
        assertThat(reply.replyToSeq()).isEqualTo(1L);
        assertThat(reply.botScopeVersion()).isNull();
    }

    @Test
    void slowLlmTimesOutAsUnavailable() {
        useCase = newUseCase(Duration.ofMillis(150));
        bot.set((scope, history, question) -> Mono.never());
        UUID conversation = customerSays("Hola");

        assertThat(awaitReplies(conversation, 1).getFirst().senderRole()).isEqualTo(SenderRole.SYSTEM);
    }

    @Test
    void typingIsSwitchedOnThenOffAroundTheReply() {
        UUID conversation = customerSays("Hola");
        awaitReplies(conversation, 1);

        await().untilAsserted(() -> assertThat(broadcast.events).hasSize(3));
        assertThat(broadcast.events.get(0)).isEqualTo(new ChatEvent.TypingEvent(conversation, true));
        assertThat(broadcast.events.get(1)).isInstanceOf(ChatEvent.MessageEvent.class);
        assertThat(broadcast.events.get(2)).isEqualTo(new ChatEvent.TypingEvent(conversation, false));
    }

    @Test
    void requestingTwiceAnswersOnlyOnce() {
        UUID conversation = customerSays("Hola");
        useCase.requestReplies(conversation);

        awaitReplies(conversation, 1);
        pause(300);
        assertThat(replies(conversation)).hasSize(1);
    }

    @Test
    void concurrentGenerationForTheSameQuestionPersistsOneReply() {
        // Two replicas answering the same question at once: the deterministic reply id makes one a duplicate.
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "ok")).delayElement(Duration.ofMillis(200)));
        BotReplyUseCase otherReplica = newUseCase(Duration.ofSeconds(2));
        UUID conversation = customerSays("Hola");
        otherReplica.requestReplies(conversation);

        awaitReplies(conversation, 1);
        pause(500);
        assertThat(replies(conversation)).hasSize(1);
        assertThat(broadcast.events.stream().filter(ChatEvent.MessageEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void consecutiveQuestionsAreAnsweredInSeqOrderOneAtATime() {
        List<Long> asked = new CopyOnWriteArrayList<>();
        bot.set((scope, history, question) -> {
            asked.add(question.seq());
            return Mono.just(new BotAnswer(true, "r" + question.seq())).delayElement(Duration.ofMillis(100));
        });
        UUID conversation = store.newConversation();
        for (int i = 1; i <= 3; i++) {
            append(conversation, "pregunta " + i);
        }
        useCase.requestReplies(conversation);
        useCase.requestReplies(conversation);

        List<MessageModel> replies = awaitReplies(conversation, 3);
        assertThat(replies).extracting(MessageModel::replyToSeq).containsExactly(1L, 2L, 3L);
        assertThat(replies).extracting(MessageModel::content).containsExactly("r1", "r2", "r3");
        assertThat(asked).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aSlowConversationDoesNotDelayAnother() {
        UUID slow = store.newConversation();
        UUID fast = store.newConversation();
        bot.set((scope, history, question) -> Mono.just(new BotAnswer(true, "ok"))
                .delayElement(question.conversationId().equals(slow) ? Duration.ofSeconds(1) : Duration.ZERO));
        append(slow, "lenta");
        append(fast, "rápida");

        useCase.requestReplies(slow);
        useCase.requestReplies(fast);

        await().atMost(Duration.ofMillis(700)).until(() -> replies(fast).size() == 1);
        assertThat(replies(slow)).isEmpty();
        awaitReplies(slow, 1);
    }

    @Test
    void historySentToTheLlmExcludesSystemMessagesAndLaterMessages() {
        List<List<MessageModel>> histories = new CopyOnWriteArrayList<>();
        bot.set((scope, history, question) -> {
            histories.add(history);
            return Mono.just(new BotAnswer(true, "ok"));
        });
        UUID conversation = store.newConversation();
        append(conversation, "uno");
        store.append(NewMessage.fromSystem(conversation, UUID.randomUUID(), "Sistema", "aviso", null)).block();
        append(conversation, "dos");

        useCase.requestReplies(conversation);
        await().until(() -> histories.size() == 2);

        // seq 1 "uno", 2 SYSTEM "aviso", 3 "dos", 4 reply to 1: the question at seq 3 only sees "uno".
        assertThat(histories.getFirst()).isEmpty();
        assertThat(histories.get(1)).extracting(MessageModel::content).containsExactly("uno");
    }

    @Test
    void theBotStaysSilentWhileAHumanAttends() {
        UUID conversation = store.newConversation();
        store.takeOver(conversation, "Luis").block();
        append(conversation, "¿Hay alguien?");

        useCase.requestReplies(conversation);
        pause(300);

        assertThat(replies(conversation)).isEmpty();
        assertThat(broadcast.events).isEmpty(); // not even "typing"
    }

    @Test
    void aReplyGeneratedWhileTheConversationIsTakenIsDroppedWithoutASeq() {
        UUID conversation = store.newConversation();
        bot.set((scope, history, question) -> {
            store.takeOver(conversation, "Luis").block(); // the agent takes it during the generation
            return Mono.just(new BotAnswer(true, "tarde"));
        });
        append(conversation, "Hola");

        useCase.requestReplies(conversation);
        await().until(() -> broadcast.events.contains(new ChatEvent.TypingEvent(conversation, false)));

        assertThat(store.messages(conversation)).extracting(MessageModel::seq).containsExactly(1L);
    }

    @Test
    void afterTheHandBackTheBotOnlyAnswersNewQuestions() {
        UUID conversation = store.newConversation();
        store.takeOver(conversation, "Luis").block();
        append(conversation, "escrita mientras atendía Luis");
        store.release(conversation, "Luis").block();
        append(conversation, "nueva");

        useCase.requestReplies(conversation);

        MessageModel reply = awaitReplies(conversation, 1).getFirst();
        assertThat(reply.replyToSeq()).isEqualTo(2L);
        pause(200);
        assertThat(replies(conversation)).hasSize(1);
    }

    @Test
    void recoveryAnswersPendingQuestions() {
        UUID conversation = store.newConversation();
        append(conversation, "sin respuesta");

        StepVerifier.create(useCase.recoverPendingReplies()).verifyComplete();

        assertThat(awaitReplies(conversation, 1).getFirst().replyToSeq()).isEqualTo(1L);
    }

    private BotReplyUseCase newUseCase(Duration timeout) {
        IBotScopePersistencePort scopes = new IBotScopePersistencePort() {
            @Override
            public Mono<BotScope> findActive() {
                return Mono.just(BIKES);
            }

            @Override
            public Mono<BotScope> insertNextVersion(BotScope scope) {
                return Mono.error(new UnsupportedOperationException());
            }

            @Override
            public Flux<BotScope> findAll() {
                return Flux.just(BIKES);
            }
        };
        IBotAnswerPort delegating = (scope, history, question) -> bot.get().answer(scope, history, question);
        return new BotReplyUseCase(store, store, scopes, delegating, broadcast,
                new BotReplyUseCase.BotSettings("Asistente", 20, timeout, Duration.ofHours(24)), Clock.systemUTC());
    }

    private UUID customerSays(String content) {
        UUID conversation = store.newConversation();
        append(conversation, content);
        useCase.requestReplies(conversation);
        return conversation;
    }

    private void append(UUID conversation, String content) {
        store.append(NewMessage.fromCustomer(conversation, UUID.randomUUID(), "Ana", content)).block();
    }

    private List<MessageModel> replies(UUID conversation) {
        return store.messages(conversation).stream().filter(m -> m.senderRole() != SenderRole.CUSTOMER).toList();
    }

    private List<MessageModel> awaitReplies(UUID conversation, int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> replies(conversation).size() >= count);
        return replies(conversation);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingBroadcast implements IMessageBroadcastPort {
        private final List<ChatEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(ChatEvent event) {
            events.add(event);
        }

        @Override
        public Flux<ChatEvent> stream(UUID conversationId) {
            return Flux.empty();
        }
    }
}
