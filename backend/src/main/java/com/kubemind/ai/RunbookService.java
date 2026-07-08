package com.kubemind.ai;

import com.kubemind.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-authored runbooks — RAG corpus 2 (Phase A3). "Your AI answers with
 * your procedures": each runbook is embedded on save and retrieved alongside
 * the shipped k8s-docs corpus during Explain/Chat, strictly scoped to the
 * cluster it was written for.
 */
@Service
public class RunbookService {

    private final RunbookRepository repository;
    private final RagService ragService;
    private final AuditService auditService;

    public RunbookService(RunbookRepository repository, RagService ragService, AuditService auditService) {
        this.repository = repository;
        this.ragService = ragService;
        this.auditService = auditService;
    }

    public List<Runbook> list(long clusterId) {
        return repository.findByClusterIdOrderByCreatedAtDesc(clusterId);
    }

    public Runbook create(String username, long clusterId, String title, String content) {
        String ref = "Runbook/" + title;
        try {
            Runbook saved = repository.save(new Runbook(clusterId, title, content, username));
            ragService.indexRunbook(clusterId, saved.getId().toString(), title, content);
            auditService.record(username, clusterId, "CREATE_RUNBOOK", ref, Map.of("title", title), true, null);
            return saved;
        } catch (Exception e) {
            auditService.record(username, clusterId, "CREATE_RUNBOOK", ref, Map.of("title", title), false, e.getMessage());
            throw e;
        }
    }

    public void delete(String username, long clusterId, UUID id) {
        Runbook runbook = repository.findById(id)
            .filter(r -> r.getClusterId() == clusterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Runbook not found"));
        String ref = "Runbook/" + runbook.getTitle();
        try {
            repository.deleteById(id);
            ragService.deleteRunbook(id.toString());
            auditService.record(username, clusterId, "DELETE_RUNBOOK", ref, null, true, null);
        } catch (Exception e) {
            auditService.record(username, clusterId, "DELETE_RUNBOOK", ref, null, false, e.getMessage());
            throw e;
        }
    }
}
