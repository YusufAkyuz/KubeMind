package com.kubemind.ai;

import com.kubemind.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunbookServiceTest {

    private RunbookRepository repository;
    private RagService ragService;
    private AuditService auditService;
    private RunbookService service;

    @BeforeEach
    void setUp() {
        repository = mock(RunbookRepository.class);
        ragService = mock(RagService.class);
        auditService = mock(AuditService.class);
        service = new RunbookService(repository, ragService, auditService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createIndexesAndAudits() {
        service.create("admin", 7L, "Restart a stuck pod", "kubectl delete pod ...");

        verify(ragService).indexRunbook(eq(7L), anyString(), eq("Restart a stuck pod"), eq("kubectl delete pod ..."));
        verify(auditService).record(eq("admin"), eq(7L), eq("CREATE_RUNBOOK"), anyString(), any(), eq(true), eq(null));
    }

    @Test
    void deleteRejectsRunbookBelongingToAnotherCluster() {
        UUID id = UUID.randomUUID();
        Runbook other = new Runbook(99L, "Someone else's runbook", "content", "other-admin");
        when(repository.findById(id)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.delete("admin", 7L, id))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");

        verify(repository, never()).deleteById(any());
        verify(ragService, never()).deleteRunbook(anyString());
    }

    @Test
    void deleteRemovesBothTheRowAndTheEmbedding() {
        UUID id = UUID.randomUUID();
        Runbook mine = new Runbook(7L, "My runbook", "content", "admin");
        when(repository.findById(id)).thenReturn(Optional.of(mine));

        service.delete("admin", 7L, id);

        verify(repository, times(1)).deleteById(id);
        verify(ragService, times(1)).deleteRunbook(id.toString());
        verify(auditService).record(eq("admin"), eq(7L), eq("DELETE_RUNBOOK"), anyString(), eq(null), eq(true), eq(null));
    }

    @Test
    void listQueriesOnlyThisCluster() {
        service.list(7L);

        verify(repository).findByClusterIdOrderByCreatedAtDesc(7L);
    }
}
