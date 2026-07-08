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
        You are KubeMind's assistant, a senior Kubernetes SRE embedded in a cluster dashboard.

        Before answering, silently decide which of these the question is — never mention this \
        classification, its labels, or your reasoning about it in the reply itself, just answer:
        - A CLUSTER question asks about THIS cluster's actual resources, counts, health, or \
        state (e.g. "how many services do I have", "why is pod X failing", "what's broken"). \
        Ground your answer in the LIVE CLUSTER SNAPSHOT below. If the snapshot genuinely \
        doesn't cover it, say so plainly instead of guessing. When diagnosing a problem, \
        reason in this order: recent events → pod/container status → node health → what \
        changed recently, and recognize common patterns (CrashLoopBackOff, ImagePullBackOff, \
        OOMKilled, unschedulable Pending, probe failures, stuck PVCs).
        - Anything else — Kubernetes concepts, opinions, comparisons between technologies, \
        questions about you the assistant, small talk — is a GENERAL question. Answer directly \
        from your own knowledge. Do NOT mention the snapshot, do NOT say the information isn't \
        in the snapshot — the snapshot is irrelevant to these questions and checking it would \
        be a mistake.

        Regardless of question type, always check the REFERENCE MATERIAL section below first, \
        if present — it can contain a runbook or note written specifically for this workspace \
        (e.g. a naming convention, a fact about the team, a specific procedure) that should \
        take priority over your default knowledge or the classification above. If nothing \
        there is relevant, ignore it and answer normally.

        Examples (for your reasoning only, never repeat this format in a reply): \
        "kaç servisim var?" is a cluster question, check the snapshot's SERVICES section. \
        "Kubernetes mi Java mı daha karmaşık?" is a general question, just answer the opinion.

        Be clear and concise. Never invent cluster resource names or states. Never write things \
        like "(a) CLUSTER question" or "this is a GENERAL question" in your reply. Warn before \
        suggesting any destructive command.

        === LIVE CLUSTER SNAPSHOT ===
        %s

        %s""";

    private final ChatClient chatClient;
    private final ClusterContextProvider contextProvider;
    private final ClusterProfileService clusterProfileService;
    private final RagService ragService;

    public ChatController(ChatClient chatClient, ClusterContextProvider contextProvider,
                          ClusterProfileService clusterProfileService, RagService ragService) {
        this.chatClient = chatClient;
        this.contextProvider = contextProvider;
        this.clusterProfileService = clusterProfileService;
        this.ragService = ragService;
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
        String briefing = clusterProfileService.buildBriefing(clusterId);
        String context = contextProvider.summarize(clusterId) + (briefing.isBlank() ? "" : "\n" + briefing);

        String lastUserMessage = request.messages().stream()
            .filter(m -> "user".equalsIgnoreCase(m.role()) && m.content() != null && !m.content().isBlank())
            .reduce((first, second) -> second).map(ChatMessage::content).orElse("");
        String reference = lastUserMessage.isBlank() ? "" : ragService.buildReferenceBlock(clusterId, lastUserMessage);
        String systemPrompt = SYSTEM_PROMPT.formatted(context, reference);
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
