package com.leancore.chat.infrastructure.exceptionhandler;

import com.leancore.chat.domain.exception.ConversationConflictException;
import com.leancore.chat.domain.exception.ConversationNotFoundException;
import com.leancore.chat.domain.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import java.util.Collections;
import java.util.Map;

@RestControllerAdvice
public class ControllerAdvisor {

    private static final String MESSAGE = "message";

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleConversationNotFound(ConversationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Collections.singletonMap(MESSAGE, exception.getMessage()));
    }

    @ExceptionHandler(ConversationConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConversationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(MESSAGE, exception.getMessage(), "agentName", String.valueOf(exception.agentName())));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(ValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap(MESSAGE, exception.getMessage()));
    }

    /** Malformed body, UUID or number in the request. */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Map<String, String>> handleInvalidInput(ServerWebInputException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap(MESSAGE, ExceptionResponse.INVALID_REQUEST.getMessage()));
    }
}
