package com.leancore.chat.application.handler.impl;

import com.leancore.chat.application.dto.request.BotScopeRequestDto;
import com.leancore.chat.application.dto.response.BotScopeResponseDto;
import com.leancore.chat.application.handler.IBotScopeHandler;
import com.leancore.chat.application.mapper.IBotScopeDtoMapper;
import com.leancore.chat.domain.api.IBotScopeServicePort;
import com.leancore.chat.domain.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BotScopeHandler implements IBotScopeHandler {

    private final IBotScopeServicePort botScopeServicePort;
    private final IBotScopeDtoMapper botScopeDtoMapper;

    @Override
    public Mono<BotScopeResponseDto> getActiveScope() {
        return botScopeServicePort.getActiveScope().map(botScopeDtoMapper::toResponse);
    }

    @Override
    public Mono<BotScopeResponseDto> saveScope(BotScopeRequestDto request) {
        if (request == null) {
            return Mono.error(new ValidationException("El cuerpo de la solicitud es obligatorio."));
        }
        return botScopeServicePort.saveScope(botScopeDtoMapper.toDraft(request)).map(botScopeDtoMapper::toResponse);
    }

    @Override
    public Flux<BotScopeResponseDto> getScopeHistory() {
        return botScopeServicePort.getScopeHistory().map(botScopeDtoMapper::toResponse);
    }
}
