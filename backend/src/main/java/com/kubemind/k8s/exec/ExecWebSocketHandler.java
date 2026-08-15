package com.kubemind.k8s.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges an interactive pod/container shell to the browser over a WebSocket.
 *
 * Client → server messages are JSON:
 *   {"type":"stdin","data":"ls\n"}
 *   {"type":"resize","cols":120,"rows":40}
 * Server → client messages are raw terminal output (stdout+stderr, TTY-merged).
 *
 * Exec is effectively full write access, so the handshake is ADMIN-only
 * (enforced in SecurityConfig) and every session open is audited.
 */
@Component
public class ExecWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecWebSocketHandler.class);
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final ClusterClientFactory clientFactory;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecClusterAccessGuard accessGuard;

    private final Map<String, ExecWatch> watches = new ConcurrentHashMap<>();

    public ExecWebSocketHandler(ClusterClientFactory clientFactory,
                                AuditService auditService,
                                ObjectMapper objectMapper,
                                ExecClusterAccessGuard accessGuard) {
        this.clientFactory = clientFactory;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.accessGuard = accessGuard;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        var params = UriComponentsBuilder.fromUri(rawSession.getUri()).build().getQueryParams();
        long clusterId = parseLong(params.getFirst("clusterId"), 0L);
        String ns = params.getFirst("ns");
        String pod = params.getFirst("pod");
        String container = params.getFirst("container");

        String username = rawSession.getPrincipal() != null
            ? rawSession.getPrincipal().getName() : "unknown";
        String ref = "Pod/" + ns + "/" + pod;

        if (ns == null || pod == null) {
            close(rawSession, CloseStatus.BAD_DATA.withReason("Missing ns/pod"));
            return;
        }

        // This route bypasses ClusterAccessInterceptor and the cluster id is
        // caller-supplied — see ExecClusterAccessGuard. Checked before the pod is
        // even looked up, so a refusal reveals nothing about another cluster.
        if (!accessGuard.permits(rawSession, clusterId)) {
            auditService.record(username, clusterId, "EXEC_POD", ref, null, false, "no access to cluster");
            close(rawSession, CloseStatus.POLICY_VIOLATION.withReason("No access to this cluster"));
            return;
        }

        // Thread-safe sends: the output pump and the exec listener both write.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 5_000, SEND_BUFFER_LIMIT);

        try {
            // Principal passed explicitly: this is a WebSocket thread, so the
            // caller lives on session.getPrincipal() and SecurityContextHolder
            // is empty — getClient(clusterId) alone would fail closed here once
            // impersonation is on.
            KubernetesClient client = clientFactory.getClient(clusterId, rawSession.getPrincipal());
            var podObj = client.pods().inNamespace(ns).withName(pod).get();
            if (podObj == null) {
                auditService.record(username, clusterId, "EXEC_POD", ref, null, false, "pod not found");
                close(session, CloseStatus.NOT_ACCEPTABLE.withReason("Pod not found"));
                return;
            }
            String containerName = (container == null || container.isBlank())
                ? podObj.getSpec().getContainers().get(0).getName()
                : container;

            ExecWatch watch = client.pods().inNamespace(ns).withName(pod)
                .inContainer(containerName)
                .redirectingInput()
                .redirectingOutput()
                .withTTY()
                .usingListener(new ExecListener() {
                    @Override
                    public void onFailure(Throwable t, Response failureResponse) {
                        sendText(session, "\r\n[connection error] " + t.getMessage() + "\r\n");
                        close(session, CloseStatus.SERVER_ERROR);
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        close(session, CloseStatus.NORMAL);
                    }
                })
                .exec("/bin/sh");

            watches.put(session.getId(), watch);
            auditService.record(username, clusterId, "EXEC_POD", ref,
                Map.of("container", containerName), true, null);

            // Pump pod output → browser.
            Thread.ofVirtual().name("exec-out/" + pod).start(() -> pumpOutput(session, watch));

        } catch (Exception e) {
            auditService.record(username, clusterId, "EXEC_POD", ref, null, false, e.getMessage());
            sendText(session, "\r\n[error] " + e.getMessage() + "\r\n");
            close(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ExecWatch watch = watches.get(session.getId());
        if (watch == null) return;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("stdin".equals(type)) {
                OutputStream in = watch.getInput();
                in.write(node.path("data").asText().getBytes(StandardCharsets.UTF_8));
                in.flush();
            } else if ("resize".equals(type)) {
                int cols = node.path("cols").asInt(80);
                int rows = node.path("rows").asInt(24);
                if (cols > 0 && rows > 0) watch.resize(cols, rows);
            }
        } catch (Exception e) {
            log.debug("exec input error: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ExecWatch watch = watches.remove(session.getId());
        if (watch != null) {
            try {
                watch.close();
            } catch (Exception ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void pumpOutput(WebSocketSession session, ExecWatch watch) {
        try {
            var out = watch.getOutput();
            byte[] buf = new byte[4096];
            int n;
            while ((n = out.read(buf)) != -1) {
                if (!session.isOpen()) break;
                session.sendMessage(new TextMessage(new String(buf, 0, n, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.debug("exec output pump ended: {}", e.getMessage());
        } finally {
            close(session, CloseStatus.NORMAL);
        }
    }

    private void sendText(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) session.sendMessage(new TextMessage(text));
        } catch (Exception ignored) {}
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception ignored) {}
    }

    private long parseLong(String s, long fallback) {
        try {
            return s != null ? Long.parseLong(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
