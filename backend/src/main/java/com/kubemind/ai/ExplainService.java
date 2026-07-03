package com.kubemind.ai;

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

    private final PodContextCollector contextCollector;
    private final AiDiagnosisRepository repository;
    private final ChatClient chatClient;
    private final String model;

    public ExplainService(PodContextCollector contextCollector,
                          AiDiagnosisRepository repository,
                          ChatClient chatClient,
                          @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.contextCollector = contextCollector;
        this.repository = repository;
        this.chatClient = chatClient;
        this.model = model;
    }

    public record ExplainResult(String explanation, boolean cached, String model, Instant createdAt) {}

    public ExplainResult explainPod(long clusterId, String namespace, String podName) {
        String context = contextCollector.collect(clusterId, namespace, podName);
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Pod '" + podName + "' not found in namespace '" + namespace + "'");
        }

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

        var diagnosis = new AiDiagnosis(clusterId, "Pod", namespace, podName, stateHash, context, explanation, model);
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
