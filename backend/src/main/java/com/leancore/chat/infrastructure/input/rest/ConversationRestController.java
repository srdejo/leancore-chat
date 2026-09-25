package com.leancore.chat.infrastructure.input.rest;

import com.leancore.chat.application.dto.request.AgentRequestDto;
import com.leancore.chat.application.dto.request.CreateConversationRequestDto;
import com.leancore.chat.application.dto.response.ConversationResponseDto;
import com.leancore.chat.application.dto.response.MessageResponseDto;
import com.leancore.chat.application.handler.IConversationHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationRestController {

    private final IConversationHandler conversationHandler;

    @Operation(summary = "Create a support conversation")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Conversation created"),
            @ApiResponse(responseCode = "400", description = "Invalid customer name", content = @Content)
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConversationResponseDto> createConversation(@RequestBody CreateConversationRequestDto request) {
        return conversationHandler.createConversation(request);
    }

    @Operation(summary = "List conversations, most recent activity first")
    @GetMapping
    public Flux<ConversationResponseDto> listConversations() {
        return conversationHandler.listConversations();
    }

    @Operation(summary = "Get a conversation")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversation found"),
            @ApiResponse(responseCode = "404", description = "Conversation not found", content = @Content)
    })
    @GetMapping("/{id}")
    public Mono<ConversationResponseDto> getConversation(@PathVariable UUID id) {
        return conversationHandler.getConversation(id);
    }

    @Operation(summary = "Get the messages with seq > afterSeq, ordered by seq")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Messages returned"),
            @ApiResponse(responseCode = "400", description = "Invalid afterSeq or limit", content = @Content),
            @ApiResponse(responseCode = "404", description = "Conversation not found", content = @Content)
    })
    @GetMapping("/{id}/messages")
    public Flux<MessageResponseDto> getMessages(
            @PathVariable UUID id,
            @Parameter(description = "Return only messages with a greater seq (default 0)")
            @RequestParam(required = false) Long afterSeq,
            @Parameter(description = "Maximum number of messages (default 100, capped at 500)")
            @RequestParam(required = false) Integer limit) {
        return conversationHandler.getMessages(id, afterSeq, limit);
    }

    @Operation(summary = "A human agent takes the conversation: the bot stops answering and the customer is notified")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversation attended by the agent"),
            @ApiResponse(responseCode = "400", description = "Invalid agent name", content = @Content),
            @ApiResponse(responseCode = "404", description = "Conversation not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Another agent attends it", content = @Content)
    })
    @PostMapping("/{id}/takeover")
    public Mono<ConversationResponseDto> takeOver(@PathVariable UUID id, @RequestBody AgentRequestDto request) {
        return conversationHandler.takeOver(id, request);
    }

    @Operation(summary = "The attending agent hands the conversation back to the bot")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conversation attended by the bot again"),
            @ApiResponse(responseCode = "404", description = "Conversation not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Another agent attends it", content = @Content)
    })
    @PostMapping("/{id}/release")
    public Mono<ConversationResponseDto> handBack(@PathVariable UUID id, @RequestBody AgentRequestDto request) {
        return conversationHandler.handBack(id, request);
    }
}
