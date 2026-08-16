package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Lets the browser learn the id of a conversation it just started. */
    static final String SESSION_ID_HEADER = "X-Chat-Session-Id";

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
    private final ChatSessionService chatSessionService;
    private final String model;

    public ChatController(ChatClient chatClient, ClusterContextProvider contextProvider,
                          ClusterProfileService clusterProfileService, RagService ragService,
                          ChatSessionService chatSessionService,
                          @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.chatClient = chatClient;
        this.contextProvider = contextProvider;
        this.clusterProfileService = clusterProfileService;
        this.ragService = ragService;
        this.chatSessionService = chatSessionService;
        this.model = model;
    }

    /** @param sessionId null starts a new conversation; the id comes back in {@value #SESSION_ID_HEADER}. */
    public record ChatRequest(UUID sessionId, @NotBlank String message) {}

    /**
     * Streams the assistant's reply as plain-text chunks (chunked transfer).
     *
     * The conversation lives in the database, not the browser: the client sends
     * only the new question, and the history the model sees is read back from
     * the session. That keeps the prompt window authoritative on the server and
     * means a client can't slip a fabricated assistant turn into its own
     * context. Only the turns are stored — the cluster snapshot wrapped around
     * them is rebuilt fresh here on every request.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> chat(@PathVariable long clusterId,
                                                      @Valid @RequestBody ChatRequest request,
                                                      Authentication auth) {
        // Records the question before the model is called, so an answer that
        // never arrives doesn't take the question down with it.
        ChatSessionService.TurnContext turn =
            chatSessionService.beginTurn(auth.getName(), clusterId, request.sessionId(), request.message());

        String briefing = clusterProfileService.buildBriefing(clusterId);
        String context = contextProvider.summarize(clusterId) + (briefing.isBlank() ? "" : "\n" + briefing);
        String reference = ragService.buildReferenceBlock(clusterId, request.message());
        String systemPrompt = SYSTEM_PROMPT.formatted(context, reference);
        List<Message> history = buildHistory(turn.history());

        StreamingResponseBody body = out -> {
            StringBuilder answer = new StringBuilder();
            try {
                chatClient.prompt()
                    .system(systemPrompt) // overrides the troubleshooting default system prompt
                    .messages(history)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        answer.append(token);
                        AiStreaming.writeChunk(out, token);
                    })
                    .blockLast();
            } catch (Exception e) {
                log.warn("Chat stream failed: {}", e.getMessage());
                AiStreaming.writeFallbackSafely(out, e);
            } finally {
                // Whatever reached the user gets saved, including a partial answer
                // from an interrupted stream. A run that produced nothing at all
                // saves nothing: the fallback notice is an error message, not an
                // answer, and the transcript is honest about the question going
                // unanswered.
                chatSessionService.completeTurn(turn.sessionId(), answer.toString(), model);
            }
        };

        return ResponseEntity.ok()
            .header(SESSION_ID_HEADER, turn.sessionId().toString())
            .body(body);
    }

    private List<Message> buildHistory(List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            if (ChatMessage.ROLE_ASSISTANT.equalsIgnoreCase(m.getRole())) {
                messages.add(new AssistantMessage(m.getContent()));
            } else {
                messages.add(new UserMessage(m.getContent()));
            }
        }
        return messages;
    }

}
