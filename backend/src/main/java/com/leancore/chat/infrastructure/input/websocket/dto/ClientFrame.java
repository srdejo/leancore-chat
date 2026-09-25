package com.leancore.chat.infrastructure.input.websocket.dto;

import java.util.UUID;

/** Frame sent by the browser. The only type is SEND. */
public record ClientFrame(String type, UUID clientMessageId, String content) {

    public static final String SEND = "SEND";
}
