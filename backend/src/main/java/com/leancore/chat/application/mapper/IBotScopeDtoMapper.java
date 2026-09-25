package com.leancore.chat.application.mapper;

import com.leancore.chat.application.dto.request.BotScopeRequestDto;
import com.leancore.chat.application.dto.response.BotScopeResponseDto;
import com.leancore.chat.domain.model.BotScope;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface IBotScopeDtoMapper {

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    BotScope toDraft(BotScopeRequestDto request);

    BotScopeResponseDto toResponse(BotScope scope);
}
