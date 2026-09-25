package com.leancore.chat.infrastructure.input.websocket.dto;

import com.leancore.chat.application.dto.response.MessageResponseDto;

import java.time.Instant;
import java.util.UUID;

/** Frames sent to the browser; every one carries its "type". */
public sealed interface ServerFrame {

    String type();

    /** A persisted message (history replay or live). */
    record MessageFrame(String type, MessageResponseDto message) implements ServerFrame {
        public MessageFrame(MessageResponseDto message) {
            this("MESSAGE", message);
        }
    }

    /** The sender's message is stored with this seq. */
    record AckFrame(String type, UUID clientMessageId, long seq, Instant createdAt) implements ServerFrame {
        public AckFrame(UUID clientMessageId, long seq, Instant createdAt) {
            this("ACK", clientMessageId, seq, createdAt);
        }
    }

    /** End of the history replay: everything up to lastSeq was sent, live messages follow; mode = who attends. */
    record SyncedFrame(String type, long lastSeq, String mode, String agentName) implements ServerFrame {
        public SyncedFrame(long lastSeq, String mode, String agentName) {
            this("SYNCED", lastSeq, mode, agentName);
        }
    }

    /** Ephemeral: who attends the conversation changed (BOT, or HUMAN with the agent's name). */
    record ModeFrame(String type, String mode, String agentName) implements ServerFrame {
        public ModeFrame(String mode, String agentName) {
            this("MODE", mode, agentName);
        }
    }

    /** Ephemeral "the bot is typing": no seq, never stored nor replayed. */
    record TypingFrame(String type, boolean active) implements ServerFrame {
        public TypingFrame(boolean active) {
            this("TYPING", active);
        }
    }

    record ErrorFrame(String type, UUID clientMessageId, String code, String message) implements ServerFrame {
        public ErrorFrame(UUID clientMessageId, String code, String message) {
            this("ERROR", clientMessageId, code, message);
        }
    }
}
