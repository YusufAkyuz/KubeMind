package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Port-forwarding used to be ADMIN-only end to end, so the proxy never had to
 * ask who a session belonged to. Opening it up to whoever may write on the
 * cluster removes that cover: /api/port-forward/{id}/** carries no cluster id,
 * and the session id would otherwise be enough for any logged-in user to ride
 * someone else's tunnel straight into a cluster they cannot reach.
 */
@EnableKubernetesMockClient(crud = true)
class PortForwardSessionOwnershipTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private PortForwardService service;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        service = new PortForwardService(factory, mock(AuditService.class));
    }

    /**
     * Registers a session directly: opening one for real needs a live pod and a
     * working tunnel, and what's under test is the ownership check, not the tunnel.
     */
    private void givenSessionOwnedBy(String sessionId, String owner) throws Exception {
        Class<?> sessionClass = Class.forName("com.kubemind.k8s.PortForwardService$Session");
        var ctor = sessionClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object session = ctor.newInstance(owner, 7L, null, new AtomicReference<>(Instant.now()));

        Field sessions = PortForwardService.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) sessions.get(service);
        map.put(sessionId, session);
    }

    @Test
    void anotherUserCannotProxyThroughSomeoneElsesSession() throws Exception {
        givenSessionOwnedBy("s1", "alice");

        assertThat(service.touch("s1", "bob")).isNull();
    }

    @Test
    void anotherUserCannotCloseSomeoneElsesSession() throws Exception {
        givenSessionOwnedBy("s1", "alice");

        service.close("bob", "s1");

        // Still there for its owner — bob's attempt changed nothing.
        assertThat(sessionExists("s1")).isTrue();
    }

    @Test
    void anUnknownSessionIsIndistinguishableFromOneYouDoNotOwn() throws Exception {
        givenSessionOwnedBy("s1", "alice");

        assertThat(service.touch("s1", "bob")).isNull();
        assertThat(service.touch("does-not-exist", "bob")).isNull();
    }

    private boolean sessionExists(String sessionId) throws Exception {
        Field sessions = PortForwardService.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) sessions.get(service);
        return map.containsKey(sessionId);
    }
}
