package com.leancore.chat.infrastructure.input.rest;

import com.leancore.chat.application.dto.request.BotScopeRequestDto;
import com.leancore.chat.application.dto.response.BotScopeResponseDto;
import com.leancore.chat.application.handler.IBotScopeHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/bot-scope")
@RequiredArgsConstructor
public class BotScopeRestController {

    private final IBotScopeHandler botScopeHandler;

    @Operation(summary = "Get the active bot scope")
    @GetMapping
    public Mono<BotScopeResponseDto> getActiveScope() {
        return botScopeHandler.getActiveScope();
    }

    @Operation(summary = "Save a new bot scope version; it applies from the next bot reply")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "New version created and active"),
            @ApiResponse(responseCode = "400", description = "Invalid scope", content = @Content)
    })
    @PutMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BotScopeResponseDto> saveScope(@RequestBody BotScopeRequestDto request) {
        return botScopeHandler.saveScope(request);
    }

    @Operation(summary = "List every bot scope version, newest first")
    @GetMapping("/versions")
    public Flux<BotScopeResponseDto> getScopeHistory() {
        return botScopeHandler.getScopeHistory();
    }
}
