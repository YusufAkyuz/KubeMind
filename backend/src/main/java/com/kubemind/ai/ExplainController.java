package com.kubemind.ai;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class ExplainController {

    private final ExplainService explainService;

    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    public record ExplainResponse(String explanation, boolean cached, String model, Instant createdAt, String stateHash) {}

    /** Pods get their own route because their context includes container logs. */
    @PostMapping("/namespaces/{ns}/pods/{pod}/explain")
    public ExplainResponse explainPod(@PathVariable long clusterId,
                                      @PathVariable String ns, @PathVariable String pod) {
        var result = explainService.explainPod(clusterId, ns, pod);
        return toResponse(result);
    }

    /** Generic explain for every other kind KubeMind manages (see ResourceEditService.EDITABLE_KINDS). */
    @PostMapping("/namespaces/{ns}/resources/{kind}/{name}/explain")
    public ExplainResponse explainResource(@PathVariable long clusterId, @PathVariable String ns,
                                           @PathVariable String kind, @PathVariable String name) {
        var result = explainService.explainResource(clusterId, kind, ns, name);
        return toResponse(result);
    }

    private ExplainResponse toResponse(ExplainService.ExplainResult result) {
        return new ExplainResponse(result.explanation(), result.cached(), result.model(), result.createdAt(), result.stateHash());
    }
}
