package com.kubemind.helm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.cluster.ClusterClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The shapes here are taken from a real release Secret read out of a running
 * cluster (`kubectl get secret sh.helm.release.v1.<name>.v<n> -o jsonpath=...
 * | base64 -d | base64 -d | gunzip`), not from documentation.
 */
class HelmReleaseChartExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HelmReleaseChartExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new HelmReleaseChartExtractor(mock(ClusterClientFactory.class), mapper);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Mirrors how Kubernetes stores what Helm wrote: base64(base64(gzip(json))). */
    private static String storedAsSecretValue(String json) throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(
            Base64.getEncoder().encode(gz.toByteArray()));
    }

    private String releaseJson() {
        return """
            {
              "name": "grafana",
              "namespace": "monitoring",
              "manifest": "---\\nkind: ConfigMap\\n",
              "config": {"replicas": 2},
              "chart": {
                "metadata": {"name": "grafana", "version": "10.5.15", "apiVersion": "v2"},
                "values": {"replicas": 1},
                "templates": [
                  {"name": "templates/cm.yaml", "data": "%s"},
                  {"name": "templates/_helpers.tpl", "data": "%s"}
                ],
                "files": [{"name": "README.md", "data": "%s"}]
              }
            }""".formatted(
                b64("kind: ConfigMap\n"), b64("{{- define \"x\" -}}\n"), b64("# readme\n"));
    }

    @Test
    void decodesTheDoubleBase64GzipPayloadHelmStores() throws Exception {
        JsonNode release = extractor.decodeRelease(storedAsSecretValue(releaseJson()));

        assertThat(release.path("name").asText()).isEqualTo("grafana");
        assertThat(release.path("chart").path("metadata").path("version").asText()).isEqualTo("10.5.15");
    }

    @Test
    void rebuildsTheChartDirectoryHelmPackagedItFrom(@TempDir Path dir) throws Exception {
        JsonNode chart = extractor.decodeRelease(storedAsSecretValue(releaseJson())).path("chart");

        extractor.writeChart(chart, dir);

        assertThat(dir.resolve("Chart.yaml")).exists();
        assertThat(Files.readString(dir.resolve("Chart.yaml"))).contains("name: grafana", "version: 10.5.15");
        // The chart's own defaults, not the release's overrides — helm layers them.
        assertThat(Files.readString(dir.resolve("values.yaml"))).contains("replicas: 1");
        assertThat(Files.readString(dir.resolve("templates/cm.yaml"))).isEqualTo("kind: ConfigMap\n");
        assertThat(dir.resolve("templates/_helpers.tpl")).exists();
        assertThat(dir.resolve("README.md")).exists();
    }

    /**
     * Subcharts, on the Helm versions that persist them. Helm 4.0.5 does not —
     * which is precisely why HelmReleaseService never applies a rebuilt chart
     * without first checking it reproduces the stored manifest.
     */
    @Test
    void rebuildsSubchartsUnderChartsWhenTheReleaseCarriesThem(@TempDir Path dir) throws Exception {
        String json = """
            {"chart": {
               "metadata": {"name": "parent", "version": "0.1.0"},
               "values": {},
               "templates": [{"name": "templates/cm.yaml", "data": "%s"}],
               "dependencies": [
                 {"metadata": {"name": "child", "version": "0.1.0"},
                  "values": {},
                  "templates": [{"name": "templates/child.yaml", "data": "%s"}]}
               ]
            }}""".formatted(b64("kind: ConfigMap\n"), b64("kind: Service\n"));

        extractor.writeChart(mapper.readTree(json).path("chart"), dir);

        assertThat(Files.readString(dir.resolve("charts/child/templates/child.yaml"))).isEqualTo("kind: Service\n");
        assertThat(dir.resolve("charts/child/Chart.yaml")).exists();
    }

    /** A release is attacker-influenced data once anyone can install into the cluster. */
    @Test
    void refusesATemplatePathThatEscapesTheChartDirectory(@TempDir Path dir) throws Exception {
        String json = """
            {"chart": {
               "metadata": {"name": "evil", "version": "0.1.0"},
               "values": {},
               "templates": [{"name": "../../../../etc/pwned", "data": "%s"}]
            }}""".formatted(b64("owned"));

        assertThatThrownBy(() -> extractor.writeChart(mapper.readTree(json).path("chart"), dir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("escaping its root");
    }

    @Test
    void aCorruptPayloadIsReportedRatherThanCrashing() {
        assertThatThrownBy(() -> extractor.decodeRelease(
            Base64.getEncoder().encodeToString(Base64.getEncoder().encode("not gzip".getBytes()))))
            .isInstanceOf(IOException.class);
    }

    @Test
    void aChartWithNoTemplatesStillProducesAValidRoot(@TempDir Path dir) throws Exception {
        String json = """
            {"chart": {"metadata": {"name": "empty", "version": "0.1.0"}}}""";

        extractor.writeChart(mapper.readTree(json).path("chart"), dir);

        assertThat(Files.readString(dir.resolve("values.yaml"))).isEqualTo("{}\n");
    }
}
