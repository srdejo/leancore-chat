package com.leancore.chat.application.handler;

import com.leancore.chat.application.dto.request.BotScopeRequestDto;
import com.leancore.chat.application.dto.response.BotScopeResponseDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface IBotScopeHandler {

    Mono<BotScopeResponseDto> getActiveScope();

    Mono<BotScopeResponseDto> saveScope(BotScopeRequestDto request);

    Flux<BotScopeResponseDto> getScopeHistory();
}
