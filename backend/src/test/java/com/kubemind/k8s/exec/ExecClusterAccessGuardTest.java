package com.kubemind.k8s.exec;

import com.kubemind.cluster.Cluster;
import com.kubemind.cluster.ClusterAccessService;
import com.kubemind.cluster.ImpersonationProperties;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ClusterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The terminal WebSockets take the cluster id from a query parameter the caller
 * controls, and /ws/** never passes through ClusterAccessInterceptor. Before
 * this guard existed, an authenticated USER could name someone else's cluster
 * and get a shell in it — ClusterClientFactory only checks that a cluster is
 * APPROVED, never who owns it.
 */
class ExecClusterAccessGuardTest {

    private ClusterRepository repository;
    private ExecClusterAccessGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        guard = new ExecClusterAccessGuard(new ClusterAccessService(repository, new ImpersonationProperties(false)));
    }

    private static WebSocketSession sessionFor(Principal principal) {
        var session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(principal);
        return session;
    }

    private static Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Cluster cluster(String owner) {
        return new Cluster("prod", "encrypted", owner, "APPROVED");
    }

    /** The escalation this closes. */
    @Test
    void userCannotOpenAShellOnSomeoneElsesCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("alice")));

        assertThat(guard.permits(sessionFor(auth("bob", "USER")), 7L)).isFalse();
    }

    @Test
    void userCanOpenAShellOnTheirOwnCluster() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("bob")));

        assertThat(guard.permits(sessionFor(auth("bob", "USER")), 7L)).isTrue();
    }

    @Test
    void adminCanOpenAShellAnywhere() {
        when(repository.findById(7L)).thenReturn(Optional.of(cluster("alice")));

        assertThat(guard.permits(sessionFor(auth("admin", "ADMIN")), 7L)).isTrue();
    }

    /** Omitting clusterId falls back to 0, the shared install identity. */
    @Test
    void userCannotReachTheBuiltInClusterByOmittingTheParameter() {
        assertThat(guard.permits(sessionFor(auth("bob", "USER")),
            ClusterClientFactory.DEFAULT_CLUSTER_ID)).isFalse();
    }

    @Test
    void sessionWithoutAnAuthenticationIsRefused() {
        assertThat(guard.permits(sessionFor(null), 7L)).isFalse();
    }

    /** A bare Principal that isn't a Spring Security Authentication proves nothing. */
    @Test
    void unrecognisedPrincipalIsRefused() {
        assertThat(guard.permits(sessionFor(() -> "bob"), 7L)).isFalse();
    }
}
