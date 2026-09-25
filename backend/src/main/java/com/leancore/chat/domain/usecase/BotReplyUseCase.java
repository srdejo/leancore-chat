package com.leancore.chat.domain.usecase;

import com.leancore.chat.domain.api.IBotReplyServicePort;
import com.leancore.chat.domain.exception.ConversationModeException;
import com.leancore.chat.domain.exception.UnparseableBotAnswerException;
import com.leancore.chat.domain.model.BotAnswer;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.ConversationMode;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.model.SenderRole;
import com.leancore.chat.domain.spi.IBotAnswerPort;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.NameBasedUuid;
import com.leancore.chat.domain.util.Texts;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers customer messages. The reply goes through the same append (seq + idempotency) as any other
 * message; its clientMessageId is derived from the question, so a question is answered at most once
 * no matter how many times the work is triggered.
 */
public class BotReplyUseCase implements IBotReplyServicePort {

    private static final System.Logger LOG = System.getLogger(BotReplyUseCase.class.getName());

    private final IMessagePersistencePort messagePersistencePort;
    private final IConversationPersistencePort conversationPersistencePort;
    private final IBotScopePersistencePort botScopePersistencePort;
    private final IBotAnswerPort botAnswerPort;
    private final IMessageBroadcastPort messageBroadcastPort;
    private final BotSettings settings;
    private final Clock clock;

    /** Tail of the pending work per conversation: new work is chained after it, so replies are serial. */
    private final Map<UUID, Mono<Void>> tails = new ConcurrentHashMap<>();

    public record BotSettings(String botName, int historySize, Duration timeout, Duration recoveryWindow) {
    }

    public BotReplyUseCase(IMessagePersistencePort messagePersistencePort,
                           IConversationPersistencePort conversationPersistencePort,
                           IBotScopePersistencePort botScopePersistencePort,
                           IBotAnswerPort botAnswerPort,
                           IMessageBroadcastPort messageBroadcastPort,
                           BotSettings settings,
                           Clock clock) {
        this.messagePersistencePort = messagePersistencePort;
        this.conversationPersistencePort = conversationPersistencePort;
        this.botScopePersistencePort = botScopePersistencePort;
        this.botAnswerPort = botAnswerPort;
        this.messageBroadcastPort = messageBroadcastPort;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public void requestReplies(UUID conversationId) {
        Mono<Void> tail = tails.compute(conversationId, (id, previous) ->
                (previous == null ? Mono.<Void>empty() : previous)
                        .then(Mono.defer(() -> answerPending(id)))
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.ERROR, "Bot reply failed for conversation " + id, error);
                            return Mono.empty();
                        })
                        .cache());
        // Subscribed outside compute(): a synchronous completion removes the entry without re-entering the map.
        tail.doFinally(signal -> tails.remove(conversationId, tail)).subscribe();
    }

    @Override
    public Mono<Void> recoverPendingReplies() {
        return messagePersistencePort
                .findConversationsWithUnansweredMessages(clock.instant().minus(settings.recoveryWindow()))
                .doOnNext(this::requestReplies)
                .then();
    }

    /** Only in BOT mode, and only questions after the last hand-back (earlier ones were the agent's). */
    private Mono<Void> answerPending(UUID conversationId) {
        return attendedByBot(conversationId)
                .flatMapMany(conversation -> messagePersistencePort
                        .findUnansweredCustomerMessages(conversationId, conversation.botResumeAfterSeq()))
                .concatMap(this::answer)
                .then();
    }

    private Mono<ConversationModel> attendedByBot(UUID conversationId) {
        return conversationPersistencePort.findById(conversationId)
                .filter(conversation -> conversation.mode() == ConversationMode.BOT);
    }

    private Mono<Void> answer(MessageModel question) {
        UUID conversationId = question.conversationId();
        // Re-checked per question: an agent may have taken the conversation while earlier ones were answered.
        return attendedByBot(conversationId).flatMap(conversation -> Mono.defer(() -> {
                    messageBroadcastPort.publish(new ChatEvent.TypingEvent(conversationId, true));
                    return botScopePersistencePort.findActive();
                })
                .flatMap(scope -> askBot(scope, question))
                .onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Bot unavailable: " + error.getMessage());
                    return Mono.just(unavailableReply(question));
                })
                .flatMap(messagePersistencePort::append)
                .doOnNext(result -> {
                    if (!result.duplicate()) {
                        messageBroadcastPort.publish(new ChatEvent.MessageEvent(result.message()));
                    }
                })
                // An agent took the conversation while the reply was being generated: the append guard
                // rejected it under the conversation lock, so it is dropped without consuming a seq.
                .onErrorResume(ConversationModeException.class, error -> Mono.empty())
                .doFinally(signal -> messageBroadcastPort.publish(new ChatEvent.TypingEvent(conversationId, false))))
                .then();
    }

    private Mono<NewMessage> askBot(BotScope scope, MessageModel question) {
        return messagePersistencePort
                .findRecentBefore(question.conversationId(), question.seq(), settings.historySize())
                .filter(message -> message.senderRole() != SenderRole.SYSTEM)
                .collectList()
                .flatMap(history -> botAnswerPort.answer(scope, history, question).timeout(settings.timeout()))
                .map(answer -> botReply(question, scope, contentFor(scope, answer), answer.provider(), answer.model()))
                // When in doubt, refuse: the refusal is decided here, never taken from the LLM text.
                .onErrorResume(UnparseableBotAnswerException.class, error -> Mono.just(
                        botReply(question, scope, scope.refusalMessage(), error.provider(), error.model())));
    }

    private String contentFor(BotScope scope, BotAnswer answer) {
        if (!answer.inScope() || answer.reply() == null || answer.reply().isBlank()) {
            return scope.refusalMessage();
        }
        return Texts.truncate(answer.reply().strip(), DomainConstants.CONTENT_MAX_LENGTH);
    }

    /** provider/model record which LLM produced (or classified) the reply, so the admin can see it. */
    private NewMessage botReply(MessageModel question, BotScope scope, String content, String provider, String model) {
        return new NewMessage(question.conversationId(), replyId(question), SenderRole.BOT, settings.botName(),
                content, question.seq(), scope.version(), provider, model);
    }

    private NewMessage unavailableReply(MessageModel question) {
        return NewMessage.fromSystem(question.conversationId(), replyId(question), DomainConstants.SYSTEM_SENDER_NAME,
                DomainConstants.BOT_UNAVAILABLE_MESSAGE, question.seq());
    }

    static UUID replyId(MessageModel question) {
        return NameBasedUuid.v5("bot-reply:" + question.conversationId() + ":" + question.seq());
    }
}
