package com.leancore.chat.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record BotScopeRequestDto(
        @Schema(description = "Tema permitido (1 a 60 caracteres)", example = "bicicletas")
        String topic,
        @Schema(description = "Descripción del tema (hasta 500 caracteres)")
        String description,
        @Schema(description = "Subtemas permitidos (1 a 20, cada uno de 1 a 60 caracteres)")
        List<String> subtopics,
        @Schema(description = "Texto fijo que el bot responde fuera del tema (1 a 300 caracteres)")
        String refusalMessage
) {
}
