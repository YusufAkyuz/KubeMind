package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.authorization.v1.ResourceRule;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectRulesReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectRulesReviewBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "what may this kubeconfig actually do here" by asking the cluster,
 * so the UI can stop offering buttons that are guaranteed to fail.
 *
 * Uses SelfSubjectRulesReview: one call returns every rule that applies to the
 * caller in a namespace, instead of one SelfSubjectAccessReview per verb+kind
 * (which would be ~45 round trips per namespace switch). Kubernetes documents
 * this endpoint as being for exactly this purpose — showing a user what they
 * can do — and explicitly not as an authorization mechanism. That matches how
 * it's used here: the cluster still refuses anything it shouldn't allow, this
 * only decides what to render.
 */
@Service
public class PermissionsService {

    private static final Logger log = LoggerFactory.getLogger(PermissionsService.class);

    /** Kind -> {apiGroup, plural resource}, for the kinds the UI has controls for. */
    private static final Map<String, String[]> KIND_RESOURCES = Map.ofEntries(
        Map.entry("Pod", new String[]{"", "pods"}),
        Map.entry("Deployment", new String[]{"apps", "deployments"}),
        Map.entry("StatefulSet", new String[]{"apps", "statefulsets"}),
        Map.entry("DaemonSet", new String[]{"apps", "daemonsets"}),
        Map.entry("Job", new String[]{"batch", "jobs"}),
        Map.entry("CronJob", new String[]{"batch", "cronjobs"}),
        Map.entry("Service", new String[]{"", "services"}),
        Map.entry("Ingress", new String[]{"networking.k8s.io", "ingresses"}),
        Map.entry("ConfigMap", new String[]{"", "configmaps"}),
        Map.entry("Secret", new String[]{"", "secrets"}),
        Map.entry("PersistentVolumeClaim", new String[]{"", "persistentvolumeclaims"}),
        Map.entry("HorizontalPodAutoscaler", new String[]{"autoscaling", "horizontalpodautoscalers"}),
        Map.entry("ServiceAccount", new String[]{"", "serviceaccounts"}),
        Map.entry("Role", new String[]{"rbac.authorization.k8s.io", "roles"}),
        Map.entry("RoleBinding", new String[]{"rbac.authorization.k8s.io", "rolebindings"})
    );

    /** The verbs the UI gates controls on. */
    private static final List<String> VERBS = List.of("create", "update", "patch", "delete", "get");

    private final ClusterClientFactory clientFactory;

