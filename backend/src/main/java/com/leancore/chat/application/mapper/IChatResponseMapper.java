package com.leancore.chat.application.mapper;

import com.leancore.chat.application.dto.response.ConversationResponseDto;
import com.leancore.chat.application.dto.response.MessageResponseDto;
import com.leancore.chat.domain.model.ConversationModel;
import com.leancore.chat.domain.model.MessageModel;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IChatResponseMapper {

    ConversationResponseDto toResponse(ConversationModel conversation);

    MessageResponseDto toResponse(MessageModel message);
}
