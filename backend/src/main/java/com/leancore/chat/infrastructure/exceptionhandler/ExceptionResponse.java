package com.leancore.chat.infrastructure.exceptionhandler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExceptionResponse {
    INVALID_REQUEST("La solicitud no es válida.");

    private final String message;
}
