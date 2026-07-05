package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Secret value access. Reading decoded Secret values is a privileged, audited
 * operation (CLAUDE.md: reveal-on-demand, permission-gated, audited). Revealed
 * values must never be logged and never fed to the AI layer.
 */
@Service
public class SecretService {

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;

    public SecretService(ClusterClientFactory clientFactory, AuditService auditService) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    public Map<String, String> reveal(String username, long clusterId, String ns, String name) {
        String ref = "Secret/" + ns + "/" + name;
        try {
            var secret = clientFactory.getClient(clusterId).secrets()
                .inNamespace(ns).withName(name).get();
            if (secret == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ref + " not found");
            }

            Map<String, String> decoded = new LinkedHashMap<>();
            Map<String, String> data = secret.getData() != null
                ? new TreeMap<>(secret.getData()) : Map.of();
            for (var entry : data.entrySet()) {
                decoded.put(entry.getKey(), decode(entry.getValue()));
            }

            auditService.record(username, clusterId, "REVEAL_SECRET", ref,
                Map.of("keys", decoded.size()), true, null);
            return decoded;
        } catch (Exception e) {
            auditService.record(username, clusterId, "REVEAL_SECRET", ref, null, false, e.getMessage());
            throw e;
        }
    }

    private String decode(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            String text = new String(raw, StandardCharsets.UTF_8);
            // Binary payloads (certs, keystores) are not printable — say so instead
            // of dumping mojibake into the UI.
            return text.chars().anyMatch(c -> c == 0xFFFD || (c < 0x20 && c != '\n' && c != '\r' && c != '\t'))
                ? "(binary, " + raw.length + " bytes)"
                : text;
        } catch (IllegalArgumentException e) {
            return "(undecodable)";
        }
    }
}
