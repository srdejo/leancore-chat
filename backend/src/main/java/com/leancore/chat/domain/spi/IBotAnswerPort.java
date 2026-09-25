package com.leancore.chat.domain.spi;

import com.leancore.chat.domain.model.BotAnswer;
import com.leancore.chat.domain.model.BotScope;
import com.leancore.chat.domain.model.MessageModel;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IBotAnswerPort {

    /**
     * Asks the LLM, restricted to the given scope. Errors with UnparseableBotAnswerException when the
     * answer has no valid structure, and BotUnavailableException when the LLM cannot be used.
     */
    Mono<BotAnswer> answer(BotScope scope, List<MessageModel> history, MessageModel question);
}
