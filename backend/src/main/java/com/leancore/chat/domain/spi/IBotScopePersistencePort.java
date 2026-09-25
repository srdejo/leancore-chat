package com.leancore.chat.domain.spi;

import com.leancore.chat.domain.model.BotScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface IBotScopePersistencePort {

    Mono<BotScope> findActive();

    /** Inserts the scope as max(version) + 1 and returns it with its version and date. */
    Mono<BotScope> insertNextVersion(BotScope scope);

    /** Newest first. */
    Flux<BotScope> findAll();
}
