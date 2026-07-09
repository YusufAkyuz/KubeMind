package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/clusters/{clusterId}/watch")
public class WatchController {

    private static final Logger log = LoggerFactory.getLogger(WatchController.class);

    private final ClusterClientFactory clientFactory;
    private final KubernetesService kubernetesService;

    public WatchController(ClusterClientFactory clientFactory, KubernetesService kubernetesService) {
        this.clientFactory = clientFactory;
        this.kubernetesService = kubernetesService;
    }

    @GetMapping(value = "/nodes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchNodes(@PathVariable long clusterId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            sendEvent(emitter, "init", kubernetesService.listNodes(clusterId));
            Watch watch = clientFactory.getClient(clusterId).nodes()
                .watch(new ListRefreshWatcher<>(emitter, () -> kubernetesService.listNodes(clusterId)));
            bindCleanup(emitter, watch);
        } catch (Exception e) {
            sendErrorAndComplete(emitter, e);
        }
        return emitter;
    }

    @GetMapping(value = "/namespaces/{ns}/pods", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchPods(@PathVariable long clusterId, @PathVariable String ns) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            sendEvent(emitter, "init", kubernetesService.listPods(clusterId, ns));
            var client = clientFactory.getClient(clusterId);
            Watch watch = ("all".equals(ns) ? client.pods().inAnyNamespace() : client.pods().inNamespace(ns))
                .watch(new ListRefreshWatcher<>(emitter, () -> kubernetesService.listPods(clusterId, ns)));
            bindCleanup(emitter, watch);
        } catch (Exception e) {
            sendErrorAndComplete(emitter, e);
        }
        return emitter;
    }

    @GetMapping(value = "/namespaces/{ns}/deployments", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchDeployments(@PathVariable long clusterId, @PathVariable String ns) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            sendEvent(emitter, "init", kubernetesService.listDeployments(clusterId, ns));
            var client = clientFactory.getClient(clusterId);
            Watch watch = ("all".equals(ns) ? client.apps().deployments().inAnyNamespace() : client.apps().deployments().inNamespace(ns))
                .watch(new ListRefreshWatcher<>(emitter, () -> kubernetesService.listDeployments(clusterId, ns)));
            bindCleanup(emitter, watch);
        } catch (Exception e) {
            sendErrorAndComplete(emitter, e);
        }
        return emitter;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendEvent(SseEmitter emitter, String eventName, List<?> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.complete();
        }
    }

    /**
     * ANY failure while setting up the stream (cluster unreachable, cluster deleted
     * mid-request, whatever) must never escape as a thrown exception here: the
     * response already negotiated text/event-stream, so Spring's normal
     * @ExceptionHandler JSON body (ApiExceptionHandler) can't be written for a
     * request whose Accept header is strictly "text/event-stream" — Spring drops it
     * to a bare, bodyless status code instead of throwing, so the failure is
     * completely invisible to the caller (confirmed: curl -H "Accept: text/event-
     * stream" against a nonexistent cluster returns 404 with zero bytes of body).
     * Send an in-band event instead and complete the stream — same idea as
     * AiStreaming.writeFallbackSafely for the AI streaming endpoints. Catching
     * Exception broadly (not just KubernetesClientException) matters here: cluster-
     * not-found throws ResponseStatusException from a completely different layer
     * (ClusterClientFactory), and it hit this exact same silent-failure bug.
     *
     * Named "stream-error", not "error": EventSource treats "error" as a reserved
     * type shared with connection-level failures, and browsers are inconsistent
     * about delivering a server-sent frame explicitly named "error" as a normal,
     * listenable MessageEvent — a custom name sidesteps that entirely.
     */
    private void sendErrorAndComplete(SseEmitter emitter, Exception e) {
        String message = e instanceof ResponseStatusException rse && rse.getReason() != null
            ? rse.getReason()
            : "Could not reach the Kubernetes cluster: " + e.getMessage();
        log.warn("Watch stream failed to start: {}", message);
        try {
            emitter.send(SseEmitter.event().name("stream-error")
                .data(Map.of("error", message), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // client already gone
        }
        emitter.complete();
    }

    private void bindCleanup(SseEmitter emitter, Watch watch) {
        emitter.onCompletion(watch::close);
        emitter.onTimeout(watch::close);
        emitter.onError(ex -> watch.close());
    }

    /**
     * Generic watcher that re-fetches the full list on any change and pushes it to the emitter.
     * Full-list push keeps the client logic simple: just replace the cache entry.
     */
    private class ListRefreshWatcher<T> implements Watcher<T> {

        private final SseEmitter emitter;
        private final Supplier<List<?>> listSupplier;

        ListRefreshWatcher(SseEmitter emitter, Supplier<List<?>> listSupplier) {
            this.emitter = emitter;
            this.listSupplier = listSupplier;
        }

        @Override
        public void eventReceived(Action action, T resource) {
            sendEvent(emitter, "update", listSupplier.get());
        }

        @Override
        public void onClose(WatcherException cause) {
            try {
                if (cause != null) emitter.completeWithError(cause);
                else emitter.complete();
            } catch (Exception ignored) {}
        }
    }
}
