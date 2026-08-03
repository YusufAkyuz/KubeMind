package com.kubemind.k8s.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared plumbing for WebSocket handlers that exec into a pod they provision
 * themselves for the duration of the session (node debug pod, cluster
 * terminal pod) — as opposed to {@link ExecWebSocketHandler}, which execs
 * into a pod the user already owns. Subclasses only decide how to provision
 * and how to tear down; the exec bridge (stdin/stdout/resize/cleanup) is
 * identical either way.
 */
abstract class AbstractEphemeralExecHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractEphemeralExecHandler.class);
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final ExecClusterAccessGuard accessGuard;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    protected AbstractEphemeralExecHandler(ObjectMapper objectMapper, ExecClusterAccessGuard accessGuard) {
        this.objectMapper = objectMapper;
        this.accessGuard = accessGuard;
    }

    private record Session(ExecWatch watch, Runnable cleanup) {}

    /** Everything needed to exec into a freshly-provisioned pod, plus how to tear it down afterwards. */
    protected record Provisioned(
        KubernetesClient client, String namespace, String podName,
        String containerName, String[] command, Runnable cleanup
    ) {}

    /**
     * Provisions whatever ephemeral cluster resources this session needs and
     * waits for the target pod to be running. Throwing here aborts the
     * connection before any exec is attempted; implementations are
     * responsible for their own audit logging (success and failure).
     */
    protected abstract Provisioned provision(WebSocketSession session,
                                             MultiValueMap<String, String> queryParams) throws Exception;

    @Override
    public final void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        var params = UriComponentsBuilder.fromUri(rawSession.getUri()).build().getQueryParams();

        // Before anything is provisioned: the cluster id is caller-supplied and
        // this route bypasses ClusterAccessInterceptor. See ExecClusterAccessGuard.
        long clusterId = parseLong(params.getFirst("clusterId"), 0L);
        if (!accessGuard.permits(rawSession, clusterId)) {
            close(rawSession, CloseStatus.POLICY_VIOLATION.withReason("No access to this cluster"));
            return;
        }

        // Thread-safe sends: the output pump and the exec listener both write.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 5_000, SEND_BUFFER_LIMIT);

        Provisioned p;
        try {
            p = provision(session, params);
        } catch (Exception e) {
            log.error("Ephemeral exec provisioning failed", e);
            sendText(session, "\r\n[error] " + e.getMessage() + "\r\n");
            close(session, CloseStatus.SERVER_ERROR);
            return;
        }

        try {
            ExecWatch watch = p.client().pods().inNamespace(p.namespace()).withName(p.podName())
                .inContainer(p.containerName())
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
                .exec(p.command());

            sessions.put(session.getId(), new Session(watch, p.cleanup()));
            Thread.ofVirtual().name("exec-out/" + p.podName()).start(() -> pumpOutput(session, watch));
        } catch (Exception e) {
            log.error("Ephemeral exec failed after provisioning", e);
            runCleanup(p.cleanup());
            sendText(session, "\r\n[error] " + e.getMessage() + "\r\n");
            close(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected final void handleTextMessage(WebSocketSession session, TextMessage message) {
        Session s = sessions.get(session.getId());
        if (s == null) return;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("stdin".equals(type)) {
                var in = s.watch().getInput();
                in.write(node.path("data").asText().getBytes(StandardCharsets.UTF_8));
                in.flush();
            } else if ("resize".equals(type)) {
                int cols = node.path("cols").asInt(80);
                int rows = node.path("rows").asInt(24);
                if (cols > 0 && rows > 0) s.watch().resize(cols, rows);
            }
        } catch (Exception e) {
            log.debug("exec input error: {}", e.getMessage());
        }
    }

    @Override
    public final void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Session s = sessions.remove(session.getId());
        if (s != null) {
            try {
                s.watch().close();
            } catch (Exception ignored) {}
            runCleanup(s.cleanup());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void runCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception e) {
            log.warn("Ephemeral exec session cleanup failed: {}", e.getMessage());
        }
    }

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

    protected long parseLong(String s, long fallback) {
        try {
            return s != null ? Long.parseLong(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected String randomSuffix() {
        return Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 6);
    }
}
