package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KubernetesService {

    private final KubernetesClient client;

    public KubernetesService(KubernetesClient client) {
        this.client = client;
    }

    // ── Namespaces ────────────────────────────────────────────────────────────

    public List<NamespaceDto> listNamespaces() {
        return client.namespaces().list().getItems().stream()
            .map(ns -> new NamespaceDto(
                ns.getMetadata().getName(),
                ns.getStatus() != null ? ns.getStatus().getPhase() : null,
                ns.getMetadata().getCreationTimestamp()))
            .toList();
    }

    // ── Nodes ─────────────────────────────────────────────────────────────────

    public List<NodeDto> listNodes() {
        return client.nodes().list().getItems().stream()
            .map(this::toNodeDto)
            .toList();
    }

    private NodeDto toNodeDto(Node node) {
        var meta = node.getMetadata();
        var status = node.getStatus();
        var info = status != null ? status.getNodeInfo() : null;
        Map<String, Quantity> capacity = status != null && status.getCapacity() != null
            ? status.getCapacity() : Map.of();
        Map<String, Quantity> allocatable = status != null && status.getAllocatable() != null
            ? status.getAllocatable() : Map.of();

        return new NodeDto(
            meta.getName(),
            nodeReadyStatus(node),
            nodeRoles(meta.getLabels()),
            info != null ? info.getKubeletVersion() : null,
            info != null ? info.getOsImage() : null,
            quantityStr(capacity.get("cpu")),
            quantityStr(capacity.get("memory")),
            quantityStr(allocatable.get("cpu")),
            quantityStr(allocatable.get("memory")),
            meta.getCreationTimestamp()
        );
    }

    private String nodeReadyStatus(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) return "Unknown";
        return node.getStatus().getConditions().stream()
            .filter(c -> "Ready".equals(c.getType()))
            .findFirst()
            .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
            .orElse("Unknown");
    }

    private String nodeRoles(Map<String, String> labels) {
        if (labels == null) return "worker";
        String roles = labels.keySet().stream()
            .filter(k -> k.startsWith("node-role.kubernetes.io/"))
            .map(k -> k.replace("node-role.kubernetes.io/", ""))
            .sorted()
            .collect(Collectors.joining(","));
        return roles.isEmpty() ? "worker" : roles;
    }

    // ── Pods ──────────────────────────────────────────────────────────────────

    public List<PodDto> listPods(String namespace) {
        var items = "all".equals(namespace)
            ? client.pods().inAnyNamespace().list().getItems()
            : client.pods().inNamespace(namespace).list().getItems();
        return items.stream().map(this::toPodDto).toList();
    }

    private PodDto toPodDto(Pod pod) {
        var meta = pod.getMetadata();
        var spec = pod.getSpec();
        var status = pod.getStatus();

        String phase = meta.getDeletionTimestamp() != null ? "Terminating"
            : (status != null && status.getPhase() != null ? status.getPhase() : "Unknown");

        Map<String, ContainerStatus> csMap = status != null && status.getContainerStatuses() != null
            ? status.getContainerStatuses().stream()
                .collect(Collectors.toMap(ContainerStatus::getName, cs -> cs, (a, b) -> a))
            : Map.of();

        int totalRestarts = csMap.values().stream()
            .mapToInt(cs -> cs.getRestartCount() != null ? cs.getRestartCount() : 0)
            .sum();

        List<PodDto.ContainerInfo> containers = spec != null && spec.getContainers() != null
            ? spec.getContainers().stream().map(c -> {
                var cs = csMap.get(c.getName());
                return new PodDto.ContainerInfo(
                    c.getName(),
                    c.getImage(),
                    cs != null && Boolean.TRUE.equals(cs.getReady()),
                    cs != null && cs.getRestartCount() != null ? cs.getRestartCount() : 0
                );
              }).toList()
            : List.of();

        return new PodDto(
            meta.getName(),
            meta.getNamespace(),
            phase,
            spec != null ? spec.getNodeName() : null,
            totalRestarts,
            status != null ? status.getPodIP() : null,
            meta.getCreationTimestamp(),
            containers
        );
    }

    // ── Deployments ───────────────────────────────────────────────────────────

    public List<DeploymentDto> listDeployments(String namespace) {
        var items = "all".equals(namespace)
            ? client.apps().deployments().inAnyNamespace().list().getItems()
            : client.apps().deployments().inNamespace(namespace).list().getItems();
        return items.stream().map(this::toDeploymentDto).toList();
    }

    private DeploymentDto toDeploymentDto(Deployment d) {
        var meta = d.getMetadata();
        var spec = d.getSpec();
        var status = d.getStatus();

        String image = spec != null
            && spec.getTemplate() != null
            && spec.getTemplate().getSpec() != null
            && !spec.getTemplate().getSpec().getContainers().isEmpty()
            ? spec.getTemplate().getSpec().getContainers().get(0).getImage()
            : null;

        String strategy = spec != null && spec.getStrategy() != null
            && spec.getStrategy().getType() != null
            ? spec.getStrategy().getType()
            : "RollingUpdate";

        return new DeploymentDto(
            meta.getName(),
            meta.getNamespace(),
            spec != null && spec.getReplicas() != null ? spec.getReplicas() : 0,
            status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0,
            status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0,
            strategy,
            image,
            meta.getCreationTimestamp()
        );
    }

    // ── Events ────────────────────────────────────────────────────────────────

    public List<EventDto> listEvents(String namespace) {
        var items = "all".equals(namespace)
            ? client.resources(Event.class).inAnyNamespace().list().getItems()
            : client.resources(Event.class).inNamespace(namespace).list().getItems();
        return items.stream()
            .sorted(Comparator.comparing(
                e -> e.getLastTimestamp() != null ? e.getLastTimestamp() : "",
                Comparator.reverseOrder()))
            .map(this::toEventDto)
            .toList();
    }

    private EventDto toEventDto(Event e) {
        var meta = e.getMetadata();
        var obj = e.getInvolvedObject();
        return new EventDto(
            meta.getName(),
            meta.getNamespace(),
            e.getType() != null ? e.getType() : "Normal",
            e.getReason(),
            e.getMessage(),
            obj != null ? obj.getKind() : null,
            obj != null ? obj.getName() : null,
            e.getCount() != null ? e.getCount() : 1,
            e.getLastTimestamp(),
            e.getFirstTimestamp()
        );
    }

    // ── Single resource ───────────────────────────────────────────────────────

    public PodDto getPod(String namespace, String name) {
        var pod = client.pods().inNamespace(namespace).withName(name).get();
        if (pod == null) return null;
        return toPodDto(pod);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String quantityStr(Quantity q) {
        return q != null ? q.toString() : null;
    }
}
