package com.leancore.chat.domain.exception;

/** The LLM answered, but not in the expected structured format. Carries which LLM it was, when known. */
public class UnparseableBotAnswerException extends DomainException {

    private final String provider;
    private final String model;

    public UnparseableBotAnswerException(String message) {
        this(message, null, null);
    }

    public UnparseableBotAnswerException(String message, String provider, String model) {
        super(message);
        this.provider = provider;
        this.model = model;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }
}
