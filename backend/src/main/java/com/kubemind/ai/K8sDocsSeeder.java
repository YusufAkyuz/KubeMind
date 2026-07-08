package com.kubemind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the RAG "k8s-docs" corpus (Phase A3, ships with the product) on first
 * boot — curated troubleshooting knowledge that closes the expertise gap of a
 * small local model. Idempotent: each doc has a stable id, and PgVectorStore
 * upserts on conflict, but we skip entirely once the expected count is present
 * so a normal restart doesn't re-embed ~13 docs through Ollama every time.
 */
@Component
public class K8sDocsSeeder {

    private static final Logger log = LoggerFactory.getLogger(K8sDocsSeeder.class);

    private final RagService ragService;
    private final JdbcTemplate jdbcTemplate;

    public K8sDocsSeeder(RagService ragService, JdbcTemplate jdbcTemplate) {
        this.ragService = ragService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final Map<String, String[]> DOCS = new LinkedHashMap<>(); // title -> {id, content}
    static {
        add("k8s-doc-crashloopbackoff", "CrashLoopBackOff", """
            CrashLoopBackOff means the container starts, exits, and Kubernetes keeps restarting it \
            with an increasing backoff delay. Check the container's exit code and last terminated \
            reason (kubectl describe pod). Exit code 0 with fast repeated restarts usually means the \
            app's main process finishes and exits normally — the image's entrypoint is wrong for a \
            long-running service. Exit code 1 or other non-zero codes point to an application error — \
            check logs with kubectl logs <pod> --previous. Exit code 137 specifically means OOMKilled, \
            not a plain crash — see the OOMKilled entry. A missing config file, unreachable dependency \
            (database, downstream service), or a failing startup health check are the most common \
            root causes of a genuine application crash.""");

        add("k8s-doc-imagepullbackoff", "ImagePullBackOff / ErrImagePull", """
            ImagePullBackOff / ErrImagePull means the kubelet could not pull the container image. \
            Common causes: a typo in the image name or tag, the tag doesn't exist in the registry, \
            the image is in a private registry and the pod has no matching imagePullSecrets, or the \
            registry is unreachable from the node (network policy, DNS, or the registry is down). \
            Check the exact error in kubectl describe pod — it usually names the registry and image \
            it tried to pull. For private registries, verify the Secret referenced in \
            spec.imagePullSecrets exists in the same namespace and has valid credentials.""");

        add("k8s-doc-oomkilled", "OOMKilled", """
            OOMKilled (exit code 137, state.terminated.reason=OOMKilled) means the container exceeded \
            its memory limit and the kernel killed it. Check spec.containers[].resources.limits.memory \
            — either the limit is set too low for what the app actually needs, or the app has a memory \
            leak. Compare actual usage (kubectl top pod, or the node/pod metrics in KubeMind) against \
            the configured limit over time. If the app is fine at a higher limit, raise it; if usage \
            grows unbounded over time, that's a leak in the application itself, not a Kubernetes \
            configuration problem.""");

        add("k8s-doc-pending-unschedulable", "Pod stuck Pending (unschedulable)", """
            A Pod stuck in Pending phase with a FailedScheduling event means the scheduler could not \
            find a node for it. Read the event message in kubectl describe pod — it names the exact \
            reason, one of: insufficient CPU/memory on every node (requests too high, or the cluster \
            is full), a nodeSelector/affinity rule that no node satisfies, a taint on every candidate \
            node with no matching toleration on the pod, or no node has an available volume for a \
            required PVC (see the PVC entry). Pending with no scheduling event at all usually means \
            an admission webhook or the API server itself is the bottleneck.""");

        add("k8s-doc-probe-failures", "Readiness/liveness probe failures", """
            A failing liveness probe causes Kubernetes to restart the container; a failing readiness \
            probe removes the pod from Service endpoints without restarting it. Distinguish "slow to \
            start" from "actually broken": if the app just needs more time on boot, raise \
            initialDelaySeconds or failureThreshold rather than treating it as a bug. If the probe \
            path/port is wrong (e.g. probing an HTTP path that doesn't exist, or the wrong container \
            port), fix the probe definition. Check the exact failure reason in \
            kubectl describe pod — timeout vs. connection refused vs. non-2xx HTTP status point to \
            different causes (app too slow, app not listening yet, or app returning an error).""");

        add("k8s-doc-pvc-pending", "PersistentVolumeClaim stuck Pending", """
            A PVC stuck in Pending phase usually means no PersistentVolume matches its request (size, \
            access mode, StorageClass) and there's no dynamic provisioner configured for that \
            StorageClass, or the StorageClass name in the PVC spec doesn't exist at all. A PVC that \
            uses WaitForFirstConsumer binding mode will normally stay Pending until a Pod that \
            references it is actually scheduled — that's expected behavior, not an error. Check \
            kubectl get storageclass and kubectl describe pvc for the exact provisioning event.""");

        add("k8s-doc-service-no-endpoints", "Service has no endpoints / traffic not reaching pods", """
            If a Service exists but traffic never reaches any pod, check kubectl get endpoints \
            <service> — an empty endpoints list means the Service's spec.selector doesn't match any \
            pod's labels, or the matching pods exist but aren't Ready (readiness probe failing removes \
            them from endpoints even though the pod is Running). Compare the Service's selector labels \
            character-for-character against the pod template's labels; a small typo is the most common \
            cause.""");

        add("k8s-doc-dns-resolution", "In-cluster DNS resolution failures", """
            Pods failing to resolve other Services by name (e.g. "could not resolve host") usually \
            points to CoreDNS: check that the coredns pods in kube-system are Running and not \
            restarting/OOMKilled, and that the failing pod's /etc/resolv.conf points at the cluster \
            DNS service IP. A Pod with dnsPolicy set to something other than ClusterFirst won't use \
            cluster DNS at all — verify spec.dnsPolicy if lookups fail for exactly one workload while \
            others work fine. Cross-namespace lookups need the full \
            <service>.<namespace>.svc.cluster.local form unless within the same namespace.""");

        add("k8s-doc-node-notready", "Node NotReady", """
            A Node reporting NotReady means the kubelet stopped reporting status (network partition, \
            kubelet crashed, node under heavy resource pressure, or the node is shutting down/rebooting). \
            Existing pods on that node keep running for a grace period but new pods won't schedule \
            there, and after the pod-eviction-timeout (default 5 minutes) the control plane starts \
            evicting pods to reschedule elsewhere. Check kubectl describe node for the exact condition \
            reasons (MemoryPressure, DiskPressure, PIDPressure, NetworkUnavailable) — these usually \
            point directly at the root cause.""");

        add("k8s-doc-configmap-secret-mount", "ConfigMap/Secret mount or env issues", """
            A Pod stuck Pending or ContainerCreating with a "configmap not found" or "secret not \
            found" event references a ConfigMap/Secret that doesn't exist in that namespace, or was \
            deleted after the Pod spec was created. ConfigMap/Secret names are namespace-scoped — a \
            resource with the same name in a different namespace won't be found. If using \
            envFrom/valueFrom with a specific key, a missing key (not just a missing object) also \
            blocks the container from starting, with a distinct "key not found" event.""");

        add("k8s-doc-rollout-stuck", "Deployment rollout stuck / not progressing", """
            A Deployment stuck mid-rollout (old and new ReplicaSets both partially scaled) usually \
            means the new Pods aren't becoming Ready — check the new ReplicaSet's pods directly for \
            their own failure reason (crash, image pull, probe failure — see those entries). \
            maxUnavailable/maxSurge in the rolling update strategy control how aggressively old pods \
            are replaced; a maxUnavailable of 0 combined with a cluster with no spare capacity can also \
            stall a rollout. kubectl rollout status and kubectl describe deployment show the exact \
            blocking condition.""");

        add("k8s-doc-job-cronjob-failures", "Job / CronJob failures", """
            A Job showing Failed status with active=0 means it exhausted backoffLimit retries without \
            a single Pod completing successfully — check the most recent failed Pod's logs for the \
            actual application error. A CronJob that never seems to run its schedule usually has \
            spec.suspend set to true, or the concurrencyPolicy (Forbid) is skipping runs because a \
            previous Job is still active. startingDeadlineSeconds too short relative to scheduler \
            load can also cause silently skipped runs.""");

        add("k8s-doc-resource-requests-limits", "Resource requests and limits guidance", """
            requests are what the scheduler reserves (affects whether a Pod can be scheduled at all); \
            limits are the hard ceiling enforced at runtime (CPU is throttled past its limit, memory \
            past its limit gets the container OOMKilled). Setting requests far below real usage causes \
            node overcommit and noisy-neighbor CPU throttling; setting limits far below real usage \
            causes OOMKilled/throttling for the workload itself. A good starting point is requests near \
            typical steady-state usage and limits with headroom for normal spikes, tuned from observed \
            metrics rather than guessed.""");

        add("k8s-doc-rbac-forbidden", "RBAC Forbidden errors", """
            A "Forbidden" or "cannot list/get/create resource X" error means the acting identity's \
            Role/ClusterRole doesn't grant that verb on that resource (and apiGroup) in that namespace. \
            Check which ServiceAccount the Pod uses (spec.serviceAccountName, defaults to "default"), \
            then check the RoleBindings/ClusterRoleBindings that reference it, and finally the \
            Role/ClusterRole's rules for the specific verb+resource+apiGroup combination the caller is \
            missing — the API server's error message names the exact missing permission.""");
    }

    private static void add(String id, String title, String content) {
        DOCS.put(title, new String[]{id, content});
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'corpus' = ?",
                Integer.class, RagService.CORPUS_K8S_DOCS);
            if (existing != null && existing >= DOCS.size()) {
                log.debug("k8s-docs RAG corpus already seeded ({} docs) — skipping", existing);
                return;
            }
            log.info("Seeding k8s-docs RAG corpus ({} docs)...", DOCS.size());
            DOCS.forEach((title, idAndContent) -> ragService.indexK8sDoc(idAndContent[0], title, idAndContent[1]));
            log.info("k8s-docs RAG corpus seeded.");
        } catch (Exception e) {
            // Ollama unreachable / embedding model not pulled yet — RAG stays a soft
            // enhancement; Explain/Chat keep working without it.
            log.warn("k8s-docs RAG seeding skipped: {}", e.getMessage());
        }
    }
}
