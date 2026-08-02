package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.authorization.v1.ResourceRule;
import io.fabric8.kubernetes.api.model.authorization.v1.ResourceRuleBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC is additive and wildcard-heavy; getting this matching wrong would either
 * hide controls the user does have or keep offering ones the cluster refuses.
 */
class PermissionsServiceTest {

    private static ResourceRule rule(List<String> verbs, List<String> groups, List<String> resources) {
        return new ResourceRuleBuilder()
            .withVerbs(verbs).withApiGroups(groups).withResources(resources).build();
    }

    @Test
    void matchesAnExactRule() {
        var rules = List.of(rule(List.of("create"), List.of("apps"), List.of("deployments")));

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "create")).isTrue();
    }

    @Test
    void doesNotMatchADifferentVerb() {
        var rules = List.of(rule(List.of("get", "list"), List.of("apps"), List.of("deployments")));

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "create")).isFalse();
    }

    @Test
    void doesNotMatchADifferentResource() {
        var rules = List.of(rule(List.of("create"), List.of("apps"), List.of("statefulsets")));

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "create")).isFalse();
    }

    /** cluster-admin looks like verbs:[*] apiGroups:[*] resources:[*]. */
    @Test
    void wildcardRuleAllowsEverything() {
        var rules = List.of(rule(List.of("*"), List.of("*"), List.of("*")));

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "delete")).isTrue();
        assertThat(PermissionsService.allows(rules, "", "secrets", "get")).isTrue();
    }

    /** Core-group resources carry an empty apiGroup, which must not be confused with "*". */
    @Test
    void coreGroupResourcesMatchOnEmptyApiGroup() {
        var rules = List.of(rule(List.of("create"), List.of(""), List.of("configmaps")));

        assertThat(PermissionsService.allows(rules, "", "configmaps", "create")).isTrue();
        assertThat(PermissionsService.allows(rules, "apps", "configmaps", "create")).isFalse();
    }

    /** Permission can come from any one of several rules. */
    @Test
    void additiveAcrossRules() {
        var rules = List.of(
            rule(List.of("get", "list", "watch"), List.of("*"), List.of("*")),
            rule(List.of("create", "delete"), List.of("apps"), List.of("deployments")));

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "delete")).isTrue();
        assertThat(PermissionsService.allows(rules, "apps", "statefulsets", "delete")).isFalse();
        assertThat(PermissionsService.allows(rules, "apps", "statefulsets", "list")).isTrue();
    }

    @Test
    void noRulesAllowsNothing() {
        assertThat(PermissionsService.allows(List.of(), "apps", "deployments", "create")).isFalse();
    }

    @Test
    void nullFieldsAreTreatedAsNoMatchRatherThanAWildcard() {
        var rules = List.of(new ResourceRuleBuilder().withVerbs("create").build());

        assertThat(PermissionsService.allows(rules, "apps", "deployments", "create")).isFalse();
    }

    /** Every kind the UI gates a control on needs an entry, or it can never be evaluated. */
    @Test
    void coversEveryCreatableKind() {
        assertThat(PermissionsService.knownKinds())
            .containsAll(ResourceCreationService.ALLOWED_KINDS);
    }
}
