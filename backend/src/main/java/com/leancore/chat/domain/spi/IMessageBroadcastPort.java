package com.leancore.chat.domain.spi;

import com.leancore.chat.domain.model.ChatEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** Live fan-out. Best effort: anything lost here is recovered from the database on resume. */
public interface IMessageBroadcastPort {

    void publish(ChatEvent event);

    /** Events of one conversation published after subscribing. */
    Flux<ChatEvent> stream(UUID conversationId);
}
