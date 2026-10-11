package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Port-forwarding used to be ADMIN-only end to end, so the proxy never had to
 * ask who a session belonged to. Opening it up to whoever may write on the
 * cluster removes that cover: /api/port-forward/{id}/** carries no cluster id,
 * and the session id would otherwise be enough for any logged-in user to ride
 * someone else's tunnel straight into a cluster they cannot reach.
 */
class PortForwardSessionOwnershipTest {

    private PortForwardService service;

    @BeforeEach
    void setUp() {
        service = new PortForwardService(mock(ClusterClientFactory.class), mock(AuditService.class),
            new PortForwardProperties("", "http", null));
    }

    /** Registers a session directly: opening one for real needs a live pod and a working tunnel. */
    private String givenSessionOwnedBy(String owner) {
        LocalPortForward forward = mock(LocalPortForward.class);
        when(forward.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(forward.getLocalPort()).thenReturn(40000);
        return service.registerSession(owner, 7L, forward, false).sessionId();
    }

    @Test
    void theOwnerCanProxyThroughTheirSession() {
        String id = givenSessionOwnedBy("alice");

        assertThat(service.touch(id, "alice")).isNotNull();
    }

    @Test
    void anotherUserCannotProxyThroughSomeoneElsesSession() {
        String id = givenSessionOwnedBy("alice");

        assertThat(service.touch(id, "bob")).isNull();
    }

    @Test
    void anotherUserCannotCloseSomeoneElsesSession() {
        String id = givenSessionOwnedBy("alice");

        service.close("bob", id);

        // Still there for its owner — bob's attempt changed nothing.
        assertThat(service.touch(id, "alice")).isNotNull();
    }

    @Test
    void anUnknownSessionIsIndistinguishableFromOneYouDoNotOwn() {
        String id = givenSessionOwnedBy("alice");

        assertThat(service.touch(id, "bob")).isNull();
        assertThat(service.touch("does-not-exist", "bob")).isNull();
    }
}
