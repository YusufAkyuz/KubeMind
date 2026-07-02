package com.kubemind.ai;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/k8s")
public class ExplainController {

    private final ExplainService explainService;

    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    public record ExplainResponse(String explanation, boolean cached, String model, Instant createdAt) {}

    @PostMapping("/namespaces/{ns}/pods/{pod}/explain")
    public ExplainResponse explainPod(@PathVariable String ns, @PathVariable String pod) {
        var result = explainService.explainPod(ns, pod);
        return new ExplainResponse(result.explanation(), result.cached(), result.model(), result.createdAt());
    }
}
