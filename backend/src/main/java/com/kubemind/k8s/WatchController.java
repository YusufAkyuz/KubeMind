package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/k8s/watch")
public class WatchController {

    private final KubernetesClient client;
    private final KubernetesService kubernetesService;

    public WatchController(KubernetesClient client, KubernetesService kubernetesService) {
        this.client = client;
        this.kubernetesService = kubernetesService;
    }

    @GetMapping(value = "/nodes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchNodes() {
        SseEmitter emitter = new SseEmitter(0L);
        sendEvent(emitter, "init", kubernetesService.listNodes());
        Watch watch = client.nodes().watch(new ListRefreshWatcher<>(emitter,
            () -> kubernetesService.listNodes()));
        bindCleanup(emitter, watch);
        return emitter;
    }

    @GetMapping(value = "/namespaces/{ns}/pods", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchPods(@PathVariable String ns) {
        SseEmitter emitter = new SseEmitter(0L);
        sendEvent(emitter, "init", kubernetesService.listPods(ns));
        Watch watch = client.pods().inNamespace(ns).watch(new ListRefreshWatcher<>(emitter,
            () -> kubernetesService.listPods(ns)));
        bindCleanup(emitter, watch);
        return emitter;
    }

    @GetMapping(value = "/namespaces/{ns}/deployments", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchDeployments(@PathVariable String ns) {
        SseEmitter emitter = new SseEmitter(0L);
        sendEvent(emitter, "init", kubernetesService.listDeployments(ns));
        Watch watch = client.apps().deployments().inNamespace(ns).watch(new ListRefreshWatcher<>(emitter,
            () -> kubernetesService.listDeployments(ns)));
        bindCleanup(emitter, watch);
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
