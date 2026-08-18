package com.kubemind.helm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Rebuilds a release's chart from what Helm stored in the cluster, so values can
 * be edited and re-applied without anyone registering a chart repository.
 *
 * Helm keeps the packaged chart inside the release Secret
 * ({@code sh.helm.release.v1.<name>.v<n>}), base64 of gzip of JSON. That is the
 * same data `helm rollback` replays, which is why this works for a release
 * installed from a terminal.
 *
 * <p><b>It is not always complete.</b> Verified against Helm 4.0.5: a chart's
 * subcharts are <em>not</em> persisted in the release Secret — the stored
 * manifest contains the subchart's rendered resources, but the stored chart
 * contains only the parent's templates. Upgrading against a chart rebuilt from
 * that would quietly delete every resource the subcharts own. Callers therefore
 * must not trust the output on its own: see
 * {@link #rendersTheSameManifest} for the check that makes it safe, and
 * HelmReleaseService for how a failed check falls back to the repository path.
 */
@Component
public class HelmReleaseChartExtractor {

    private static final Logger log = LoggerFactory.getLogger(HelmReleaseChartExtractor.class);

    /** Guards against decompressing a hostile or corrupt payload into memory. */
    private static final int MAX_RELEASE_BYTES = 20 * 1024 * 1024;

    private final ClusterClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    public HelmReleaseChartExtractor(ClusterClientFactory clientFactory, ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * @param chartDir  rebuilt chart root, ready to hand to `helm upgrade`
     * @param values    the values the release is currently running with
     * @param manifest  what Helm rendered last time, for {@link #rendersTheSameManifest}
     */
    public record ExtractedChart(Path chartDir, String values, String manifest) {}

    /**
     * Returns empty rather than throwing whenever the chart can't be recovered —
     * every such case has a working fallback (the repository path), so a failure
     * here is a routing decision, not an error to surface.
     */
    public Optional<ExtractedChart> extract(long clusterId, String namespace, String releaseName) {
        try {
            JsonNode release = readRelease(clusterId, namespace, releaseName);
            if (release == null) return Optional.empty();

            JsonNode chart = release.path("chart");
            if (chart.isMissingNode() || !chart.hasNonNull("metadata")) return Optional.empty();

            Path chartDir = Files.createTempDirectory("kubemind-helm-chart-");
            writeChart(chart, chartDir);

            return Optional.of(new ExtractedChart(
                chartDir,
                yaml(release.path("config")),
                release.path("manifest").asText("")));
        } catch (Exception e) {
            log.debug("Could not rebuild chart for {}/{}: {}", namespace, releaseName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The safety gate. Renders the rebuilt chart with the release's own values and
     * compares against the manifest Helm stored — proving the reconstruction is
     * complete before anything is applied to a cluster.
     *
     * This is what catches the subchart gap described in the class doc: a parent
     * chart rebuilt without its subcharts renders strictly fewer resources than
     * the stored manifest, so the comparison fails and the caller falls back
     * instead of deleting whatever the subcharts own.
     */
    public boolean rendersTheSameManifest(HelmCliService cli, long clusterId, String namespace,
                                          String releaseName, ExtractedChart extracted) {
        Path valuesFile = null;
        try {
            valuesFile = Files.createTempFile("kubemind-helm-verify-", ".yaml");
            Files.writeString(valuesFile, extracted.values());

            String rendered = cli.run(clusterId, List.of(
                "template", releaseName, extracted.chartDir().toString(),
                "-n", namespace, "-f", valuesFile.toString()));

            boolean same = normalize(rendered).equals(normalize(extracted.manifest()));
            if (!same) {
                log.info("Rebuilt chart for {}/{} does not reproduce the stored manifest "
                    + "(subcharts are not kept in the release); falling back to the chart repository",
                    namespace, releaseName);
            }
            return same;
        } catch (Exception e) {
            log.debug("Could not verify rebuilt chart for {}/{}: {}", namespace, releaseName, e.getMessage());
            return false;
        } finally {
            deleteQuietly(valuesFile);
        }
    }

    /** Trailing-whitespace and blank-line differences are formatting, not content. */
    private static String normalize(String manifest) {
        return manifest == null ? "" : manifest.strip().replaceAll("[ \t]+\n", "\n");
    }

    private JsonNode readRelease(long clusterId, String namespace, String releaseName) throws IOException {
        List<Secret> secrets = clientFactory.getClient(clusterId).secrets()
            .inNamespace(namespace)
            .withLabels(Map.of("owner", "helm", "name", releaseName, "status", "deployed"))
            .list().getItems();

        // "deployed" is normally a single Secret; if Helm left more than one behind,
        // the highest revision is the live one.
        Optional<Secret> newest = secrets.stream()
            .max(Comparator.comparingInt(s -> revisionOf(s)));
        if (newest.isEmpty()) return null;

        String encoded = newest.get().getData().get("release");
        if (encoded == null) return null;
        return decodeRelease(encoded);
    }

    /**
     * Fabric8 hands back the value still base64-encoded by Kubernetes, and Helm's
     * own payload is base64 again wrapping gzip — hence the double decode.
     * Returns null when the payload is larger than {@link #MAX_RELEASE_BYTES}.
     */
    JsonNode decodeRelease(String doubleEncoded) throws IOException {
        byte[] gzipped = Base64.getDecoder().decode(Base64.getDecoder().decode(doubleEncoded));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            byte[] json = in.readNBytes(MAX_RELEASE_BYTES);
            if (json.length >= MAX_RELEASE_BYTES) {
                log.info("Release payload exceeds {} bytes — not rebuilding its chart", MAX_RELEASE_BYTES);
                return null;
            }
            return objectMapper.readTree(json);
        }
    }

    private static int revisionOf(Secret secret) {
        try {
            return Integer.parseInt(secret.getMetadata().getLabels().getOrDefault("version", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Mirrors Helm's own chart layout: metadata → Chart.yaml, values → values.yaml,
     *  and every template/file back at the path it was packaged under. */
    void writeChart(JsonNode chart, Path root) throws IOException {
        writeFile(root, "Chart.yaml", yaml(chart.path("metadata")).getBytes(StandardCharsets.UTF_8));
        writeFile(root, "values.yaml", yaml(chart.path("values")).getBytes(StandardCharsets.UTF_8));

        for (JsonNode template : chart.path("templates")) {
            writeEntry(root, template);
        }
        for (JsonNode file : chart.path("files")) {
            writeEntry(root, file);
        }
        if (chart.hasNonNull("schema")) {
            writeFile(root, "values.schema.json", Base64.getDecoder().decode(chart.get("schema").asText()));
        }
        // Subcharts, on the Helm versions that do persist them. Kept because the
        // verification gate — not this loop — decides whether the result is usable.
        for (JsonNode dependency : chart.path("dependencies")) {
            String name = dependency.path("metadata").path("name").asText("");
            if (!name.isBlank()) {
                writeChart(dependency, root.resolve("charts").resolve(sanitize(name)));
            }
        }
    }

    private void writeEntry(Path root, JsonNode entry) throws IOException {
        String name = entry.path("name").asText("");
        String data = entry.path("data").asText("");
        if (name.isBlank()) return;
        writeFile(root, name, Base64.getDecoder().decode(data));
    }

    /** Refuses any path that would land outside the chart directory (zip-slip). */
    private void writeFile(Path root, String relative, byte[] content) throws IOException {
        Path target = root.resolve(sanitize(relative)).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IOException("Release contains a chart path escaping its root: " + relative);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private static String sanitize(String path) {
        return path.replace("\\", "/");
    }

    private String yaml(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "{}\n";
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Object plain = objectMapper.convertValue(node, Object.class);
        return new Yaml(options).dump(plain);
    }

    /** Chart directories are temporary; leaving them behind would fill the pod's disk. */
    public static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp file is not worth failing an upgrade over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
