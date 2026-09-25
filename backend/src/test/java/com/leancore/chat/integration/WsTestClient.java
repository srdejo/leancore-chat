package com.leancore.chat.integration;

import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/** Test WebSocket participant: records every text frame and the close status. */
final class WsTestClient implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
    private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
    private final Disposable connection;

    private WsTestClient(URI uri) {
        connection = new ReactorNettyWebSocketClient().execute(uri, session -> session
                        .send(outbound.asFlux().map(session::textMessage))
                        .and(session.receive()
                                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                                .doOnNext(message -> frames.add(JSON.readTree(message.getPayloadAsText())))
                                .then())
                        .and(session.closeStatus().doOnNext(closeStatus::set).then()))
                .subscribe(null, error -> { });
    }

    static WsTestClient connect(URI uri) {
        return new WsTestClient(uri);
    }

    UUID send(String content) {
        UUID id = UUID.randomUUID();
        send(id, content);
        return id;
    }

    void send(UUID clientMessageId, String content) {
        sendRaw("{\"type\":\"SEND\",\"clientMessageId\":\"%s\",\"content\":\"%s\"}".formatted(clientMessageId, content));
    }

    void sendRaw(String json) {
        outbound.emitNext(json, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(200)));
    }

    List<JsonNode> ofType(String type) {
        return frames.stream().filter(frame -> type.equals(frame.get("type").asString())).toList();
    }

    /** Seqs of the MESSAGE frames, in arrival order. */
    List<Long> messageSeqs() {
        return ofType("MESSAGE").stream().map(frame -> frame.get("message").get("seq").asLong()).toList();
    }

    void awaitSynced() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !ofType("SYNCED").isEmpty());
    }

    void awaitMessages(int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> ofType("MESSAGE").size() >= count);
    }

    @Override
    public void close() {
        connection.dispose();
    }
}
