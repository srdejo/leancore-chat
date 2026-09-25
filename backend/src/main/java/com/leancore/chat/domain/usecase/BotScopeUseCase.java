package com.leancore.chat.domain.usecase;

import com.leancore.chat.domain.api.IBotScopeServicePort;
import com.leancore.chat.domain.exception.ValidationException;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import com.leancore.chat.domain.util.DomainConstants;
import com.leancore.chat.domain.util.Texts;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BotScopeUseCase implements IBotScopeServicePort {

    private final IBotScopePersistencePort botScopePersistencePort;

    public BotScopeUseCase(IBotScopePersistencePort botScopePersistencePort) {
        this.botScopePersistencePort = botScopePersistencePort;
    }

    @Override
    public Mono<BotScope> getActiveScope() {
        return botScopePersistencePort.findActive();
    }

    @Override
    public Mono<BotScope> saveScope(BotScope draft) {
        return Mono.fromCallable(() -> validate(draft))
                .flatMap(botScopePersistencePort::insertNextVersion);
    }

    @Override
    public Flux<BotScope> getScopeHistory() {
        return botScopePersistencePort.findAll();
    }

    private BotScope validate(BotScope draft) {
        String topic = checked(Texts.requireText(draft.topic(), "El tema", DomainConstants.SCOPE_TOPIC_MAX_LENGTH), "El tema");
        String description = draft.description() == null ? "" : draft.description().strip();
        if (description.length() > DomainConstants.SCOPE_DESCRIPTION_MAX_LENGTH) {
            throw new ValidationException("La descripción admite máximo " + DomainConstants.SCOPE_DESCRIPTION_MAX_LENGTH + " caracteres.");
        }
        checked(description, "La descripción");
        String refusal = checked(Texts.requireText(draft.refusalMessage(), "La negativa", DomainConstants.SCOPE_REFUSAL_MAX_LENGTH), "La negativa");
        return new BotScope(null, topic, description, validateSubtopics(draft.subtopics()), refusal, null);
    }

    private List<String> validateSubtopics(List<String> subtopics) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String subtopic : subtopics) {
            String stripped = subtopic == null ? "" : subtopic.strip();
            if (!stripped.isEmpty()) {
                unique.putIfAbsent(stripped.toLowerCase(Locale.ROOT), stripped);
            }
        }
        if (unique.isEmpty()) {
            throw new ValidationException("Indica al menos un subtema.");
        }
        if (unique.size() > DomainConstants.SCOPE_MAX_SUBTOPICS) {
            throw new ValidationException("Se admiten máximo " + DomainConstants.SCOPE_MAX_SUBTOPICS + " subtemas.");
        }
        for (String subtopic : unique.values()) {
            Texts.requireText(subtopic, "Cada subtema", DomainConstants.SCOPE_SUBTOPIC_MAX_LENGTH);
            checked(subtopic, "Cada subtema");
        }
        return List.copyOf(unique.values());
    }

    private String checked(String value, String field) {
        if (Texts.hasControlChars(value)) {
            throw new ValidationException(field + " contiene caracteres no permitidos.");
        }
        return value;
    }
}