    public PermissionsService(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** Kind -> verb -> allowed. */
    public record NamespacePermissions(String namespace, Map<String, Map<String, Boolean>> kinds,
                                       boolean resolved) {}

    /**
     * @param namespace the namespace to evaluate in; permissions are per-namespace,
     *                  so "all namespaces" cannot be answered and callers should
     *                  fall back to showing controls optimistically there.
     */
    public NamespacePermissions forNamespace(long clusterId, String namespace) {
        List<ResourceRule> rules;
        try {
            SelfSubjectRulesReview review = clientFactory.getClient(clusterId)
                .authorization().v1().selfSubjectRulesReview()
                .create(new SelfSubjectRulesReviewBuilder()
                    .withNewSpec().withNamespace(namespace).endSpec()
                    .build());
            rules = review.getStatus() != null && review.getStatus().getResourceRules() != null
                ? review.getStatus().getResourceRules() : List.of();

            // The API reports incomplete results rather than failing; treating a
            // partial answer as authoritative would hide controls the user does have.
            if (Boolean.TRUE.equals(review.getStatus() != null ? review.getStatus().getIncomplete() : null)) {
                log.debug("SelfSubjectRulesReview incomplete for cluster {} ns {}", clusterId, namespace);
                return unresolved(namespace);
            }
        } catch (Exception e) {
            // Some clusters/proxies don't expose the endpoint. Fail open: the UI
            // shows its controls and the cluster still refuses what it should.
            log.debug("SelfSubjectRulesReview unavailable for cluster {} ns {}: {}",
                clusterId, namespace, e.getMessage());
            return unresolved(namespace);
        }

        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        KIND_RESOURCES.forEach((kind, gr) -> {
            Map<String, Boolean> perVerb = new LinkedHashMap<>();
            for (String verb : VERBS) {
                perVerb.put(verb, allows(rules, gr[0], gr[1], verb));
            }
            result.put(kind, perVerb);
        });
        return new NamespacePermissions(namespace, result, true);
    }

    /** Nothing was determined — callers should not hide anything on this basis. */
    private NamespacePermissions unresolved(String namespace) {
        return new NamespacePermissions(namespace, Map.of(), false);
    }

    /** RBAC is additive: one matching rule is enough, and "*" matches everything. */
    static boolean allows(List<ResourceRule> rules, String apiGroup, String resource, String verb) {
        for (ResourceRule rule : rules) {
            if (matches(rule.getVerbs(), verb)
                && matches(rule.getApiGroups(), apiGroup)
                && matches(rule.getResources(), resource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(List<String> values, String wanted) {
        if (values == null) return false;
        return values.contains("*") || values.contains(wanted);
    }

    static Set<String> knownKinds() {
        return KIND_RESOURCES.keySet();
    }

    // ── Node Shell / Cluster Terminal ────────────────────────────────────────
    //
    // Neither fits the "kind + namespace + verb" shape above: both are
    // KubeMind-provisioned side effects (a debug pod, a throwaway
    // ServiceAccount + ClusterRoleBinding), not CRUD on a resource kind the
    // user is browsing. A single targeted SelfSubjectAccessReview per action is
    // the right tool here instead of folding them into the bulk rules review.
    //
    // "kube-system" is duplicated from NodeExecWebSocketHandler.DEBUG_NAMESPACE
    // / ClusterTerminalWebSocketHandler.TERMINAL_NAMESPACE (different package,
    // not worth a cross-module constant for one string) — if either changes,
    // this must too.
    private static final String TERMINAL_NAMESPACE = "kube-system";

    /**
     * Node Shell schedules a privileged, host-mounted debug pod into
     * {@code kube-system}. RBAC can't see "privileged" — Pod Security Admission
     * decides that separately and isn't queryable this way — so "may this
     * identity create a Pod there" is the closest available signal, not a
     * guarantee the pod will actually be admitted.
     */
    public boolean canOpenNodeShell(long clusterId) {
        return selfSubjectAccessReview(clusterId, "", "pods", "create", TERMINAL_NAMESPACE);
    }

    /**
     * Cluster Terminal provisions a ServiceAccount and binds it to
     * cluster-admin. Being able to create a ClusterRoleBinding is the one
     * check that actually matters — it's already equivalent to holding
     * cluster-admin, so there's no weaker permission worth asking about.
     */
    public boolean canOpenClusterTerminal(long clusterId) {
        return selfSubjectAccessReview(clusterId, "rbac.authorization.k8s.io",
            "clusterrolebindings", "create", null);
    }

    /**
     * Fails open, matching {@link #forNamespace} — a control this identity
     * genuinely has is worse to hide than one to let a click discover is
     * refused, and the cluster enforces the real limit either way.
     */
    private boolean selfSubjectAccessReview(long clusterId, String group, String resource,
                                            String verb, String namespace) {
        try {
            var attrs = new io.fabric8.kubernetes.api.model.authorization.v1.ResourceAttributesBuilder()
                .withGroup(group).withResource(resource).withVerb(verb);
            if (namespace != null) attrs.withNamespace(namespace);

            SelfSubjectAccessReview review = clientFactory.getClient(clusterId)
                .authorization().v1().selfSubjectAccessReview()
                .create(new SelfSubjectAccessReviewBuilder()
                    .withNewSpec().withResourceAttributes(attrs.build()).endSpec()
                    .build());
            return review.getStatus() != null && Boolean.TRUE.equals(review.getStatus().getAllowed());
        } catch (Exception e) {
            log.debug("SelfSubjectAccessReview unavailable for cluster {} ({} {}/{}): {}",
                clusterId, verb, group, resource, e.getMessage());
            return true;
        }
    }
}
