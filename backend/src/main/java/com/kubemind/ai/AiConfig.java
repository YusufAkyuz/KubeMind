package com.kubemind.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    static final String SYSTEM_PROMPT = """
        You are a Kubernetes troubleshooting expert. You are given the state of a \
        Kubernetes resource: its spec summary, conditions, container states, recent \
        events, and recent log lines. Explain in plain language what is wrong (or \
        confirm the resource looks healthy) and give concrete, safe next steps. \
        Be concise. Use short paragraphs and bullet points. Never invent details \
        that are not present in the provided state. Do not suggest destructive \
        commands without a warning.""";

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM_PROMPT).build();
    }
}
