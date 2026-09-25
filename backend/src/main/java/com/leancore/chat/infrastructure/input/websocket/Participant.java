package com.leancore.chat.infrastructure.input.websocket;

import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.Texts;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Who is on the socket, fixed at handshake: /ws/conversations/{id}?role=CUSTOMER|OBSERVER&name=...&lastSeq=N */
record Participant(UUID conversationId, Role role, String name, long lastSeq) {

    enum Role {
        CUSTOMER,
        /** An admin as agent: can write only while the conversation is assigned to this name. */
        AGENT,
        OBSERVER
    }

    static Participant fromHandshake(URI uri) {
        List<String> segments = UriComponentsBuilder.fromUri(uri).build().getPathSegments();
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        return new Participant(
                parseConversationId(segments.getLast()),
                parseRole(param(query, "role")),
                Texts.requireText(param(query, "name"), "El nombre", DomainConstants.NAME_MAX_LENGTH),
                parseLastSeq(param(query, "lastSeq")));
    }

    boolean canSend() {
        return role == Role.CUSTOMER || role == Role.AGENT;
    }

    private static String param(MultiValueMap<String, String> query, String name) {
        String value = query.getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static UUID parseConversationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Identificador de conversación inválido.");
        }
    }

    private static Role parseRole(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("role debe ser CUSTOMER, AGENT u OBSERVER.");
        }
    }

    private static long parseLastSeq(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long lastSeq = Long.parseLong(value);
            if (lastSeq < 0) {
                throw new ValidationException("lastSeq no puede ser negativo.");
            }
            return lastSeq;
        } catch (NumberFormatException e) {
            throw new ValidationException("lastSeq debe ser un número.");
        }
    }
}
