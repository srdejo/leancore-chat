package com.leancore.chat.application.handler.impl;

import com.leancore.chat.application.dto.request.AgentRequestDto;
import com.leancore.chat.application.dto.request.CreateConversationRequestDto;
import com.leancore.chat.application.dto.response.ConversationResponseDto;
import com.leancore.chat.application.dto.response.MessageResponseDto;
import com.leancore.chat.application.handler.IConversationHandler;
import com.leancore.chat.application.mapper.IChatResponseMapper;
import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.api.IHandoffServicePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationHandler implements IConversationHandler {

    private final IConversationServicePort conversationServicePort;
    private final IHandoffServicePort handoffServicePort;
    private final IChatResponseMapper chatResponseMapper;

    @Override
    public Mono<ConversationResponseDto> createConversation(CreateConversationRequestDto request) {
        return conversationServicePort.createConversation(request == null ? null : request.customerName())
                .map(chatResponseMapper::toResponse);
    }

    @Override
    public Mono<ConversationResponseDto> getConversation(UUID id) {
        return conversationServicePort.getConversation(id).map(chatResponseMapper::toResponse);
    }

    @Override
    public Flux<ConversationResponseDto> listConversations() {
        return conversationServicePort.listConversations().map(chatResponseMapper::toResponse);
    }

    @Override
    public Flux<MessageResponseDto> getMessages(UUID conversationId, Long afterSeq, Integer limit) {
        return conversationServicePort.getHistory(conversationId, afterSeq, limit).map(chatResponseMapper::toResponse);
    }

    @Override
    public Mono<ConversationResponseDto> takeOver(UUID conversationId, AgentRequestDto request) {
        return handoffServicePort.takeOver(conversationId, request == null ? null : request.agentName())
                .map(chatResponseMapper::toResponse);
    }

    @Override
    public Mono<ConversationResponseDto> handBack(UUID conversationId, AgentRequestDto request) {
        return handoffServicePort.handBack(conversationId, request == null ? null : request.agentName())
                .map(chatResponseMapper::toResponse);
    }
}
