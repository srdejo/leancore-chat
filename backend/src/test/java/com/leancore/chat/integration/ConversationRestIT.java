package com.leancore.chat.integration;

import com.leancore.chat.domain.model.NewMessage;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

class ConversationRestIT extends IntegrationTestBase {

    @Autowired
    private IMessagePersistencePort messages;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void createsAConversation() {
        client.post().uri("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customerName", "Ana"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.customerName").isEqualTo("Ana")
                .jsonPath("$.lastSeq").isEqualTo(0)
                .jsonPath("$.createdAt").isNotEmpty();
    }

    @Test
    void rejectsAnInvalidName() {
        client.post().uri("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customerName", "x".repeat(61)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isNotEmpty();
    }

    @Test
    void unknownConversationIsNotFound() {
        client.get().uri("/api/v1/conversations/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/conversations/{id}/messages", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test
    void malformedIdOrParametersAreBadRequests() {
        client.get().uri("/api/v1/conversations/not-a-uuid").exchange().expectStatus().isBadRequest();
        UUID id = createConversation();
        client.get().uri("/api/v1/conversations/{id}/messages?limit=0", id).exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/conversations/{id}/messages?afterSeq=-1", id).exchange().expectStatus().isBadRequest();
    }

    @Test
    void returnsMessagesAfterSeqInOrder() {
        UUID id = createConversation();
        for (int i = 1; i <= 7; i++) {
            messages.append(NewMessage.fromCustomer(id, UUID.randomUUID(), "Ana", "m" + i)).block();
        }

        client.get().uri("/api/v1/conversations/{id}/messages?afterSeq=3&limit=3", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].seq").isEqualTo(4)
                .jsonPath("$[2].seq").isEqualTo(6)
                .jsonPath("$[0].senderRole").isEqualTo("CUSTOMER");
    }

    @Test
    void listIncludesPreviewOfTheLastMessage() {
        UUID id = createConversation();
        messages.append(NewMessage.fromCustomer(id, UUID.randomUUID(), "Ana", "¿Qué talla necesito?")).block();

        client.get().uri("/api/v1/conversations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(id.toString())
                .jsonPath("$[0].lastPreview").isEqualTo("¿Qué talla necesito?")
                .jsonPath("$[0].lastSenderRole").isEqualTo("CUSTOMER");
    }

    private UUID createConversation() {
        return UUID.fromString(client.post().uri("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customerName", "Ana"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString());
    }
}
