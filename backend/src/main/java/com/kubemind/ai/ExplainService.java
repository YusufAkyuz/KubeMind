package com.kubemind.ai;

import com.kubemind.k8s.ResourceEditService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class ExplainService {

    private final PodContextCollector podContextCollector;
    private final ResourceContextCollector resourceContextCollector;
    private final AiDiagnosisRepository repository;
    private final ClusterProfileService clusterProfileService;
    private final RagService ragService;
    private final ChatClient chatClient;
    private final String model;

    public ExplainService(PodContextCollector podContextCollector,
                          ResourceContextCollector resourceContextCollector,
                          AiDiagnosisRepository repository,
                          ClusterProfileService clusterProfileService,
                          RagService ragService,
                          ChatClient chatClient,
                          @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.podContextCollector = podContextCollector;
        this.resourceContextCollector = resourceContextCollector;
        this.repository = repository;
        this.clusterProfileService = clusterProfileService;
        this.ragService = ragService;
        this.chatClient = chatClient;
        this.model = model;
    }

    public record ExplainResult(String explanation, boolean cached, String model, Instant createdAt) {}

    public ExplainResult explainPod(long clusterId, String namespace, String podName) {
        String context = podContextCollector.collect(clusterId, namespace, podName);
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Pod '" + podName + "' not found in namespace '" + namespace + "'");
        }
        // Node usage fluctuates constantly — never let it into the cache key (see liveNodeUsage javadoc).
        String liveUsage = podContextCollector.liveNodeUsage(clusterId, namespace, podName);
        return explain(clusterId, "Pod", namespace, podName, context, liveUsage);
    }

    public ExplainResult explainResource(long clusterId, String kind, String namespace, String name) {
        if (!ResourceEditService.EDITABLE_KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Kind '" + kind + "' is not supported here.");
        }
        String context = resourceContextCollector.collect(clusterId, kind, namespace, name);
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                kind + " '" + name + "' not found in namespace '" + namespace + "'");
        }
        return explain(clusterId, kind, namespace, name, context, null);
    }

    private ExplainResult explain(long clusterId, String kind, String namespace, String name,
                                  String context, String liveEnrichment) {
        String stateHash = sha256(context);

        var cachedDiagnosis = repository.findFirstByStateHash(stateHash);
        if (cachedDiagnosis.isPresent()) {
            var d = cachedDiagnosis.get();
            return new ExplainResult(d.getResponse(), true, d.getModel(), d.getCreatedAt());
        }

        String promptContext = withPreviousDiagnosis(clusterId, kind, namespace, name, context);
        if (liveEnrichment != null && !liveEnrichment.isBlank()) {
            promptContext = promptContext + "\n=== LIVE SIGNALS (not part of the cached state) ===\n" + liveEnrichment + "\n";
        }
        String briefing = clusterProfileService.buildBriefing(clusterId);
        if (!briefing.isBlank()) {
            promptContext = promptContext + "\n" + briefing;
        }
        String reference = ragService.buildReferenceBlock(clusterId, context);
        if (!reference.isBlank()) {
            promptContext = promptContext + "\n" + reference;
        }

        String explanation;
        try {
            explanation = chatClient.prompt().user(promptContext).call().content();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI model is unavailable. Is Ollama running? (" + e.getMessage() + ")");
        }
        if (explanation == null || explanation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI model returned an empty response");
        }

        var diagnosis = new AiDiagnosis(clusterId, kind, namespace, name, stateHash, promptContext, explanation, model);
        try {
            diagnosis = repository.save(diagnosis);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request cached the same state first — serve our fresh result anyway.
        }
        ragService.indexDiagnosisBestEffort(clusterId, "diag-" + stateHash, kind + "/" + namespace + "/" + name, explanation);
        return new ExplainResult(explanation, false, model,
            diagnosis.getCreatedAt() != null ? diagnosis.getCreatedAt() : Instant.now());
    }

    /**
     * If this resource was diagnosed before under a different state, append that prior
     * diagnosis to the prompt — the model can note whether the issue is the same, worse,
     * resolved, or new. The cache key (stateHash) is computed on the raw context only, so
     * this enrichment never affects cache hits/misses.
     */
    private String withPreviousDiagnosis(long clusterId, String kind, String namespace, String name, String context) {
        return repository.findFirstByClusterIdAndResourceKindAndResourceNsAndResourceNameOrderByCreatedAtDesc(
                clusterId, kind, namespace, name)
            .map(prev -> context + "\n=== PREVIOUS DIAGNOSIS (resource state has changed since) ===\n"
                + prev.getResponse() + "\n")
            .orElse(context);
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
