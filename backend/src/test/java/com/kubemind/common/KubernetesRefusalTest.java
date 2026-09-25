package com.kubemind.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal is the message people see most often when something is set up
 * slightly wrong, so it is worth reading well. The bar: keep every fact the API
 * server gave — who, verb, resource, where — and never invent one.
 */
class KubernetesRefusalTest {

    /** Verbatim from a kubeconfig scoped to a ServiceAccount without secret access. */
    private static final String HELM_LIST = "Error: list: failed to list: secrets is forbidden: User "
        + "\"system:serviceaccount:dev-team:kubemind-developer\" cannot list resource "
        + "\"secrets\" in API group \"\" in the namespace \"dev-team\"";

    @Test
    void keepsEveryFactTheApiServerGave() {
        var refusal = KubernetesRefusal.parse(HELM_LIST).orElseThrow();

        assertThat(refusal.verb()).isEqualTo("list");
        assertThat(refusal.resource()).isEqualTo("secrets");
        assertThat(refusal.namespace()).isEqualTo("dev-team");
        assertThat(refusal.subject()).contains("kubemind-developer").contains("dev-team");
    }

    /** "system:serviceaccount:ns:name" is mostly boilerplate; the account and its namespace are the point. */
    @Test
    void readsAServiceAccountAsAnAccountRatherThanAUrn() {
        assertThat(KubernetesRefusal.parse(HELM_LIST).orElseThrow().subject())
            .isEqualTo("ServiceAccount \"kubemind-developer\" (namespace dev-team)");
    }

    @Test
    void readsAPlainUserAsThemselves() {
        String raw = "pods is forbidden: User \"alice\" cannot create resource \"pods\" "
            + "in API group \"\" in the namespace \"prod\"";

        assertThat(KubernetesRefusal.parse(raw).orElseThrow().subject()).isEqualTo("\"alice\"");
    }

    @Test
    void handlesClusterScopedRefusalsWhichNameNoNamespace() {
        String raw = "clusterroles.rbac.authorization.k8s.io is forbidden: User \"alice\" cannot "
            + "list resource \"clusterroles\" in API group \"rbac.authorization.k8s.io\" at the cluster scope";

        var refusal = KubernetesRefusal.parse(raw).orElseThrow();
        assertThat(refusal.namespace()).isNull();
        assertThat(KubernetesRefusal.describe(refusal)).contains("cluster-wide");
    }

    @Test
    void keepsSubresourcesIntact() {
        String raw = "pods is forbidden: User \"alice\" cannot create resource \"pods/exec\" "
            + "in API group \"\" in the namespace \"prod\"";

        assertThat(KubernetesRefusal.parse(raw).orElseThrow().resource()).isEqualTo("pods/exec");
    }

    @Test
    void saysWhoseLimitItIsSoItDoesNotReadAsAKubeMindDecision() {
        assertThat(KubernetesRefusal.explain(HELM_LIST))
            .contains("kubemind-developer")
            .contains("list")
            .contains("secrets")
            .contains("dev-team")
            .contains("not from KubeMind");
    }

    /**
     * Rewriting a message we did not understand would replace a real error with a
     * vaguer one — worse than showing it raw.
     */
    @Test
    void passesThroughAnythingItDoesNotRecognise() {
        String raw = "Error: UPGRADE FAILED: another operation is in progress";

        assertThat(KubernetesRefusal.explain(raw)).isEqualTo(raw);
        assertThat(KubernetesRefusal.parse(raw)).isEmpty();
    }

    @Test
    void survivesNullAndBlankInput() {
        assertThat(KubernetesRefusal.parse(null)).isEmpty();
        assertThat(KubernetesRefusal.parse("  ")).isEmpty();
        assertThat(KubernetesRefusal.isForbidden(null)).isFalse();
    }

    @Test
    void recognisesRefusalWordingInEitherForm() {
        assertThat(KubernetesRefusal.isForbidden(HELM_LIST)).isTrue();
        assertThat(KubernetesRefusal.isForbidden("Error: forbidden: User \"x\" cannot get")).isTrue();
        assertThat(KubernetesRefusal.isForbidden("Error: release not found")).isFalse();
    }
}
