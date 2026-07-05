package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_HISTORY = 12;

    private static final String SYSTEM_PROMPT = """
        You are KubeMind's assistant, a Kubernetes expert embedded in a cluster dashboard. \
        Answer the user's questions clearly and concisely. When the question is about THIS \
        cluster, ground your answer in the live cluster snapshot provided below; if the \
        snapshot doesn't contain the answer, say so rather than guessing. For general \
        Kubernetes questions, answer normally. Never invent resource names or states. \
        Warn before suggesting any destructive command.

        === LIVE CLUSTER SNAPSHOT ===
        %s""";

    private final ChatClient chatClient;
    private final ClusterContextProvider contextProvider;

    public ChatController(ChatClient chatClient, ClusterContextProvider contextProvider) {
        this.chatClient = chatClient;
        this.contextProvider = contextProvider;
    }

    public record ChatMessage(String role, String content) {}

    public record ChatRequest(@NotEmpty List<ChatMessage> messages) {}

    /**
     * Streams the assistant's reply as plain-text chunks (chunked transfer).
     * The client keeps conversation state and sends it back each turn (stateless server).
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public StreamingResponseBody chat(@PathVariable long clusterId,
                                      @Valid @RequestBody ChatRequest request) {
        String context = contextProvider.summarize(clusterId);
        String systemPrompt = SYSTEM_PROMPT.formatted(context);
        List<Message> history = buildHistory(request.messages());

        return out -> {
            try {
                chatClient.prompt()
                    .system(systemPrompt) // overrides the troubleshooting default system prompt
                    .messages(history)
                    .stream()
                    .content()
                    .doOnNext(token -> AiStreaming.writeChunk(out,token))
                    .blockLast();
            } catch (Exception e) {
                log.warn("Chat stream failed: {}", e.getMessage());
                AiStreaming.writeChunk(out,"\n\n[The AI service is unavailable. Is Ollama running?]");
            }
        };
    }

    private List<Message> buildHistory(List<ChatMessage> history) {
        // Keep only the tail of the conversation to bound the prompt size.
        List<ChatMessage> tail = history.size() > MAX_HISTORY
            ? history.subList(history.size() - MAX_HISTORY, history.size())
            : history;

        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : tail) {
            if (m.content() == null || m.content().isBlank()) continue;
            if ("assistant".equalsIgnoreCase(m.role())) {
                messages.add(new AssistantMessage(m.content()));
            } else {
                messages.add(new UserMessage(m.content()));
            }
        }
        return messages;
    }

}
