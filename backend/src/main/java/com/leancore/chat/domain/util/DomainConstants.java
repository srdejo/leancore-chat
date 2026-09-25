package com.leancore.chat.domain.util;

public final class DomainConstants {

    public static final int NAME_MAX_LENGTH = 60;
    public static final int CONTENT_MAX_LENGTH = 2000;
    public static final int PREVIEW_MAX_LENGTH = 120;
    public static final int HISTORY_DEFAULT_LIMIT = 100;
    public static final int HISTORY_MAX_LIMIT = 500;

    public static final int SCOPE_TOPIC_MAX_LENGTH = 60;
    public static final int SCOPE_DESCRIPTION_MAX_LENGTH = 500;
    public static final int SCOPE_MAX_SUBTOPICS = 20;
    public static final int SCOPE_SUBTOPIC_MAX_LENGTH = 60;
    public static final int SCOPE_REFUSAL_MAX_LENGTH = 300;

    public static final String BOT_UNAVAILABLE_MESSAGE =
            "El asistente no está disponible en este momento. Intenta de nuevo más tarde.";
    public static final String SYSTEM_SENDER_NAME = "Sistema";

    private DomainConstants() {
    }
}
