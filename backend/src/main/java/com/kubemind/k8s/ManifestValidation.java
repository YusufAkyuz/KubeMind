package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Shared manifest parsing/validation for the generic resource endpoints
 * (create, edit). Kept in one place so the same rules — kind allowlist,
 * required name, namespace must match — apply everywhere a user submits
 * raw YAML.
 */
final class ManifestValidation {

    /** Well-known apiVersion per kind, used to build minimal "lookup" manifests. */
    static final Map<String, String> KIND_API_VERSIONS = Map.ofEntries(
        Map.entry("Pod", "v1"),
        Map.entry("Service", "v1"),
        Map.entry("ConfigMap", "v1"),
        Map.entry("Secret", "v1"),
        Map.entry("PersistentVolumeClaim", "v1"),
        Map.entry("Deployment", "apps/v1"),
        Map.entry("StatefulSet", "apps/v1"),
        Map.entry("DaemonSet", "apps/v1"),
        Map.entry("Ingress", "networking.k8s.io/v1")
    );

    private ManifestValidation() {}

    record Identity(String kind, String name) {}

    /** Full validation: enforces the kind allowlist, a required name, and a matching namespace. */
    static Identity parseAndValidate(String yaml, String ns, Set<String> allowedKinds) {
        if (yaml == null || yaml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manifest is empty");
        }

        GenericKubernetesResource parsed;
        try {
            parsed = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML: " + rootMessage(e));
        }

        String kind = parsed.getKind();
        if (kind == null || !allowedKinds.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Kind '" + kind + "' is not supported here. Supported kinds: "
                + String.join(", ", allowedKinds.stream().sorted().toList()));
        }

        if (parsed.getMetadata() == null || parsed.getMetadata().getName() == null
            || parsed.getMetadata().getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metadata.name is required");
        }

        String parsedNs = parsed.getMetadata().getNamespace();
        if (parsedNs != null && !parsedNs.isBlank() && !parsedNs.equals(ns)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "metadata.namespace ('" + parsedNs + "') must match the target namespace '" + ns
                + "', or be omitted");
        }

        return new Identity(kind, parsed.getMetadata().getName());
    }

    /** Loose extraction for audit labeling only — never throws, never enforces rules. */
    static Identity extractBestEffort(String yaml) {
        try {
            var parsed = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
            String kind = parsed.getKind() != null ? parsed.getKind() : "unknown";
            String name = parsed.getMetadata() != null && parsed.getMetadata().getName() != null
                ? parsed.getMetadata().getName() : "unknown";
            return new Identity(kind, name);
        } catch (Exception e) {
            return new Identity("unknown", "unknown");
        }
    }

    /** Builds a minimal manifest (apiVersion+kind+metadata only) used to look up a live object generically. */
    static String buildLookupManifest(String kind, String ns, String name) {
        String apiVersion = KIND_API_VERSIONS.get(kind);
        if (apiVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kind '" + kind + "' is not supported here");
        }
        return "apiVersion: " + apiVersion + "\n"
            + "kind: " + kind + "\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "  namespace: " + ns + "\n";
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
