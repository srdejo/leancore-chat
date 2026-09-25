package com.leancore.chat.infrastructure.input.websocket;

import com.leancore.chat.application.dto.response.MessageResponseDto;
import com.leancore.chat.infrastructure.input.websocket.dto.ClientFrame;
import com.leancore.chat.infrastructure.input.websocket.dto.ServerFrame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FrameSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void clientSendFrameRoundTrips() {
        ClientFrame frame = new ClientFrame(ClientFrame.SEND, UUID.randomUUID(), "Hola");

        assertThat(mapper.readValue(mapper.writeValueAsString(frame), ClientFrame.class)).isEqualTo(frame);
    }

    @Test
    void serverFramesCarryTheirTypeAndRoundTrip() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        var message = new ServerFrame.MessageFrame(
                new MessageResponseDto(4, id, "BOT", "Asistente", "ok", 3L, 1, "openai", "gpt-5-mini", now));
        var ack = new ServerFrame.AckFrame(id, 4, now);
        var synced = new ServerFrame.SyncedFrame(4, "HUMAN", "Luis");
        var mode = new ServerFrame.ModeFrame("BOT", null);
        var typing = new ServerFrame.TypingFrame(true);
        var error = new ServerFrame.ErrorFrame(id, "READ_ONLY", "solo lectura");

        assertThat(roundTrip(message, ServerFrame.MessageFrame.class)).isEqualTo(message);
        assertThat(roundTrip(ack, ServerFrame.AckFrame.class)).isEqualTo(ack);
        assertThat(roundTrip(synced, ServerFrame.SyncedFrame.class)).isEqualTo(synced);
        assertThat(roundTrip(mode, ServerFrame.ModeFrame.class)).isEqualTo(mode);
        assertThat(roundTrip(typing, ServerFrame.TypingFrame.class)).isEqualTo(typing);
        assertThat(roundTrip(error, ServerFrame.ErrorFrame.class)).isEqualTo(error);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(ack));
        assertThat(json.get("type").asString()).isEqualTo("ACK");
        assertThat(json.get("createdAt").asString()).isEqualTo("2026-09-24T10:00:00Z");
    }

    private <T> T roundTrip(T frame, Class<T> type) {
        return mapper.readValue(mapper.writeValueAsString(frame), type);
    }
}
