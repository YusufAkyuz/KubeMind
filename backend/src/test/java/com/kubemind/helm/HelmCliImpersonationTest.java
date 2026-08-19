package com.kubemind.helm;

import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ImpersonationProperties;
import com.kubemind.cluster.ImpersonationResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Impersonation being on is exactly what opens the built-in cluster to
 * non-admins (ClusterAccessService.canRead) — so it is the mode in which a helm
 * subprocess running as the installation's own ServiceAccount (cluster-admin in
 * a default install) would have handed any authenticated user the run of every
 * release in the cluster, while the rest of the app was carefully scoping them.
 *
 * Asserts on the command that gets built rather than on running helm: the
 * binary is not on every machine, and the flags are the whole point.
 */
class HelmCliImpersonationTest {

    private final ClusterClientFactory clientFactory = mock(ClusterClientFactory.class);
    private final ImpersonationResolver resolver = mock(ImpersonationResolver.class);

    private HelmCliService withImpersonation(boolean enabled) {
        return new HelmCliService(clientFactory, new ImpersonationProperties(enabled), resolver);
    }

    private List<String> command(HelmCliService service, long clusterId) {
        List<String> command = new ArrayList<>(List.of("helm", "list"));
        service.addImpersonation(command, clusterId);
        return command;
    }

    @Test
    void passesTheCallersUserAndGroupsToHelm() {
        when(resolver.currentIdentity()).thenReturn(
            Optional.of(new ImpersonationResolver.Identity("alice", List.of("devs", "oncall"))));

        assertThat(command(withImpersonation(true), ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .containsSequence("--kube-as-user", "alice")
            .containsSequence("--kube-as-group", "devs")
            .containsSequence("--kube-as-group", "oncall");
    }

    /** Local accounts carry no groups; the user flag still has to go. */
    @Test
    void passesTheUserEvenWithoutGroups() {
        when(resolver.currentIdentity()).thenReturn(
            Optional.of(new ImpersonationResolver.Identity("admin", List.of())));

        assertThat(command(withImpersonation(true), ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .containsSequence("--kube-as-user", "admin")
            .doesNotContain("--kube-as-group");
    }

    /**
     * Running as the ServiceAccount because the caller could not be named is the
     * exact failure this exists to prevent, so it fails rather than falls back.
     */
    @Test
    void refusesToRunAsTheServiceAccountWhenTheCallerCannotBeIdentified() {
        when(resolver.currentIdentity()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> command(withImpersonation(true), ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("could not be identified");
    }

    /**
     * With impersonation off the built-in cluster is ADMIN-only, so acting as the
     * installation identity is the intended behaviour, not an escalation.
     */
    @Test
    void doesNotImpersonateWhenTheFeatureIsOff() {
        assertThat(command(withImpersonation(false), ClusterClientFactory.DEFAULT_CLUSTER_ID))
            .containsExactly("helm", "list");
    }

    /**
     * A registered cluster is reached through its owner's own kubeconfig, which
     * already is their identity — impersonating on top would name a user the
     * target cluster has never heard of.
     */
    @Test
    void leavesRegisteredClustersToTheirOwnKubeconfig() {
        assertThat(command(withImpersonation(true), 7L)).containsExactly("helm", "list");
    }
}
