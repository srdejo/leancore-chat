package com.leancore.chat.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateConversationRequestDto(
        @Schema(description = "Nombre visible del cliente (1 a 60 caracteres)", example = "Ana")
        String customerName
) {
}
