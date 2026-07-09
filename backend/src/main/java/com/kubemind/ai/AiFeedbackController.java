package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thumbs-up/down on an AI answer (Explain, Chat, ...). Any authenticated user can
 * rate — same authorization level as the AI surfaces themselves (see SecurityConfig;
 * these are read/advisory endpoints, not ADMIN-gated writes against the cluster).
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/ai-feedback")
public class AiFeedbackController {

    private final AiFeedbackService feedbackService;

    public AiFeedbackController(AiFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record FeedbackRequest(
        @NotBlank @Pattern(regexp = "EXPLAIN|CHAT|DRAFT|ANALYZE|EDIT") String surface,
        @NotBlank String contextHash,
        @NotBlank @Pattern(regexp = "UP|DOWN") String rating
    ) {}

    @PostMapping
    public ResponseEntity<Void> submit(@PathVariable long clusterId,
                                       @Valid @RequestBody FeedbackRequest request,
                                       Authentication auth) {
        feedbackService.record(clusterId, request.surface(), request.contextHash(), request.rating(), auth.getName());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
