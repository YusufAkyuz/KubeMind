package com.kubemind.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cases here are taken from this project's own audit log and cluster
 * profiles — including the two links a measurement found to be genuine and the
 * four it found to be nonsense.
 */
class ChangeEffectLinkerTest {

    private static final Instant CHANGE_AT = Instant.parse("2026-08-03T12:30:00Z");

    private static ChangeEffectLinker.Change change(String action, String ref) {
        return new ChangeEffectLinker.Change(action, ref, "yusuf", CHANGE_AT);
    }

    private static ChangeEffectLinker.Warning warning(String kind, String ns, String name,
                                                      String reason, long minutesAfter) {
        return new ChangeEffectLinker.Warning(kind, ns, name, reason,
            CHANGE_AT.plusSeconds(minutesAfter * 60));
    }

    /**
     * The real one from the measurement: a Deployment was created and its pods
     * started failing ten minutes later. The Deployment itself never appears in
     * the events, which is why matching only the named object would miss it.
     */
    @Test
    void linksAWorkloadChangeToItsOwnPodsFailing() {
        var effects = ChangeEffectLinker.link(
            List.of(change("CREATE_RESOURCE", "Deployment/dev-team/nginx-deployment")),
            List.of(warning("Pod", "dev-team", "nginx-deployment-55cdd48788-5w5vx", "Failed", 10)));

        assertThat(effects).hasSize(1);
        assertThat(effects.get(0).matchLevel()).isEqualTo("OWNED_OBJECT");
        assertThat(effects.get(0).minutesAfter()).isEqualTo(10);
        assertThat(effects.get(0).warningSignature()).isEqualTo("Pod/dev-team/nginx-deployment-55cdd48788-5w5vx");
    }

    /** The other real one: an HPA created against a target that does not exist. */
    @Test
    void linksAChangeToAWarningOnTheSameObject() {
        var effects = ChangeEffectLinker.link(
            List.of(change("CREATE_RESOURCE", "HorizontalPodAutoscaler/mydep/my-hpa")),
            List.of(warning("HorizontalPodAutoscaler", "mydep", "my-hpa", "FailedGetScale", 0)));

        assertThat(effects).singleElement()
            .extracting(ChangeEffectLinker.ChangeEffect::matchLevel).isEqualTo("SAME_OBJECT");
    }

    /**
     * The four false positives that killed the backward design: ArgoCD pods
     * failing in kube-system while a Prometheus release was uninstalled there.
     * Same namespace, minutes apart, and completely unrelated.
     */
    @Test
    void refusesToLinkUnrelatedObjectsThatMerelyShareANamespace() {
        var effects = ChangeEffectLinker.link(
            List.of(change("UNINSTALL_HELM_RELEASE", "HelmRelease/kube-system/my-prom")),
            List.of(warning("Pod", "kube-system", "my-argo-argo-cd-server-b74d8b787-s8pt9", "Failed", 11)));

        assertThat(effects).isEmpty();
    }

    /** A Helm release does name the resources it renders, and those do count. */
    @Test
    void linksAHelmReleaseToTheResourcesItNames() {
        var effects = ChangeEffectLinker.link(
            List.of(change("UPGRADE_HELM_VALUES", "HelmRelease/kube-system/my-prom")),
            List.of(warning("Pod", "kube-system", "my-prom-server-7d9f-abc", "BackOff", 3)));

        assertThat(effects).singleElement()
            .extracting(ChangeEffectLinker.ChangeEffect::matchLevel).isEqualTo("OWNED_OBJECT");
    }

    /** "my-prom" must not claim "my-promtail" — the separator is what prevents it. */
    @Test
    void doesNotTreatANameThatMerelyStartsTheSameAsOwned() {
        var effects = ChangeEffectLinker.link(
            List.of(change("UPGRADE_HELM_VALUES", "HelmRelease/kube-system/my-prom")),
            List.of(warning("Pod", "kube-system", "my-promtail-xyz", "BackOff", 3)));

        assertThat(effects).isEmpty();
    }

    /**
     * The single most important negative: something already broken before the
     * change is not evidence about the change, however close the timestamps.
     */
    @Test
    void ignoresWarningsThatWereAlreadyBurningBeforeTheChange() {
        var effects = ChangeEffectLinker.link(
            List.of(change("RESTART_RESOURCE", "Deployment/dev-team/api")),
            List.of(warning("Pod", "dev-team", "api-1", "CrashLoopBackOff", -1)));

        assertThat(effects).isEmpty();
    }

    @Test
    void ignoresWarningsTooLongAfterTheChangeToBeAboutIt() {
        var effects = ChangeEffectLinker.link(
            List.of(change("RESTART_RESOURCE", "Deployment/dev-team/api")),
            List.of(warning("Pod", "dev-team", "api-1", "CrashLoopBackOff", 60)));

        assertThat(effects).isEmpty();
    }

    @Test
    void neverMatchesAcrossNamespaces() {
        var effects = ChangeEffectLinker.link(
            List.of(change("CREATE_RESOURCE", "Deployment/dev-team/api")),
            List.of(warning("Pod", "prod", "api-abc-123", "Failed", 2)));

        assertThat(effects).isEmpty();
    }

    /** Cluster-scoped audit rows own nothing in a namespace, so they are not watched. */
    @Test
    void skipsChangesThatNameNoNamespacedObject() {
        var effects = ChangeEffectLinker.link(
            List.of(change("DELETE_CLUSTER", "Cluster/eba-ex"), change("RESET_USER_PASSWORD", "User/bob")),
            List.of(warning("Pod", "dev-team", "eba-ex-1", "Failed", 2)));

        assertThat(effects).isEmpty();
    }

    /**
     * Found the first time this ran against a real cluster: a bad image makes
     * Kubernetes emit "Failed to pull image", then "Error: ErrImagePull", then
     * "Error: ImagePullBackOff" — three Events, one reason, one pod. Row per
     * Event meant the same fact three times on screen.
     */
    @Test
    void reportsOneConditionOnceEvenWhenKubernetesRepeatsTheEvent() {
        var effects = ChangeEffectLinker.link(
            List.of(change("CREATE_RESOURCE", "Deployment/default/effect-probe-dep")),
            List.of(warning("Pod", "default", "effect-probe-dep-6797b9479b-vr8z9", "Failed", 2),
                    warning("Pod", "default", "effect-probe-dep-6797b9479b-vr8z9", "Failed", 3),
                    warning("Pod", "default", "effect-probe-dep-6797b9479b-vr8z9", "Failed", 5)));

        assertThat(effects).hasSize(1);
        // The earliest sighting is the one closest to the change.
        assertThat(effects.get(0).minutesAfter()).isEqualTo(2);
    }

    /** Different reasons on the same object are different facts and both belong. */
    @Test
    void keepsDistinctReasonsOnTheSameObject() {
        var effects = ChangeEffectLinker.link(
            List.of(change("CREATE_RESOURCE", "Deployment/default/api")),
            List.of(warning("Pod", "default", "api-1-a", "Failed", 2),
                    warning("Pod", "default", "api-1-a", "BackOff", 3)));

        assertThat(effects).hasSize(2);
    }

    @Test
    void reportsEveryAffectedObjectRatherThanOnlyTheFirst() {
        var effects = ChangeEffectLinker.link(
            List.of(change("EDIT_RESOURCE_YAML", "Deployment/dev-team/api")),
            List.of(warning("Pod", "dev-team", "api-1-a", "Failed", 2),
                    warning("Pod", "dev-team", "api-1-b", "Failed", 4)));

        assertThat(effects).hasSize(2);
    }
}
