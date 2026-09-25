package com.leancore.chat.integration;

import com.leancore.chat.domain.model.BotAnswer;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.model.MessageModel;
import com.leancore.chat.domain.spi.IBotAnswerPort;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real PostgreSQL (one container for every integration test class) and a scripted bot instead of Claude.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestBase.FakeBotConfiguration.class)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected FakeBot fakeBot;

    @BeforeEach
    void resetBot() {
        fakeBot.reset();
    }

    protected URI wsUri(UUID conversationId, String role, String name, long lastSeq) {
        return URI.create("ws://localhost:%d/ws/conversations/%s?role=%s&name=%s&lastSeq=%d"
                .formatted(port, conversationId, role, name, lastSeq));
    }

    /** Scripted replacement for Claude: answers "re: question" after a configurable delay. */
    public static class FakeBot implements IBotAnswerPort {
        private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
        private final AtomicReference<Boolean> inScope = new AtomicReference<>(true);

        public void reset() {
            delay.set(Duration.ZERO);
            inScope.set(true);
        }

        public void delay(Duration value) {
            delay.set(value);
        }

        public void inScope(boolean value) {
            inScope.set(value);
        }

        @Override
        public Mono<BotAnswer> answer(BotScope scope, List<MessageModel> history, MessageModel question) {
            return Mono.just(new BotAnswer(inScope.get(), "re: " + question.content(), "fake", "fake-1"))
                    .delayElement(delay.get());
        }
    }

    @TestConfiguration
    static class FakeBotConfiguration {
        @Bean
        @Primary
        FakeBot fakeBot() {
            return new FakeBot();
        }
    }
}
