package com.leancore.chat.application.handler;

import com.leancore.chat.application.dto.request.AgentRequestDto;
import com.leancore.chat.application.dto.request.CreateConversationRequestDto;
import com.leancore.chat.application.dto.response.ConversationResponseDto;
import com.leancore.chat.application.dto.response.MessageResponseDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IConversationHandler {

    Mono<ConversationResponseDto> createConversation(CreateConversationRequestDto request);

    Mono<ConversationResponseDto> getConversation(UUID id);

    Flux<ConversationResponseDto> listConversations();

    Flux<MessageResponseDto> getMessages(UUID conversationId, Long afterSeq, Integer limit);

    Mono<ConversationResponseDto> takeOver(UUID conversationId, AgentRequestDto request);

    Mono<ConversationResponseDto> handBack(UUID conversationId, AgentRequestDto request);
}
