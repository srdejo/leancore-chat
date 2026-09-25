package com.leancore.chat.domain.api;

import com.leancore.chat.domain.model.BotScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface IBotScopeServicePort {

    Mono<BotScope> getActiveScope();

    /** Validates the draft and stores it as a new version, which becomes the active one. */
    Mono<BotScope> saveScope(BotScope draft);

    /** Newest first. */
    Flux<BotScope> getScopeHistory();
}
