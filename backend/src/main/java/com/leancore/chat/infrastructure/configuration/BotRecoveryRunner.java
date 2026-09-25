package com.leancore.chat.infrastructure.configuration;

import com.leancore.chat.domain.api.IBotReplyServicePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** On startup, answers customer messages left without a bot reply by a previous stop or crash. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotRecoveryRunner {

    private final IBotReplyServicePort botReplyServicePort;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingReplies() {
        botReplyServicePort.recoverPendingReplies()
                .subscribe(null, error -> log.error("Could not recover pending bot replies", error));
    }
}
