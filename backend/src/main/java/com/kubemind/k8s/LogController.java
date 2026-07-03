package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class LogController {

    private final ClusterClientFactory clientFactory;

    public LogController(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Streams pod logs line-by-line as SSE events.
     *
     * Events:
     *   name=log   data=<line text>
     *   name=error data=<message>   (then connection closes)
     *
     * Query params:
     *   container  - container name (defaults to first container)
     *   tailLines  - number of historical lines to send before following (default 200)
     */
    @GetMapping(value = "/namespaces/{ns}/pods/{pod}/logs/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
        @PathVariable long clusterId,
        @PathVariable String ns,
        @PathVariable String pod,
        @RequestParam(required = false, defaultValue = "") String container,
        @RequestParam(defaultValue = "200") int tailLines
    ) {
        var client = clientFactory.getClient(clusterId);
        var podObj = client.pods().inNamespace(ns).withName(pod).get();
        if (podObj == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Pod '" + pod + "' not found in namespace '" + ns + "'");
        }
        if (podObj.getSpec() == null || podObj.getSpec().getContainers().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pod has no containers");
        }

        String containerName = container.isBlank()
            ? podObj.getSpec().getContainers().get(0).getName()
            : container;

        boolean containerExists = podObj.getSpec().getContainers().stream()
            .anyMatch(c -> containerName.equals(c.getName()));
        if (!containerExists) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Container '" + containerName + "' not found in pod '" + pod + "'");
        }

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ex -> cancelled.set(true));

        Thread.ofVirtual().name("log-stream/" + ns + "/" + pod + "/" + containerName).start(() -> {
            try (var logWatch = client.pods().inNamespace(ns).withName(pod)
                    .inContainer(containerName)
                    .tailingLines(tailLines)
                    .watchLog()) {

                var reader = new BufferedReader(new InputStreamReader(logWatch.getOutput()));
                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    try {
                        emitter.send(SseEmitter.event().name("log").data(line));
                    } catch (IOException e) {
                        break; // client disconnected
                    }
                }
                if (!cancelled.get()) emitter.complete();

            } catch (Exception e) {
                if (!cancelled.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                        emitter.complete();
                    } catch (IOException ignored) {}
                }
            }
        });

        return emitter;
    }
}
