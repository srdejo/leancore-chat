package com.leancore.chat.domain;

import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import com.leancore.chat.domain.usecase.BotScopeUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotScopeUseCaseTest {

    private IBotScopePersistencePort persistence;
    private BotScopeUseCase useCase;

    @BeforeEach
    void setUp() {
        persistence = mock(IBotScopePersistencePort.class);
        useCase = new BotScopeUseCase(persistence);
        when(persistence.insertNextVersion(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void savesStrippedDeduplicatedSubtopics() {
        var draft = scope(" cafeteras ", List.of(" espresso ", "Espresso", "", "limpieza"), "Solo cafeteras.");

        StepVerifier.create(useCase.saveScope(draft)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<BotScope> saved = ArgumentCaptor.forClass(BotScope.class);
        verify(persistence).insertNextVersion(saved.capture());
        assertThat(saved.getValue().topic()).isEqualTo("cafeteras");
        assertThat(saved.getValue().subtopics()).containsExactly("espresso", "limpieza");
    }

    @Test
    void rejectsEmptyTopic() {
        assertRejected(scope("  ", List.of("a"), "No."));
    }

    @Test
    void rejectsTopicLongerThan60() {
        assertRejected(scope("x".repeat(61), List.of("a"), "No."));
    }

    @Test
    void rejectsDescriptionLongerThan500() {
        assertRejected(new BotScope(null, "tema", "x".repeat(501), List.of("a"), "No.", null));
    }

    @Test
    void rejectsMissingSubtopics() {
        assertRejected(scope("tema", List.of(" ", ""), "No."));
    }

    @Test
    void rejectsMoreThan20Subtopics() {
        List<String> subtopics = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            subtopics.add("subtema " + i);
        }
        assertRejected(scope("tema", subtopics, "No."));
    }

    @Test
    void rejectsSubtopicLongerThan60() {
        assertRejected(scope("tema", List.of("x".repeat(61)), "No."));
    }

    @Test
    void rejectsEmptyOrTooLongRefusal() {
        assertRejected(scope("tema", List.of("a"), " "));
        assertRejected(scope("tema", List.of("a"), "x".repeat(301)));
    }

    @Test
    void rejectsControlCharacters() {
        assertRejected(scope("tema\u0007", List.of("a"), "No."));
        assertRejected(scope("tema", Collections.singletonList("a\u0000b"), "No."));
    }

    private void assertRejected(BotScope draft) {
        StepVerifier.create(useCase.saveScope(draft)).verifyError(ValidationException.class);
        verify(persistence, never()).insertNextVersion(any());
    }

    private static BotScope scope(String topic, List<String> subtopics, String refusal) {
        return new BotScope(null, topic, "", subtopics, refusal, null);
    }
}
