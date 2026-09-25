package com.leancore.chat.infrastructure.documentation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI chatOpenApi() {
        return new OpenAPI().info(new Info()
                .title("LeanCore Chat API")
                .version("1.0.0")
                .description("Support chat: conversations, message history and bot scope. "
                        + "Real-time messaging runs over WebSocket at /ws/conversations/{id}."));
    }
}
