package com.leancore.chat.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record AgentRequestDto(
        @Schema(description = "Nombre del agente que toma o devuelve la conversación (1 a 60 caracteres)", example = "Luis")
        String agentName
) {
}
