package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin-authored runbooks (RAG corpus 2) — see RunbookService. ADMIN-only, audited. */
@RestController
@RequestMapping("/api/clusters/{clusterId}/runbooks")
@PreAuthorize("hasRole('ADMIN')")
public class RunbookController {

    private final RunbookService runbookService;

    public RunbookController(RunbookService runbookService) {
        this.runbookService = runbookService;
    }

    public record RunbookDto(UUID id, String title, String content, String createdBy, Instant createdAt) {
        static RunbookDto from(Runbook r) {
            return new RunbookDto(r.getId(), r.getTitle(), r.getContent(), r.getCreatedBy(), r.getCreatedAt());
        }
    }

    public record CreateRunbookRequest(@NotBlank String title, @NotBlank String content) {}

    @GetMapping
    public List<RunbookDto> list(@PathVariable long clusterId) {
        return runbookService.list(clusterId).stream().map(RunbookDto::from).toList();
    }

    @PostMapping
    public RunbookDto create(@PathVariable long clusterId, @Valid @RequestBody CreateRunbookRequest request,
                             Authentication auth) {
        return RunbookDto.from(runbookService.create(auth.getName(), clusterId, request.title(), request.content()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long clusterId, @PathVariable UUID id, Authentication auth) {
        runbookService.delete(auth.getName(), clusterId, id);
        return ResponseEntity.noContent().build();
    }
}
