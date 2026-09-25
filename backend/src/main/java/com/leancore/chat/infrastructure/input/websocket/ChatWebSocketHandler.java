package com.leancore.chat.infrastructure.input.websocket;

import com.leancore.chat.application.mapper.IChatResponseMapper;
import com.leancore.chat.domain.api.IBotReplyServicePort;
import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.api.IMessageServicePort;
import com.leancore.chat.domain.exception.ConversationModeException;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.ChatEvent;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.infrastructure.input.websocket.dto.ClientFrame;
import com.leancore.chat.infrastructure.input.websocket.dto.ServerFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One socket per participant and conversation. Outbound order: history (seq > lastSeq), SYNCED, then live.
 * The live subscription is opened BEFORE reading the history and buffered, so a message committed while
 * the history is being read is not lost; anything the history already delivered is filtered by seq.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    static final CloseStatus INVALID_PARAMETERS = new CloseStatus(4400, "Parámetros inválidos");
    static final CloseStatus CONVERSATION_NOT_FOUND = new CloseStatus(4404, "La conversación no existe");

    private final IConversationServicePort conversationServicePort;
    private final IMessageServicePort messageServicePort;
    private final IBotReplyServicePort botReplyServicePort;
    private final IMessageBroadcastPort messageBroadcastPort;
    private final IChatResponseMapper chatResponseMapper;
    private final JsonMapper jsonMapper;

    @Value("${chat.websocket.ping-interval:25s}")
    private Duration pingInterval;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Participant participant;
        try {
            participant = Participant.fromHandshake(session.getHandshakeInfo().getUri());
        } catch (ValidationException e) {
            return session.close(INVALID_PARAMETERS);
        }
        return conversationServicePort.getConversation(participant.conversationId())
                .flatMap(conversation -> serve(session, participant))
                .onErrorResume(ConversationNotFoundException.class, e -> session.close(CONVERSATION_NOT_FOUND));
    }

    private Mono<Void> serve(WebSocketSession session, Participant participant) {
        UUID conversationId = participant.conversationId();
        // Someone is looking at the conversation: make sure no customer message is left unanswered.
        botReplyServicePort.requestReplies(conversationId);

        Sinks.Many<ChatEvent> liveBuffer = Sinks.many().unicast().onBackpressureBuffer();
        Disposable liveSubscription = messageBroadcastPort.stream(conversationId)
                .subscribe(liveBuffer::tryEmitNext);

        Sinks.Many<ServerFrame> replies = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong highWater = new AtomicLong(participant.lastSeq());

        Flux<ServerFrame> history = historyAfter(conversationId, participant.lastSeq())
                .doOnNext(message -> highWater.accumulateAndGet(message.seq(), Math::max))
                .map(message -> new ServerFrame.MessageFrame(chatResponseMapper.toResponse(message)));
        // Who attends is read after the history, so a view that reconnects shows the current mode.
        Flux<ServerFrame> synced = Mono.defer(() -> conversationServicePort.getConversation(conversationId))
                .<ServerFrame>map(conversation -> new ServerFrame.SyncedFrame(highWater.get(),
                        conversation.mode().name(), conversation.agentName()))
                .flux();
        Flux<ServerFrame> live = liveBuffer.asFlux()
                // Serialize and write on another thread, never on the publisher's (it holds the broadcast lock).
                .publishOn(Schedulers.parallel())
                .filter(event -> !(event instanceof ChatEvent.MessageEvent(MessageModel message))
                        || message.seq() > highWater.get())
                .map(this::toFrame);

        Flux<WebSocketMessage> outbound = Flux.merge(
                        Flux.concat(history, synced, live),
                        replies.asFlux())
                .map(frame -> session.textMessage(jsonMapper.writeValueAsString(frame)))
                .mergeWith(Flux.interval(pingInterval)
                        .map(tick -> session.pingMessage(factory -> factory.wrap(new byte[0]))));

        Mono<Void> inbound = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                // Sequential per socket: a participant's own messages keep the order in which they were sent.
                .concatMap(text -> handleFrame(participant, text))
                .doOnNext(frame -> replies.emitNext(frame, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(200))))
                .then();

        return Mono.firstWithSignal(inbound, session.send(outbound))
                .doFinally(signal -> liveSubscription.dispose());
    }

    /** Full history after lastSeq, read in pages of the maximum page size. */
    private Flux<MessageModel> historyAfter(UUID conversationId, long lastSeq) {
        int page = DomainConstants.HISTORY_MAX_LIMIT;
        return conversationServicePort.getHistory(conversationId, lastSeq, page)
                .collectList()
                .expand(messages -> messages.size() < page
                        ? Mono.empty()
                        : conversationServicePort.getHistory(conversationId, messages.getLast().seq(), page).collectList())
                .flatMapIterable(messages -> messages);
    }

    private Mono<ServerFrame> handleFrame(Participant participant, String text) {
        ClientFrame frame;
        try {
            frame = jsonMapper.readValue(text, ClientFrame.class);
        } catch (JacksonException e) {
            return Mono.just(new ServerFrame.ErrorFrame(null, "VALIDATION", "Frame inválido."));
        }
        if (!ClientFrame.SEND.equals(frame.type())) {
            return Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "VALIDATION", "Tipo de frame desconocido."));
        }
        if (!participant.canSend()) {
            return Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "READ_ONLY", "Esta conexión es de solo lectura."));
        }
        var send = participant.role() == Participant.Role.AGENT
                ? messageServicePort.sendAgentMessage(participant.conversationId(), frame.clientMessageId(),
                        participant.name(), frame.content())
                : messageServicePort.sendCustomerMessage(participant.conversationId(), frame.clientMessageId(),
                        participant.name(), frame.content());
        return send
                .<ServerFrame>map(result -> new ServerFrame.AckFrame(
                        result.message().clientMessageId(), result.message().seq(), result.message().createdAt()))
                .onErrorResume(ValidationException.class,
                        e -> Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "VALIDATION", e.getMessage())))
                .onErrorResume(ConversationModeException.class,
                        e -> Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "NOT_ASSIGNED",
                                "La conversación no está asignada a este agente.")))
                .onErrorResume(ConversationNotFoundException.class,
                        e -> Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "NOT_FOUND", e.getMessage())))
                .onErrorResume(e -> {
                    log.error("Failed to send message {}", frame.clientMessageId(), e);
                    return Mono.just(new ServerFrame.ErrorFrame(frame.clientMessageId(), "INTERNAL", "No se pudo enviar el mensaje."));
                });
    }

    private ServerFrame toFrame(ChatEvent event) {
        return switch (event) {
            case ChatEvent.MessageEvent(MessageModel message) -> new ServerFrame.MessageFrame(chatResponseMapper.toResponse(message));
            case ChatEvent.TypingEvent(UUID ignored, boolean active) -> new ServerFrame.TypingFrame(active);
            case ChatEvent.ModeEvent(UUID ignored, var mode, String agentName) -> new ServerFrame.ModeFrame(mode.name(), agentName);
        };
    }
}
