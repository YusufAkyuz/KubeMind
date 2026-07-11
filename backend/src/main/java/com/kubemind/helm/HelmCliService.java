package com.kubemind.helm;

import com.kubemind.cluster.ClusterClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the `helm` CLI as a subprocess, pointed at the right cluster.
 *
 * There's no mature Helm SDK for Java, and reimplementing chart rendering,
 * hooks, and atomic install/rollback ourselves would be a project on its own —
 * shelling out gets the real thing for free. This is the same trust tier as
 * the Cluster Terminal (CLAUDE.md's disclosed exceptions): every call here is
 * ADMIN-gated and audited by its caller, and the `helm` binary must be present
 * in the backend's runtime image (documented in the Helm chart / Dockerfile
 * when KubeMind ships one).
 *
 * For the built-in local cluster (id 0), no --kubeconfig is passed — the
 * subprocess inherits the same ambient config (in-cluster ServiceAccount or
 * ~/.kube/config) the backend's own default KubernetesClient already uses.
 * For registered remote clusters, the stored kubeconfig is decrypted and
 * written to a short-lived temp file (0600, deleted in a finally block) and
 * passed via --kubeconfig so nothing sensitive touches disk longer than one
 * command's lifetime.
 */
@Service
public class HelmCliService {

    private static final Logger log = LoggerFactory.getLogger(HelmCliService.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final ClusterClientFactory clientFactory;

    public HelmCliService(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** Runs `helm <args>` against the given cluster and returns stdout. Throws on nonzero exit. */
    public String run(long clusterId, List<String> args) {
        Path tempKubeconfig = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("helm");
            command.addAll(args);

            String kubeconfig = clientFactory.getKubeconfig(clusterId);
            if (kubeconfig != null) {
                tempKubeconfig = Files.createTempFile("kubemind-helm-", ".yaml",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(tempKubeconfig, kubeconfig);
                command.add("--kubeconfig");
                command.add(tempKubeconfig.toString());
            }

            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
            Process process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "helm command timed out");
            }
            if (process.exitValue() != 0) {
                String message = !stderr.isBlank() ? stderr.trim() : stdout.trim();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "helm: " + message);
            }
            return stdout;
        } catch (IOException e) {
            log.error("Failed to run helm — is it installed on the backend host/image? {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "helm CLI is not available on the server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "helm command interrupted");
        } finally {
            if (tempKubeconfig != null) {
                try {
                    Files.deleteIfExists(tempKubeconfig);
                } catch (IOException e) {
                    log.warn("Failed to delete temp kubeconfig {}: {}", tempKubeconfig, e.getMessage());
                }
            }
        }
    }

    /** Like {@link #run} but returns an empty string instead of throwing when helm's stderr
     *  indicates "nothing found" (e.g. no repos added yet, no releases) — several helm
     *  subcommands treat an empty result as a CLI error rather than an empty success. */
    public String runAllowingEmpty(long clusterId, List<String> args) {
        try {
            return run(clusterId, args);
        } catch (ResponseStatusException e) {
            String reason = e.getReason() != null ? e.getReason().toLowerCase() : "";
            if (reason.contains("no repositories") || reason.contains("no releases") || reason.contains("not found")) {
                return "";
            }
            throw e;
        }
    }
}
