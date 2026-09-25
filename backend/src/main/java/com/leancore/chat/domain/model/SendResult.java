package com.leancore.chat.domain.model;

/** duplicate = the clientMessageId was already stored; message is the original row. */
public record SendResult(MessageModel message, boolean duplicate) {
}
