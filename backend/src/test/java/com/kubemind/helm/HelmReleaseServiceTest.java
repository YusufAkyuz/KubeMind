package com.kubemind.helm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelmReleaseServiceTest {

    private HelmCliService cli;
    private AuditService auditService;
    private HelmInstallRepository installRepository;
    private HelmReleaseChartExtractor extractor;
    private HelmReleaseService service;

    @BeforeEach
    void setUp() {
        cli = mock(HelmCliService.class);
        auditService = mock(AuditService.class);
        installRepository = mock(HelmInstallRepository.class);
        extractor = mock(HelmReleaseChartExtractor.class);
        when(extractor.extract(anyLong(), any(), any())).thenReturn(Optional.empty());
        service = new HelmReleaseService(cli, auditService, new ObjectMapper(), installRepository, extractor);
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

    private HelmReleaseChartExtractor.ExtractedChart storedChart(Path dir) {
        return new HelmReleaseChartExtractor.ExtractedChart(dir, "replicas: 1\n", "kind: ConfigMap\n");
    }

    /**
     * The whole point of phase 2: a release nobody installed through KubeMind,
     * with no repository registered, is still editable — the chart comes out of
     * the cluster.
     */
    @Test
    void valuesUpgradeUsesTheChartStoredInTheClusterAndNeverTouchesARepository(@TempDir Path dir) {
        when(extractor.extract(anyLong(), any(), any())).thenReturn(Optional.of(storedChart(dir)));
        when(extractor.rendersTheSameManifest(any(), anyLong(), any(), any(), any())).thenReturn(true);

        service.upgradeValues("admin", 7L, "monitoring", "grafana", "replicas: 3\n");

        List<String> args = capturedArgs();
        assertThat(args).startsWith("upgrade", "grafana", dir.toString(), "-n", "monitoring");
        verify(installRepository, never()).findByClusterIdAndNamespaceAndReleaseName(anyLong(), any(), any());
        verify(auditService).record(eq("admin"), eq(7L), eq("UPGRADE_HELM_VALUES"), any(),
            eq(Map.of("chartSource", "stored-chart")), eq(true), eq(null));
    }

    /**
     * Helm 4 does not persist subcharts in the release, so a rebuilt chart can
     * render strictly fewer resources than what is running. Applying it would
     * delete the difference — the gate exists to stop exactly that, and this
     * locks in that a failed gate does not reach `helm upgrade`.
     */
    @Test
    void anIncompleteRebuiltChartIsNeverApplied(@TempDir Path dir) {
        when(extractor.extract(anyLong(), any(), any())).thenReturn(Optional.of(storedChart(dir)));
        when(extractor.rendersTheSameManifest(any(), anyLong(), any(), any(), any())).thenReturn(false);
        when(installRepository.findByClusterIdAndNamespaceAndReleaseName(7L, "monitoring", "grafana"))
            .thenReturn(Optional.of(new HelmInstall(7L, "monitoring", "grafana", "bitnami/grafana")));

        service.upgradeValues("admin", 7L, "monitoring", "grafana", "replicas: 3\n");

        assertThat(capturedArgs()).startsWith("upgrade", "grafana", "bitnami/grafana");
        verify(auditService).record(eq("admin"), eq(7L), eq("UPGRADE_HELM_VALUES"), any(),
            eq(Map.of("chartSource", "bitnami/grafana")), eq(true), eq(null));
    }

    /** Neither a usable stored chart nor a repository reference: refuse, don't guess. */
    @Test
    void withNothingToUpgradeAgainstItRefusesInsteadOfGuessing(@TempDir Path dir) {
        when(extractor.extract(anyLong(), any(), any())).thenReturn(Optional.of(storedChart(dir)));
        when(extractor.rendersTheSameManifest(any(), anyLong(), any(), any(), any())).thenReturn(false);
        when(cli.run(anyLong(), any())).thenReturn(""); // resolveChartRef finds nothing

        assertThatThrownBy(() -> service.upgradeValues("admin", 7L, "monitoring", "grafana", "x: 1"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409")
            .hasMessageContaining("Link its chart");

        verify(auditService).record(eq("admin"), eq(7L), eq("UPGRADE_HELM_VALUES"), any(),
            eq(Map.of("chartSource", "none")), eq(false), any());
    }

    /**
     * The editor round-trips this string straight back into a values file on
     * save, and plain `helm get values` prefixes a "USER-SUPPLIED VALUES:"
     * header — which parses as a top-level key with a null value and quietly
     * accumulates in the release's values on every upgrade.
     */
    @Test
    void valuesAreFetchedAsPlainYamlWithoutHelmsHeaderLine() {
        when(cli.run(anyLong(), any())).thenReturn("");
        when(cli.runAllowingEmpty(anyLong(), any())).thenReturn("");

        service.detail(7L, "monitoring", "grafana");

        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(cli, atLeastOnce()).run(anyLong(), args.capture());
        assertThat(args.getAllValues())
            .anySatisfy(a -> assertThat(a).containsExactly("get", "values", "grafana", "-n", "monitoring", "-o", "yaml"));
    }

    /**
     * A release with no repository behind it is not an error state any more —
     * it simply has no update to offer, and everything else about it still works.
     */
    @Test
    void withoutARepositoryThereIsSimplyNoUpdateToOffer() {
        when(cli.run(anyLong(), any())).thenReturn("{\"chart\":\"grafana\",\"version\":\"10.5.15\"}");
        when(cli.runAllowingEmpty(anyLong(), any())).thenReturn("[]");

        var update = service.chartUpdate(7L, "monitoring", "grafana");

        assertThat(update.chartRef()).isNull();
        assertThat(update.currentVersion()).isEqualTo("10.5.15");
        assertThat(update.updateAvailable()).isFalse();
    }

    @Test
    void reportsANewerChartWhenTheLinkedRepositoryHasOne() {
        when(installRepository.findByClusterIdAndNamespaceAndReleaseName(7L, "monitoring", "grafana"))
            .thenReturn(Optional.of(new HelmInstall(7L, "monitoring", "grafana", "bitnami/grafana")));
        when(cli.run(anyLong(), any())).thenReturn("{\"chart\":\"grafana\",\"version\":\"10.5.15\"}");
        when(cli.runAllowingEmpty(anyLong(), any()))
            .thenReturn("[{\"name\":\"bitnami/grafana\",\"version\":\"11.2.0\"}]");

        var update = service.chartUpdate(7L, "monitoring", "grafana");

        assertThat(update.latestVersion()).isEqualTo("11.2.0");
        assertThat(update.updateAvailable()).isTrue();
    }

    /**
     * A bare `helm upgrade` resets a release to the chart's defaults. On a
     * running release that is indistinguishable from wiping its configuration,
     * so the current values have to travel with the version change.
     */
    @Test
    void changingChartVersionCarriesTheCurrentValuesAcross() {
        when(installRepository.findByClusterIdAndNamespaceAndReleaseName(7L, "monitoring", "grafana"))
            .thenReturn(Optional.of(new HelmInstall(7L, "monitoring", "grafana", "bitnami/grafana")));
        when(cli.run(anyLong(), any())).thenReturn("replicas: 3\n");

        service.upgradeChartVersion("admin", 7L, "monitoring", "grafana", "11.2.0");

        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(cli, atLeastOnce()).run(anyLong(), args.capture());
        assertThat(args.getAllValues())
            .anySatisfy(a -> assertThat(a).containsSubsequence(
                "get", "values", "grafana", "-n", "monitoring", "-o", "yaml"))
            .anySatisfy(a -> assertThat(a).containsSubsequence(
                "upgrade", "grafana", "bitnami/grafana", "--version", "11.2.0").contains("-f"));
        verify(auditService).record(eq("admin"), eq(7L), eq("UPGRADE_HELM_CHART"), any(),
            eq(Map.of("version", "11.2.0")), eq(true), eq(null));
    }

    @Test
    void aVersionChangeWithNoLinkedRepositoryIsRefused() {
        when(cli.run(anyLong(), any())).thenReturn("");
        when(cli.runAllowingEmpty(anyLong(), any())).thenReturn("[]");

        assertThatThrownBy(() -> service.upgradeChartVersion("admin", 7L, "monitoring", "grafana", "11.2.0"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409");

        verify(auditService).record(eq("admin"), eq(7L), eq("UPGRADE_HELM_CHART"), any(),
            eq(Map.of("version", "11.2.0")), eq(false), any());
    }

    @Test
    void historyIsScopedToTheNamespaceItWasAskedFor() {
        when(cli.run(anyLong(), any())).thenReturn("[]");

        service.history(7L, "monitoring", "grafana");

        assertThat(capturedArgs()).containsExactly("history", "grafana", "-n", "monitoring", "-o", "json");
    }
}
