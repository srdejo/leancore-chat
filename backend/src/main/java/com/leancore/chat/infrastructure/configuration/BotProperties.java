package com.leancore.chat.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param timeout          whole reply budget (all providers), enforced by the use case
 * @param providerTimeout  budget of one provider before falling back to the next one
 * @param providerCooldown how long a failed provider is tried last instead of first
 */
@ConfigurationProperties(prefix = "chat.bot")
public record BotProperties(
        String name,
        Duration timeout,
        Duration providerTimeout,
        Duration providerCooldown,
        int historySize,
        int maxConcurrentCalls,
        Duration recoveryWindow,
        Provider anthropic,
        Provider openai
) {
    /**
     * Per-provider settings; null/blank values are not sent, so the provider default applies. Reasoning
     * models (OpenAI gpt-5 family) reject a custom temperature and count reasoning tokens in maxTokens.
     */
    public record Provider(String apiKey, String model, Double temperature, Integer maxTokens, String reasoningEffort) {
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasModel() {
            return model != null && !model.isBlank();
        }

        public boolean hasReasoningEffort() {
            return reasoningEffort != null && !reasoningEffort.isBlank();
        }
    }
}
