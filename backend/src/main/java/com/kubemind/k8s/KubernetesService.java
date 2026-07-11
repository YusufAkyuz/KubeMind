package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        Map<String, ContainerStatus> csMap = status != null && status.getContainerStatuses() != null
            ? status.getContainerStatuses().stream()
                .collect(Collectors.toMap(ContainerStatus::getName, cs -> cs, (a, b) -> a))
            : Map.of();

        String phase = meta.getDeletionTimestamp() != null ? "Terminating"
            : (status != null && status.getPhase() != null && !"Unknown".equalsIgnoreCase(status.getPhase())
                ? status.getPhase()
                : (csMap.values().stream().anyMatch(cs -> cs.getState() != null && cs.getState().getRunning() != null)
                    ? "Running" : "Pending"));

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
                    cs != null && cs.getRestartCount() != null ? cs.getRestartCount() : 0,
                    extractContainerTerminatedReason(cs)
                );
              }).toList()
            : List.of();

        String podTerminatedReason = containers.stream()
            .map(PodDto.ContainerInfo::lastTerminatedReason)
            .filter(Objects::nonNull)
            .filter("OOMKilled"::equals)
            .findFirst()
            .orElseGet(() -> containers.stream()
                .map(PodDto.ContainerInfo::lastTerminatedReason)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));

        return new PodDto(
            meta.getName(),
            meta.getNamespace(),
            phase,
            spec != null ? spec.getNodeName() : null,
            totalRestarts,
            status != null ? status.getPodIP() : null,
            meta.getCreationTimestamp(),
            podTerminatedReason,
            containers
        );
    }

    private String extractContainerTerminatedReason(ContainerStatus cs) {
        if (cs == null) return null;
        var state = cs.getState();
        var last = cs.getLastState();

        if (state != null && state.getTerminated() != null && state.getTerminated().getReason() != null) {
            return state.getTerminated().getReason();
        }

        String lastReason = (last != null && last.getTerminated() != null) ? last.getTerminated().getReason() : null;

        if (state != null && state.getWaiting() != null && state.getWaiting().getReason() != null) {
            String waitReason = state.getWaiting().getReason();
            if ("OOMKilled".equals(lastReason)) {
                return "OOMKilled";
            }
            if (lastReason != null && !"Completed".equals(lastReason)) {
                return waitReason + " (" + lastReason + ")";
            }
            return waitReason;
        }

        if ("OOMKilled".equals(lastReason)) {
            return "OOMKilled";
        }
        if (lastReason != null && !"Completed".equals(lastReason) && cs.getRestartCount() != null && cs.getRestartCount() > 0) {
            return lastReason;
        }

        return null;
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
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.configMaps().inAnyNamespace().list().getItems()
            : client.configMaps().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(cm -> new ConfigMapDto(
                cm.getMetadata().getName(),
                cm.getMetadata().getNamespace(),
                cm.getData() != null ? cm.getData() : Map.of(),
                cm.getBinaryData() != null ? cm.getBinaryData().size() : 0,
                cm.getMetadata().getCreationTimestamp()))
            .toList();
    }

    public List<SecretDto> listSecrets(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.secrets().inAnyNamespace().list().getItems()
            : client.secrets().inNamespace(namespace).list().getItems();
        return items.stream()
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
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.apps().statefulSets().inAnyNamespace().list().getItems()
            : client.apps().statefulSets().inNamespace(namespace).list().getItems();
        return items.stream()
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
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.apps().daemonSets().inAnyNamespace().list().getItems()
            : client.apps().daemonSets().inNamespace(namespace).list().getItems();
        return items.stream()
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

    public List<JobDto> listJobs(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.batch().v1().jobs().inAnyNamespace().list().getItems()
            : client.batch().v1().jobs().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(this::toJobDto)
            .toList();
    }

    private JobDto toJobDto(Job job) {
        var meta = job.getMetadata();
        var spec = job.getSpec();
        var status = job.getStatus();

        int succeeded = status != null && status.getSucceeded() != null ? status.getSucceeded() : 0;
        int failed = status != null && status.getFailed() != null ? status.getFailed() : 0;
        int active = status != null && status.getActive() != null ? status.getActive() : 0;
        Integer completions = spec != null ? spec.getCompletions() : null;

        String jobStatus;
        if (status != null && status.getCompletionTime() != null) {
            jobStatus = "Succeeded";
        } else if (failed > 0 && active == 0) {
            jobStatus = "Failed";
        } else if (active > 0) {
            jobStatus = "Running";
        } else {
            jobStatus = "Pending";
        }

        return new JobDto(
            meta.getName(),
            meta.getNamespace(),
            jobStatus,
            succeeded,
            failed,
            active,
            completions,
            firstImage(spec != null && spec.getTemplate() != null && spec.getTemplate().getSpec() != null
                ? spec.getTemplate().getSpec().getContainers() : null),
            status != null ? status.getStartTime() : null,
            status != null ? status.getCompletionTime() : null,
            meta.getCreationTimestamp()
        );
    }

    public List<CronJobDto> listCronJobs(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.batch().v1().cronjobs().inAnyNamespace().list().getItems()
            : client.batch().v1().cronjobs().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(this::toCronJobDto)
            .toList();
    }

    private CronJobDto toCronJobDto(CronJob cronJob) {
        var meta = cronJob.getMetadata();
        var spec = cronJob.getSpec();
        var status = cronJob.getStatus();

        var jobTemplateSpec = spec != null && spec.getJobTemplate() != null
            ? spec.getJobTemplate().getSpec() : null;
        var podSpec = jobTemplateSpec != null && jobTemplateSpec.getTemplate() != null
            ? jobTemplateSpec.getTemplate().getSpec() : null;

        return new CronJobDto(
            meta.getName(),
            meta.getNamespace(),
            spec != null ? spec.getSchedule() : null,
            spec != null && Boolean.TRUE.equals(spec.getSuspend()),
            status != null && status.getActive() != null ? status.getActive().size() : 0,
            firstImage(podSpec != null ? podSpec.getContainers() : null),
            status != null ? status.getLastScheduleTime() : null,
            meta.getCreationTimestamp()
        );
    }

    // ── Network ───────────────────────────────────────────────────────────────

    public List<ServiceDto> listServices(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.services().inAnyNamespace().list().getItems()
            : client.services().inNamespace(namespace).list().getItems();
        return items.stream()
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
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.network().v1().ingresses().inAnyNamespace().list().getItems()
            : client.network().v1().ingresses().inNamespace(namespace).list().getItems();
        return items.stream()
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
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.persistentVolumeClaims().inAnyNamespace().list().getItems()
            : client.persistentVolumeClaims().inNamespace(namespace).list().getItems();
        return items.stream()
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

    // ── HPA (Horizontal Pod Autoscalers) ─────────────────────────────────────

    public List<HpaDto> listHpas(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.autoscaling().v2().horizontalPodAutoscalers().inAnyNamespace().list().getItems()
            : client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(this::toHpaDto)
            .toList();
    }

    private HpaDto toHpaDto(io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler hpa) {
        var meta = hpa.getMetadata();
        var spec = hpa.getSpec();
        var status = hpa.getStatus();

        String targetRef = spec != null && spec.getScaleTargetRef() != null
            ? spec.getScaleTargetRef().getKind() + "/" + spec.getScaleTargetRef().getName()
            : "—";

        int minReplicas = spec != null && spec.getMinReplicas() != null ? spec.getMinReplicas() : 1;
        int maxReplicas = spec != null && spec.getMaxReplicas() != null ? spec.getMaxReplicas() : 0;
        int currentReplicas = status != null && status.getCurrentReplicas() != null ? status.getCurrentReplicas() : 0;

        // Extract CPU utilization from metrics (most common metric type)
        Integer currentCpu = null;
        Integer targetCpu = null;

        if (spec != null && spec.getMetrics() != null) {
            for (var metric : spec.getMetrics()) {
                if (metric.getType() != null && "Resource".equals(metric.getType())
                    && metric.getResource() != null
                    && "cpu".equals(metric.getResource().getName())) {
                    if (metric.getResource().getTarget() != null
                        && metric.getResource().getTarget().getAverageUtilization() != null) {
                        targetCpu = metric.getResource().getTarget().getAverageUtilization();
                    }
                    break;
                }
            }
        }
        if (status != null && status.getCurrentMetrics() != null) {
            for (var metric : status.getCurrentMetrics()) {
                if (metric.getType() != null && "Resource".equals(metric.getType())
                    && metric.getResource() != null
                    && "cpu".equals(metric.getResource().getName())
                    && metric.getResource().getCurrent() != null
                    && metric.getResource().getCurrent().getAverageUtilization() != null) {
                    currentCpu = metric.getResource().getCurrent().getAverageUtilization();
                    break;
                }
            }
        }

        return new HpaDto(
            meta.getName(),
            meta.getNamespace(),
            targetRef,
            minReplicas,
            maxReplicas,
            currentReplicas,
            currentCpu,
            targetCpu,
            meta.getCreationTimestamp()
        );
    }

    // ── Access Control (RBAC) ───────────────────────────────────────────────────
    // ServiceAccount/Role/RoleBinding are creatable/editable/deletable through the
    // generic resource endpoints (ResourceCreationService.ALLOWED_KINDS); ClusterRole/
    // ClusterRoleBinding through ClusterResourceCreationService + the cluster-scoped
    // edit route. See those classes' comments for the privilege-escalation trade-off
    // the maintainer accepted. `systemManaged` (RbacFilters) is a UI-only signal so
    // the pages can default to hiding the ~70 Kubernetes-bootstrapped rows by name.

    public List<ServiceAccountDto> listServiceAccounts(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.serviceAccounts().inAnyNamespace().list().getItems()
            : client.serviceAccounts().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(sa -> new ServiceAccountDto(
                sa.getMetadata().getName(),
                sa.getMetadata().getNamespace(),
                sa.getSecrets() != null ? sa.getSecrets().size() : 0,
                sa.getImagePullSecrets() != null ? sa.getImagePullSecrets().size() : 0,
                sa.getAutomountServiceAccountToken(),
                sa.getMetadata().getCreationTimestamp(),
                RbacFilters.isDefaultServiceAccount(sa.getMetadata().getName())
                    || RbacFilters.isSystemNamespace(sa.getMetadata().getNamespace())))
            .toList();
    }

    public List<RoleDto> listRoles(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.rbac().roles().inAnyNamespace().list().getItems()
            : client.rbac().roles().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(r -> new RoleDto(
                r.getMetadata().getName(),
                r.getMetadata().getNamespace(),
                rules(r.getRules()),
                r.getMetadata().getCreationTimestamp(),
                RbacFilters.isSystemName(r.getMetadata().getName())
                    || RbacFilters.hasBootstrapLabel(r.getMetadata())
                    || RbacFilters.isSystemNamespace(r.getMetadata().getNamespace())))
            .toList();
    }

    public List<ClusterRoleDto> listClusterRoles(long clusterId) {
        return clientFactory.getClient(clusterId).rbac().clusterRoles()
            .list().getItems().stream()
            .map(cr -> new ClusterRoleDto(
                cr.getMetadata().getName(),
                rules(cr.getRules()),
                cr.getMetadata().getCreationTimestamp(),
                RbacFilters.isSystemName(cr.getMetadata().getName())
                    || RbacFilters.hasBootstrapLabel(cr.getMetadata())))
            .toList();
    }

    public List<RoleBindingDto> listRoleBindings(long clusterId, String namespace) {
        var client = clientFactory.getClient(clusterId);
        var items = "all".equals(namespace)
            ? client.rbac().roleBindings().inAnyNamespace().list().getItems()
            : client.rbac().roleBindings().inNamespace(namespace).list().getItems();
        return items.stream()
            .map(rb -> new RoleBindingDto(
                rb.getMetadata().getName(),
                rb.getMetadata().getNamespace(),
                rb.getRoleRef() != null ? rb.getRoleRef().getKind() : null,
                rb.getRoleRef() != null ? rb.getRoleRef().getName() : null,
                subjects(rb.getSubjects()),
                rb.getMetadata().getCreationTimestamp(),
                RbacFilters.isSystemName(rb.getMetadata().getName())
                    || RbacFilters.isSystemNamespace(rb.getMetadata().getNamespace())
                    || (rb.getRoleRef() != null && RbacFilters.isSystemName(rb.getRoleRef().getName()))))
            .toList();
    }

    public List<ClusterRoleBindingDto> listClusterRoleBindings(long clusterId) {
        return clientFactory.getClient(clusterId).rbac().clusterRoleBindings()
            .list().getItems().stream()
            .map(crb -> new ClusterRoleBindingDto(
                crb.getMetadata().getName(),
                crb.getRoleRef() != null ? crb.getRoleRef().getKind() : null,
                crb.getRoleRef() != null ? crb.getRoleRef().getName() : null,
                subjects(crb.getSubjects()),
                crb.getMetadata().getCreationTimestamp(),
                RbacFilters.isSystemName(crb.getMetadata().getName())
                    || RbacFilters.hasBootstrapLabel(crb.getMetadata())
                    || (crb.getRoleRef() != null && RbacFilters.isSystemName(crb.getRoleRef().getName()))))
            .toList();
    }

    private List<RbacRuleDto> rules(List<PolicyRule> rules) {
        if (rules == null) return List.of();
        return rules.stream()
            .map(r -> new RbacRuleDto(
                r.getApiGroups() != null ? r.getApiGroups() : List.of(),
                r.getResources() != null ? r.getResources() : List.of(),
                r.getResourceNames() != null ? r.getResourceNames() : List.of(),
                r.getVerbs() != null ? r.getVerbs() : List.of()))
            .toList();
    }

    private List<RbacSubjectDto> subjects(List<Subject> subjects) {
        if (subjects == null) return List.of();
        return subjects.stream()
            .map(s -> new RbacSubjectDto(s.getKind(), s.getName(), s.getNamespace()))
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
