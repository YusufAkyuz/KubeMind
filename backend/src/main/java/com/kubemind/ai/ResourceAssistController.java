package com.kubemind.ai;

import com.kubemind.k8s.ResourceCreationService;
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
 * AI assistance for the Create Resource flow: drafting a manifest from a plain-
 * English request, and reviewing a manifest the user is about to apply. Both are
 * advisory-only — neither endpoint touches the cluster (CLAUDE.md: "AI explains,
 * never acts"). ADMIN-only because the only caller is the ADMIN-gated Create page.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/namespaces/{ns}/resources")
@PreAuthorize("hasRole('ADMIN')")
public class ResourceAssistController {

    private static final Logger log = LoggerFactory.getLogger(ResourceAssistController.class);

    private static final String DRAFT_SYSTEM = """
        You are a Kubernetes manifest generator embedded in KubeMind, a cluster dashboard.
        Given the user's request, output exactly ONE valid Kubernetes YAML manifest for
        namespace "%s".

        Rules:
        - Output ONLY the YAML. No explanations, no markdown code fences, no comments about
          what you did.
        - The manifest's "kind" MUST be one of: %s
        - Set metadata.namespace to "%s".
        - Prefer minimal, production-sane defaults: resource requests/limits for workloads,
          a readiness/liveness probe when it makes sense, explicit image tags (never "latest").
        - Never fabricate real Secret data; if a Secret is requested, use obviously-fake
          placeholder values.
        - Common mistakes the API server will reject — avoid these:
          - "spec.ports[].nodePort" is only legal when "spec.type" is "NodePort" or
            "LoadBalancer". If the request implies external/node-level access, set
            spec.type accordingly; otherwise omit nodePort entirely (a plain ClusterIP
            Service never sets it).
          - selector labels on a Service/Deployment/StatefulSet must actually match the
            pod template's labels, or nothing will route to the pods.
          - a Deployment/StatefulSet/DaemonSet needs spec.selector.matchLabels to be a
            subset of spec.template.metadata.labels.

        If the request cannot be satisfied with a single manifest of an allowed kind, output
        a single-line YAML comment starting with "# " explaining why, and nothing else.""";

    private static final String ANALYZE_SYSTEM = """
        You are a Kubernetes manifest reviewer. The user is about to apply the following
        manifest to namespace "%s" of a live cluster. Review it for:
        - Correctness (will the API server likely accept it as-is?)
        - Security concerns (privileged containers, missing resource limits, host access,
          overly broad permissions)
        - Best-practice gaps (missing probes, no resource requests, ":latest" tags, missing
          labels)

        Be concise: a short verdict first, then bullet points for anything worth flagging.
        If the manifest looks solid, say so plainly. Never invent fields that are not
        present. This is advisory only — you are not applying anything.""";

    private final ChatClient chatClient;
    private final ResourceCreationService resourceCreationService;

    public ResourceAssistController(ChatClient chatClient, ResourceCreationService resourceCreationService) {
        this.chatClient = chatClient;
        this.resourceCreationService = resourceCreationService;
    }

    public record DraftRequest(@NotBlank @Size(max = 2000) String prompt) {}

    public record AnalyzeRequest(@NotBlank @Size(max = 40_000) String yaml) {}

    @PostMapping(value = "/draft", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public StreamingResponseBody draft(@PathVariable long clusterId, @PathVariable String ns,
                                       @Valid @RequestBody DraftRequest request) {
        String allowedKinds = String.join(", ", resourceCreationService.allowedKinds());
        String system = DRAFT_SYSTEM.formatted(ns, allowedKinds, ns);
        return out -> streamCompletion(out, system, request.prompt());
    }

    @PostMapping(value = "/analyze", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public StreamingResponseBody analyze(@PathVariable long clusterId, @PathVariable String ns,
                                         @Valid @RequestBody AnalyzeRequest request) {
        String system = ANALYZE_SYSTEM.formatted(ns);
        // The pasted manifest is user-authored, possibly copy-pasted from a real
        // Secret — scrub it before it reaches the model, same as everywhere else.
        String redacted = Redactor.redactText(request.yaml());
        return out -> streamCompletion(out, system, redacted);
    }

    private void streamCompletion(java.io.OutputStream out, String system, String userMessage) {
        try {
            chatClient.prompt()
                .system(system) // overrides the troubleshooting default system prompt
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(token -> AiStreaming.writeChunk(out, token))
                .blockLast();
        } catch (Exception e) {
            log.warn("AI assist stream failed: {}", e.getMessage());
            AiStreaming.writeFallbackSafely(out, e);
        }
    }
}
