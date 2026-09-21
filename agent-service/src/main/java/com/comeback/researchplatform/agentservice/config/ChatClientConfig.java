package com.comeback.researchplatform.agentservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    // Spring AI autoconfigures a ChatModel bean from spring.ai.openai.* (pointed
    // at DeepSeek -- see application.yml); this just wraps it in the fluent
    // ChatClient builder ResearcherService uses.
    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
