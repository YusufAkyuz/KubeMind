package com.kubemind.helm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelmReleaseServiceTest {

    private HelmCliService cli;
    private AuditService auditService;
    private HelmInstallRepository installRepository;
    private HelmReleaseService service;

    @BeforeEach
    void setUp() {
        cli = mock(HelmCliService.class);
        auditService = mock(AuditService.class);
        installRepository = mock(HelmInstallRepository.class);
        service = new HelmReleaseService(cli, auditService, new ObjectMapper(), installRepository);
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedArgs() {
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(cli).run(anyLong(), args.capture());
        return args.getValue();
    }

    @Test
    void historyParsesHelmsRevisionLog() {
        when(cli.run(anyLong(), any())).thenReturn("""
            [
              {"revision":1,"updated":"2026-08-01T10:00:00Z","status":"superseded",
               "chart":"grafana-10.5.15","app_version":"11.0.0","description":"Install complete"},
              {"revision":2,"updated":"2026-08-02T11:00:00Z","status":"deployed",
               "chart":"grafana-10.5.15","app_version":"11.0.0","description":"Upgrade complete"}
            ]""");

        List<HelmRevisionDto> revisions = service.history(7L, "monitoring", "grafana");

        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(1).revision()).isEqualTo(2);
        assertThat(revisions.get(1).status()).isEqualTo("deployed");
        assertThat(revisions.get(1).appVersion()).isEqualTo("11.0.0");
        assertThat(revisions.get(1).description()).isEqualTo("Upgrade complete");
    }

    /**
     * The point of shipping rollback first: it is the one repair action that
     * needs no chart reference, so it works on a release installed from a
     * terminal by someone who never registered a chart repository here.
     */
    @Test
    void rollbackNeverConsultsAChartReference() {
        service.rollback("admin", 7L, "monitoring", "grafana", 3);

        assertThat(capturedArgs()).containsExactly("rollback", "grafana", "3", "-n", "monitoring");
        verify(installRepository, never()).findByClusterIdAndNamespaceAndReleaseName(anyLong(), any(), any());
    }

    @Test
    void rollbackIsAudited() {
        service.rollback("admin", 7L, "monitoring", "grafana", 3);

        verify(auditService).record(eq("admin"), eq(7L), eq("ROLLBACK_HELM_RELEASE"),
            eq("HelmRelease/monitoring/grafana"), eq(Map.of("revision", 3)), eq(true), eq(null));
    }

    /** A failed rollback is the one most worth having in the log, so it is recorded before rethrowing. */
    @Test
    void aRefusedRollbackIsAuditedAndStillSurfaces() {
        when(cli.run(anyLong(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "helm: release is forbidden"));

        assertThatThrownBy(() -> service.rollback("admin", 7L, "monitoring", "grafana", 3))
            .isInstanceOf(ResponseStatusException.class);

        verify(auditService).record(eq("admin"), eq(7L), eq("ROLLBACK_HELM_RELEASE"),
            eq("HelmRelease/monitoring/grafana"), eq(Map.of("revision", 3)), eq(false), any());
    }

    @Test
    void historyIsScopedToTheNamespaceItWasAskedFor() {
        when(cli.run(anyLong(), any())).thenReturn("[]");

        service.history(7L, "monitoring", "grafana");

        assertThat(capturedArgs()).containsExactly("history", "grafana", "-n", "monitoring", "-o", "json");
    }
}
