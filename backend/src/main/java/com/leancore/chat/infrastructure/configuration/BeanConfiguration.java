package com.leancore.chat.infrastructure.configuration;

import com.leancore.chat.domain.api.IBotReplyServicePort;
import com.leancore.chat.domain.api.IBotScopeServicePort;
import com.leancore.chat.domain.api.IConversationServicePort;
import com.leancore.chat.domain.api.IHandoffServicePort;
import com.leancore.chat.domain.api.IMessageServicePort;
import com.leancore.chat.domain.spi.IBotAnswerPort;
import com.leancore.chat.domain.spi.IBotScopePersistencePort;
import com.leancore.chat.domain.spi.IConversationPersistencePort;
import com.leancore.chat.domain.spi.IMessageBroadcastPort;
import com.leancore.chat.domain.spi.IMessagePersistencePort;
import com.leancore.chat.domain.usecase.BotReplyUseCase;
import com.leancore.chat.domain.usecase.BotScopeUseCase;
import com.leancore.chat.domain.usecase.ConversationUseCase;
import com.leancore.chat.domain.usecase.HandoffUseCase;
import com.leancore.chat.domain.usecase.MessageUseCase;
import com.leancore.chat.infrastructure.out.ai.FallbackBotAnswerAdapter;
import com.leancore.chat.infrastructure.out.ai.LlmBotAnswerAdapter;
import com.leancore.chat.infrastructure.out.broadcast.InMemoryMessageBroadcastAdapter;
import com.leancore.chat.infrastructure.out.r2dbc.adapter.BotScopeAdapter;
import com.leancore.chat.infrastructure.out.r2dbc.adapter.ConversationAdapter;
import com.leancore.chat.infrastructure.out.r2dbc.adapter.MessageAdapter;
import com.leancore.chat.infrastructure.out.r2dbc.mapper.IBotScopeEntityMapper;
import com.leancore.chat.infrastructure.out.r2dbc.mapper.IConversationEntityMapper;
import com.leancore.chat.infrastructure.out.r2dbc.mapper.IMessageEntityMapper;
import com.leancore.chat.infrastructure.out.r2dbc.repository.IBotScopeRepository;
import com.leancore.chat.infrastructure.out.r2dbc.repository.IConversationRepository;
import com.leancore.chat.infrastructure.out.r2dbc.repository.IMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class BeanConfiguration {

    private static final int LLM_QUEUE_CAPACITY = 1_000;

    @Bean
    public IConversationPersistencePort conversationPersistencePort(IConversationRepository conversationRepository,
                                                                    IConversationEntityMapper conversationEntityMapper,
                                                                    R2dbcEntityTemplate entityTemplate,
                                                                    DatabaseClient databaseClient) {
        return new ConversationAdapter(conversationRepository, conversationEntityMapper, entityTemplate, databaseClient);
    }

    @Bean
    public IMessagePersistencePort messagePersistencePort(IMessageRepository messageRepository,
                                                          IMessageEntityMapper messageEntityMapper,
                                                          DatabaseClient databaseClient,
                                                          TransactionalOperator transactionalOperator) {
        return new MessageAdapter(messageRepository, messageEntityMapper, databaseClient, transactionalOperator);
    }

    @Bean
    public IBotScopePersistencePort botScopePersistencePort(IBotScopeRepository botScopeRepository,
                                                            IBotScopeEntityMapper botScopeEntityMapper,
                                                            DatabaseClient databaseClient) {
        return new BotScopeAdapter(botScopeRepository, botScopeEntityMapper, databaseClient);
    }

    @Bean
    public IMessageBroadcastPort messageBroadcastPort() {
        return new InMemoryMessageBroadcastAdapter();
    }

    /** Its thread cap is the global limit of concurrent LLM calls. */
    @Bean(destroyMethod = "dispose")
    public Scheduler botLlmScheduler(BotProperties botProperties) {
        return Schedulers.newBoundedElastic(botProperties.maxConcurrentCalls(), LLM_QUEUE_CAPACITY, "bot-llm");
    }

    /** Anthropic first, OpenAI as fallback; a provider without key is skipped. */
    @Bean
    public IBotAnswerPort botAnswerPort(BotProperties botProperties,
                                        ObjectProvider<AnthropicChatModel> anthropicChatModel,
                                        ObjectProvider<OpenAiChatModel> openAiChatModel,
                                        Scheduler botLlmScheduler,
                                        @Value("classpath:prompts/scoped-support.st") Resource systemTemplate) throws IOException {
        String template = systemTemplate.getContentAsString(StandardCharsets.UTF_8);

        BotProperties.Provider anthropicSettings = botProperties.anthropic();
        var anthropic = llmProvider("anthropic", anthropicSettings, anthropicChatModel,
                AnthropicChatOptions.builder()
                        .model(anthropicSettings.model())
                        .temperature(anthropicSettings.temperature())
                        .maxTokens(anthropicSettings.maxTokens())
                        .build(),
                template, botLlmScheduler);

        BotProperties.Provider openAiSettings = botProperties.openai();
        String openAiModel = openAiSettings.hasModel() ? openAiSettings.model() : OpenAiChatOptions.DEFAULT_CHAT_MODEL;
        // max_completion_tokens (not max_tokens), and no temperature unless configured: gpt-5 models reject both.
        var openAiOptions = OpenAiChatOptions.builder()
                .model(openAiModel)
                .maxCompletionTokens(openAiSettings.maxTokens())
                .temperature(openAiSettings.temperature());
        if (openAiSettings.hasReasoningEffort()) {
            openAiOptions.reasoningEffort(openAiSettings.reasoningEffort());
        }
        var openai = llmProvider("openai", openAiSettings, openAiChatModel, openAiOptions.build(),
                template, botLlmScheduler);

        log.info("Bot providers: anthropic={} ({}), openai={} ({})",
                anthropic.isEnabled() ? "enabled" : "no key", botProperties.anthropic().model(),
                openai.isEnabled() ? "enabled" : "no key", openAiModel);
        if (!anthropic.isEnabled() && !openai.isEnabled()) {
            log.warn("Neither ANTHROPIC_API_KEY nor OPENAI_API_KEY is set: the bot will answer 'not available'.");
        }
        return new FallbackBotAnswerAdapter(List.of(anthropic, openai), botProperties.providerTimeout(),
                botProperties.providerCooldown(), Clock.systemUTC());
    }

    private static LlmBotAnswerAdapter llmProvider(String name, BotProperties.Provider provider,
                                                   ObjectProvider<? extends ChatModel> chatModel, ChatOptions options,
                                                   String template, Scheduler scheduler) {
        ChatModel model = chatModel.getIfAvailable();
        if (!provider.hasApiKey() || model == null) {
            return LlmBotAnswerAdapter.disabled(name);
        }
        return new LlmBotAnswerAdapter(name, ChatClient.create(model), options, template, scheduler);
    }

    @Bean
    public IConversationServicePort conversationServicePort(IConversationPersistencePort conversationPersistencePort,
                                                            IMessagePersistencePort messagePersistencePort) {
        return new ConversationUseCase(conversationPersistencePort, messagePersistencePort);
    }

    @Bean
    public IBotScopeServicePort botScopeServicePort(IBotScopePersistencePort botScopePersistencePort) {
        return new BotScopeUseCase(botScopePersistencePort);
    }

    @Bean
    public IBotReplyServicePort botReplyServicePort(IMessagePersistencePort messagePersistencePort,
                                                    IConversationPersistencePort conversationPersistencePort,
                                                    IBotScopePersistencePort botScopePersistencePort,
                                                    IBotAnswerPort botAnswerPort,
                                                    IMessageBroadcastPort messageBroadcastPort,
                                                    BotProperties botProperties) {
        var settings = new BotReplyUseCase.BotSettings(botProperties.name(), botProperties.historySize(),
                botProperties.timeout(), botProperties.recoveryWindow());
        return new BotReplyUseCase(messagePersistencePort, conversationPersistencePort, botScopePersistencePort, botAnswerPort,
                messageBroadcastPort, settings, Clock.systemUTC());
    }

    @Bean
    public IHandoffServicePort handoffServicePort(IConversationPersistencePort conversationPersistencePort,
                                                  IMessagePersistencePort messagePersistencePort,
                                                  IMessageBroadcastPort messageBroadcastPort) {
        return new HandoffUseCase(conversationPersistencePort, messagePersistencePort, messageBroadcastPort);
    }

    @Bean
    public IMessageServicePort messageServicePort(IMessagePersistencePort messagePersistencePort,
                                                  IMessageBroadcastPort messageBroadcastPort,
                                                  IBotReplyServicePort botReplyServicePort) {
        return new MessageUseCase(messagePersistencePort, messageBroadcastPort, botReplyServicePort);
    }
}
