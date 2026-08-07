package com.kubemind.cluster;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The single gate in front of ~29 cluster-scoped controllers. It parses the
 * cluster id out of the path by hand, so both halves matter: letting the wrong
 * person through, and mistaking a non-cluster route (/api/clusters/pending) for
 * a cluster id.
 */
class ClusterAccessInterceptorTest {

    private ClusterRepository repository;
    private ClusterAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterRepository.class);
        interceptor = new ClusterAccessInterceptor(new ClusterAccessService(repository));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loggedInAs(String username, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static HttpServletRequest requestTo(String uri) {
        var request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    private boolean preHandle(String uri) {
        return interceptor.preHandle(requestTo(uri), null, null);
    }

    @Test
    void letsTheOwnerThrough() {
        loggedInAs("bob", "USER");
        when(repository.findById(7L))
            .thenReturn(Optional.of(new Cluster("prod", "enc", "bob", "APPROVED")));

        assertThat(preHandle("/api/clusters/7/namespaces/default/pods")).isTrue();
    }

    @Test
    void refusesSomeoneElsesClusterWith403() {
        loggedInAs("bob", "USER");
        when(repository.findById(7L))
            .thenReturn(Optional.of(new Cluster("prod", "enc", "alice", "APPROVED")));

        assertThatThrownBy(() -> preHandle("/api/clusters/7/namespaces/default/pods"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void refusesTheBuiltInClusterForANonAdmin() {
        loggedInAs("bob", "USER");

        assertThatThrownBy(() -> preHandle("/api/clusters/0/nodes"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void adminReachesAnything() {
        loggedInAs("admin", "ADMIN");

        assertThat(preHandle("/api/clusters/0/nodes")).isTrue();
        assertThat(preHandle("/api/clusters/99/namespaces/x/pods")).isTrue();
    }

    /** Fails closed rather than assuming an unauthenticated call is internal. */
    @Test
    void refusesWhenThereIsNoAuthentication() {
        assertThatThrownBy(() -> preHandle("/api/clusters/7/nodes"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ── Path parsing: these must NOT be read as a cluster id ─────────────────

    @Test
    void ignoresTheClusterListAndPendingRoutes() {
        loggedInAs("bob", "USER");

        assertThat(preHandle("/api/clusters")).isTrue();
        assertThat(preHandle("/api/clusters/pending")).isTrue();
    }

    @Test
    void ignoresRoutesOutsideTheClusterTree() {
        loggedInAs("bob", "USER");

        assertThat(preHandle("/api/auth/me")).isTrue();
        assertThat(preHandle("/api/users/3/password")).isTrue();
        assertThat(preHandle("/api/config")).isTrue();
        assertThat(preHandle("/actuator/health")).isTrue();
    }

    /**
     * The regression this guards against: a USER's own freshly-registered
     * cluster starts PENDING, and testing/deleting it is how they find out it's
     * reachable or withdraw a bad request — ClusterService's ownership checks
     * deliberately allow that regardless of status. This interceptor must not
     * add an APPROVED requirement on top and lock them out of their own
     * not-yet-approved cluster.
     */
    @Test
    void ignoresClusterControllersOwnActionsRegardlessOfStatus() {
        loggedInAs("bob", "USER");

        assertThat(preHandle("/api/clusters/7/test")).isTrue();
        assertThat(preHandle("/api/clusters/7/approve")).isTrue();
        assertThat(preHandle("/api/clusters/7/reject")).isTrue();
    }

    /** /api/clusters/{id} with nothing after it is the cluster resource itself,
     *  handled by ClusterController's own ownership checks. */
    @Test
    void ignoresTheBareClusterResourceRoute() {
        loggedInAs("bob", "USER");

        assertThat(preHandle("/api/clusters/7")).isTrue();
    }
}
