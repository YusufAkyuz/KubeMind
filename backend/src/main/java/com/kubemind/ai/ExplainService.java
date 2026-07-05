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
    private final ChatClient chatClient;
    private final String model;

    public ExplainService(PodContextCollector podContextCollector,
                          ResourceContextCollector resourceContextCollector,
                          AiDiagnosisRepository repository,
                          ChatClient chatClient,
                          @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.podContextCollector = podContextCollector;
        this.resourceContextCollector = resourceContextCollector;
        this.repository = repository;
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
        return explain(clusterId, "Pod", namespace, podName, context);
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
        return explain(clusterId, kind, namespace, name, context);
    }

    private ExplainResult explain(long clusterId, String kind, String namespace, String name, String context) {
        String stateHash = sha256(context);

        var cachedDiagnosis = repository.findFirstByStateHash(stateHash);
        if (cachedDiagnosis.isPresent()) {
            var d = cachedDiagnosis.get();
            return new ExplainResult(d.getResponse(), true, d.getModel(), d.getCreatedAt());
        }

        String explanation;
        try {
            explanation = chatClient.prompt().user(context).call().content();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI model is unavailable. Is Ollama running? (" + e.getMessage() + ")");
        }
        if (explanation == null || explanation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI model returned an empty response");
        }

        var diagnosis = new AiDiagnosis(clusterId, kind, namespace, name, stateHash, context, explanation, model);
        try {
            diagnosis = repository.save(diagnosis);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request cached the same state first — serve our fresh result anyway.
        }
        return new ExplainResult(explanation, false, model,
            diagnosis.getCreatedAt() != null ? diagnosis.getCreatedAt() : Instant.now());
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
