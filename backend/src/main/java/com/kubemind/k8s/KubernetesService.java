package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KubernetesService {

    private final ClusterClientFactory clientFactory;

    public KubernetesService(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    // ── Namespaces ────────────────────────────────────────────────────────────

    public List<NamespaceDto> listNamespaces(long clusterId) {
        return clientFactory.getClient(clusterId).namespaces().list().getItems().stream()
            .map(ns -> new NamespaceDto(
                ns.getMetadata().getName(),
                ns.getStatus() != null ? ns.getStatus().getPhase() : null,
                ns.getMetadata().getCreationTimestamp()))
            .toList();
    }

    // ── Nodes ─────────────────────────────────────────────────────────────────

    public List<NodeDto> listNodes(long clusterId) {
        return clientFactory.getClient(clusterId).nodes().list().getItems().stream()
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

    public List<PodDto> listPods(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.pods().inAnyNamespace().list().getItems()
            : client.pods().inNamespace(namespace).list().getItems();
        return items.stream().map(this::toPodDto).toList();
    }

    public PodDto getPod(long clusterId, String namespace, String name) {
        var pod = clientFactory.getClient(clusterId).pods()
            .inNamespace(namespace).withName(name).get();
        if (pod == null) return null;
        return toPodDto(pod);
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

    public List<DeploymentDto> listDeployments(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
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

    public List<EventDto> listEvents(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
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

    // ── Config ────────────────────────────────────────────────────────────────

    public List<ConfigMapDto> listConfigMaps(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).configMaps().inNamespace(namespace)
            .list().getItems().stream()
            .map(cm -> new ConfigMapDto(
                cm.getMetadata().getName(),
                cm.getMetadata().getNamespace(),
                cm.getData() != null ? cm.getData() : Map.of(),
                cm.getBinaryData() != null ? cm.getBinaryData().size() : 0,
                cm.getMetadata().getCreationTimestamp()))
            .toList();
    }

    public List<SecretDto> listSecrets(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).secrets().inNamespace(namespace)
            .list().getItems().stream()
            .map(s -> new SecretDto(
                s.getMetadata().getName(),
                s.getMetadata().getNamespace(),
                s.getType(),
                s.getData() != null ? s.getData().keySet().stream().sorted().toList() : List.of(),
                s.getMetadata().getCreationTimestamp()))
            .toList();
    }

    // ── Workloads (beyond deployments) ────────────────────────────────────────

    public List<StatefulSetDto> listStatefulSets(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).apps().statefulSets().inNamespace(namespace)
            .list().getItems().stream()
            .map(s -> new StatefulSetDto(
                s.getMetadata().getName(),
                s.getMetadata().getNamespace(),
                s.getSpec() != null && s.getSpec().getReplicas() != null ? s.getSpec().getReplicas() : 0,
                s.getStatus() != null && s.getStatus().getReadyReplicas() != null ? s.getStatus().getReadyReplicas() : 0,
                s.getSpec() != null ? s.getSpec().getServiceName() : null,
                firstImage(s.getSpec() != null && s.getSpec().getTemplate() != null
                    && s.getSpec().getTemplate().getSpec() != null
                    ? s.getSpec().getTemplate().getSpec().getContainers() : null),
                s.getMetadata().getCreationTimestamp()))
            .toList();
    }

    public List<DaemonSetDto> listDaemonSets(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).apps().daemonSets().inNamespace(namespace)
            .list().getItems().stream()
            .map(d -> new DaemonSetDto(
                d.getMetadata().getName(),
                d.getMetadata().getNamespace(),
                d.getStatus() != null && d.getStatus().getDesiredNumberScheduled() != null
                    ? d.getStatus().getDesiredNumberScheduled() : 0,
                d.getStatus() != null && d.getStatus().getNumberReady() != null
                    ? d.getStatus().getNumberReady() : 0,
                d.getStatus() != null && d.getStatus().getNumberAvailable() != null
                    ? d.getStatus().getNumberAvailable() : 0,
                firstImage(d.getSpec() != null && d.getSpec().getTemplate() != null
                    && d.getSpec().getTemplate().getSpec() != null
                    ? d.getSpec().getTemplate().getSpec().getContainers() : null),
                d.getMetadata().getCreationTimestamp()))
            .toList();
    }

    // ── Network ───────────────────────────────────────────────────────────────

    public List<ServiceDto> listServices(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).services().inNamespace(namespace)
            .list().getItems().stream()
            .map(s -> {
                var spec = s.getSpec();
                List<String> ports = spec != null && spec.getPorts() != null
                    ? spec.getPorts().stream().map(p -> {
                        StringBuilder sb = new StringBuilder();
                        if (p.getName() != null) sb.append(p.getName()).append(' ');
                        sb.append(p.getPort());
                        if (p.getTargetPort() != null) sb.append('→').append(p.getTargetPort().toString());
                        if (p.getNodePort() != null) sb.append(" (:").append(p.getNodePort()).append(')');
                        sb.append('/').append(p.getProtocol() != null ? p.getProtocol() : "TCP");
                        return sb.toString();
                      }).toList()
                    : List.of();
                return new ServiceDto(
                    s.getMetadata().getName(),
                    s.getMetadata().getNamespace(),
                    spec != null && spec.getType() != null ? spec.getType() : "ClusterIP",
                    spec != null ? spec.getClusterIP() : null,
                    ports,
                    spec != null && spec.getSelector() != null ? spec.getSelector() : Map.of(),
                    s.getMetadata().getCreationTimestamp());
            })
            .toList();
    }

    public List<IngressDto> listIngresses(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).network().v1().ingresses().inNamespace(namespace)
            .list().getItems().stream()
            .map(ing -> {
                List<IngressDto.Rule> rules = ing.getSpec() != null && ing.getSpec().getRules() != null
                    ? ing.getSpec().getRules().stream().flatMap(r -> {
                        String host = r.getHost() != null ? r.getHost() : "*";
                        if (r.getHttp() == null || r.getHttp().getPaths() == null) {
                            return java.util.stream.Stream.of(new IngressDto.Rule(host, "/", "—"));
                        }
                        return r.getHttp().getPaths().stream().map(p -> {
                            String backend = "—";
                            if (p.getBackend() != null && p.getBackend().getService() != null) {
                                var svc = p.getBackend().getService();
                                backend = svc.getName() + (svc.getPort() != null && svc.getPort().getNumber() != null
                                    ? ":" + svc.getPort().getNumber() : "");
                            }
                            return new IngressDto.Rule(host, p.getPath() != null ? p.getPath() : "/", backend);
                        });
                      }).toList()
                    : List.of();
                return new IngressDto(
                    ing.getMetadata().getName(),
                    ing.getMetadata().getNamespace(),
                    ing.getSpec() != null ? ing.getSpec().getIngressClassName() : null,
                    rules,
                    ing.getMetadata().getCreationTimestamp());
            })
            .toList();
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    public List<PvcDto> listPersistentVolumeClaims(long clusterId, String namespace) {
        return clientFactory.getClient(clusterId).persistentVolumeClaims().inNamespace(namespace)
            .list().getItems().stream()
            .map(pvc -> new PvcDto(
                pvc.getMetadata().getName(),
                pvc.getMetadata().getNamespace(),
                pvc.getStatus() != null ? pvc.getStatus().getPhase() : "Unknown",
                pvc.getSpec() != null ? pvc.getSpec().getVolumeName() : null,
                pvc.getStatus() != null && pvc.getStatus().getCapacity() != null
                    ? quantityStr(pvc.getStatus().getCapacity().get("storage")) : null,
                pvc.getSpec() != null && pvc.getSpec().getAccessModes() != null
                    ? pvc.getSpec().getAccessModes() : List.of(),
                pvc.getSpec() != null ? pvc.getSpec().getStorageClassName() : null,
                pvc.getMetadata().getCreationTimestamp()))
            .toList();
    }

    public List<PvDto> listPersistentVolumes(long clusterId) {
        return clientFactory.getClient(clusterId).persistentVolumes()
            .list().getItems().stream()
            .map(pv -> new PvDto(
                pv.getMetadata().getName(),
                pv.getStatus() != null ? pv.getStatus().getPhase() : "Unknown",
                pv.getSpec() != null && pv.getSpec().getCapacity() != null
                    ? quantityStr(pv.getSpec().getCapacity().get("storage")) : null,
                pv.getSpec() != null && pv.getSpec().getAccessModes() != null
                    ? pv.getSpec().getAccessModes() : List.of(),
                pv.getSpec() != null ? pv.getSpec().getPersistentVolumeReclaimPolicy() : null,
                pv.getSpec() != null ? pv.getSpec().getStorageClassName() : null,
                pv.getSpec() != null && pv.getSpec().getClaimRef() != null
                    ? pv.getSpec().getClaimRef().getNamespace() + "/" + pv.getSpec().getClaimRef().getName()
                    : null,
                pv.getMetadata().getCreationTimestamp()))
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String firstImage(List<io.fabric8.kubernetes.api.model.Container> containers) {
        return containers != null && !containers.isEmpty() ? containers.get(0).getImage() : null;
    }

    private String quantityStr(Quantity q) {
        return q != null ? q.toString() : null;
    }
}
