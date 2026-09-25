package com.leancore.chat.integration;

import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotScopeIT extends IntegrationTestBase {

    @Autowired
    private IBotScopePersistencePort scopes;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void versionOneIsBicyclesAfterStartup() {
        BotScope first = scopes.findAll().filter(scope -> scope.version() == 1).blockFirst();

        assertThat(first.topic()).isEqualTo("bicicletas");
        assertThat(first.subtopics()).contains("mecánica y mantenimiento", "seguridad al rodar");
        assertThat(first.refusalMessage())
                .isEqualTo("Solo puedo ayudarte con temas de bicicletas. Tu pregunta está fuera de mi alcance.");
    }

    @Test
    void savingCreatesANewActiveVersionAndKeepsHistory() {
        int before = client.get().uri("/api/v1/bot-scope").exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("version") instanceof Integer v ? v : -1;

        client.put().uri("/api/v1/bot-scope").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "cafeteras", "description", "Cafeteras domésticas",
                        "subtopics", List.of("espresso", "limpieza"), "refusalMessage", "Solo cafeteras."))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.version").isEqualTo(before + 1)
                .jsonPath("$.topic").isEqualTo("cafeteras");

        client.get().uri("/api/v1/bot-scope").exchange().expectBody().jsonPath("$.topic").isEqualTo("cafeteras");
        List<Map> history = client.get().uri("/api/v1/bot-scope/versions").exchange().expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(history.getFirst().get("version")).isEqualTo(before + 1);
        assertThat(history.getLast().get("version")).isEqualTo(1);
        assertThat(history.getLast().get("topic")).isEqualTo("bicicletas");
    }

    @Test
    void invalidScopeIsRejectedAndDoesNotChangeTheActiveOne() {
        BotScope active = scopes.findActive().block();

        client.put().uri("/api/v1/bot-scope").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "", "subtopics", List.of(), "refusalMessage", "x".repeat(301)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isNotEmpty();

        assertThat(scopes.findActive().block().version()).isEqualTo(active.version());
    }

    @Test
    void concurrentSavesProduceConsecutiveDistinctVersions() {
        List<Integer> versions = Flux.range(1, 2)
                .flatMap(i -> scopes.insertNextVersion(new BotScope(null, "tema " + i, "", List.of("a"), "No.", null))
                        .subscribeOn(Schedulers.parallel()), 2)
                .map(BotScope::version)
                .sort()
                .collectList()
                .block();

        assertThat(versions.get(1)).isEqualTo(versions.get(0) + 1);
    }
}
