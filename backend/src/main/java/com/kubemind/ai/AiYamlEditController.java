package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * "Edit with AI" for the YAML editor: given the manifest currently in the
 * editor and a plain-English instruction, streams back the whole updated
 * manifest. Advisory only — the user still reviews the result and clicks
 * Apply themselves; nothing here touches the cluster.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/resources")
@PreAuthorize("hasRole('ADMIN')")
public class AiYamlEditController {

    private static final Logger log = LoggerFactory.getLogger(AiYamlEditController.class);

    private static final String EDIT_SYSTEM = """
        You are editing an existing Kubernetes manifest inside KubeMind, a cluster dashboard.
        You are given the CURRENT YAML and an instruction describing the desired change.

        Rules:
        - Output the FULL updated YAML manifest — the whole object, not a diff or patch.
        - Output ONLY the YAML. No explanations, no markdown code fences.
        - Keep the same kind, metadata.name, and metadata.namespace unless the instruction
          explicitly asks to change one of them.
        - Preserve everything in the manifest that the instruction doesn't ask you to change.
        - Common mistakes the API server will reject — avoid these:
          - "spec.ports[].nodePort" is only legal when "spec.type" is "NodePort" or
            "LoadBalancer".
          - selector labels on a Service/Deployment/StatefulSet must match the pod
            template's labels.
        - If the instruction is unclear or cannot be safely applied, output the ORIGINAL
          YAML unchanged, followed by a single trailing YAML comment line starting with
          "# " explaining why.""";

    private final ChatClient chatClient;

    public AiYamlEditController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record EditRequest(
        @NotBlank @Size(max = 40_000) String yaml,
        @NotBlank @Size(max = 1_000) String instruction
    ) {}

    @PostMapping(value = "/ai-edit", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public StreamingResponseBody editWithAi(@PathVariable long clusterId, @Valid @RequestBody EditRequest request) {
        String userMessage = "CURRENT YAML:\n" + request.yaml() + "\n\nINSTRUCTION: " + request.instruction();
        return out -> {
            try {
                chatClient.prompt()
                    .system(EDIT_SYSTEM)
                    .user(userMessage)
                    .stream()
                    .content()
                    .doOnNext(token -> AiStreaming.writeChunk(out, token))
                    .blockLast();
            } catch (Exception e) {
                log.warn("AI edit stream failed: {}", e.getMessage());
                AiStreaming.writeChunk(out, "\n\n[The AI service is unavailable. Is Ollama running?]");
            }
        };
    }
}
