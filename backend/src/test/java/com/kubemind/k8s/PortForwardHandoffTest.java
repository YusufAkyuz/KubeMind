package com.kubemind.k8s;

import com.kubemind.audit.AuditService;
import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.LogoutSuccessEvent;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On a session's own origin the owner's KubeMind login never arrives, so the
 * only thing that admits a browser there is the one-time link KubeMind handed
 * the owner. These pin down what that link is worth.
 */
class PortForwardHandoffTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    private PortForwardService service;
    private LocalPortForward forward;

    @BeforeEach
    void setUp() {
        service = new PortForwardService(mock(ClusterClientFactory.class), mock(AuditService.class),
            new PortForwardProperties("pf.kubemind.my.kubernetes", "http", null), clock);
        forward = mock(LocalPortForward.class);
        when(forward.getLocalAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(forward.getLocalPort()).thenReturn(40000);
    }

    @Test
    void opensOnTheSessionsOwnOriginThroughAHandoffLink() {
        var opened = service.registerSession("alice", 0L, forward, false);

        URI url = URI.create(opened.url());
        assertThat(url.getScheme()).isEqualTo("http");
        assertThat(url.getHost()).isEqualTo(opened.sessionId() + ".pf.kubemind.my.kubernetes");
        assertThat(url.getPath()).isEqualTo(ForwardHostFilter.HANDOFF_PATH);
        assertThat(token(opened)).hasSizeGreaterThanOrEqualTo(43); // 32 random bytes
    }

    @Test
    void opensUnderThePathPrefixWhenNoDomainIsConfigured() {
        var pathMode = new PortForwardService(mock(ClusterClientFactory.class), mock(AuditService.class),
            new PortForwardProperties("", "http", null), clock);

        var opened = pathMode.registerSession("alice", 0L, forward, false);

        assertThat(opened.url()).isEqualTo("/api/port-forward/" + opened.sessionId() + "/");
        assertThat(pathMode.redeemHandoff(opened.sessionId(), "anything")).isNull();
    }

    @Test
    void theLinkTradesForACookieSecretThatAdmitsTheBrowser() {
        var opened = service.registerSession("alice", 0L, forward, true);

        String secret = service.redeemHandoff(opened.sessionId(), token(opened));

        assertThat(secret).isNotBlank();
        var target = service.touchWithSecret(opened.sessionId(), secret);
        assertThat(target).isNotNull();
        assertThat(target.port()).isEqualTo(40000);
        assertThat(target.tls()).isTrue();
    }

    @Test
    void theLinkWorksOnlyOnce() {
        var opened = service.registerSession("alice", 0L, forward, false);
        String first = service.redeemHandoff(opened.sessionId(), token(opened));

        assertThat(service.redeemHandoff(opened.sessionId(), token(opened))).isNull();
        // The browser that redeemed it first keeps working.
        assertThat(service.touchWithSecret(opened.sessionId(), first)).isNotNull();
    }

    @Test
    void theLinkExpires() {
        var opened = service.registerSession("alice", 0L, forward, false);

        clock.advance(PortForwardService.HANDOFF_TTL.plusSeconds(1));

        assertThat(service.redeemHandoff(opened.sessionId(), token(opened))).isNull();
    }

    @Test
    void aWrongTokenOrAnotherSessionsTokenAdmitsNothing() {
        var mine = service.registerSession("alice", 0L, forward, false);
        var other = service.registerSession("bob", 0L, forward, false);

        assertThat(service.redeemHandoff(mine.sessionId(), "not-the-token")).isNull();
        assertThat(service.redeemHandoff(mine.sessionId(), token(other))).isNull();
        assertThat(service.redeemHandoff(mine.sessionId(), null)).isNull();
        assertThat(service.redeemHandoff("no-such-session", token(mine))).isNull();
        // A failed attempt doesn't burn the real link.
        assertThat(service.redeemHandoff(mine.sessionId(), token(mine))).isNotNull();
    }

    @Test
    void withoutRedeemingTheLinkNoSecretAdmitsAnything() {
        var opened = service.registerSession("alice", 0L, forward, false);

        assertThat(service.touchWithSecret(opened.sessionId(), null)).isNull();
        assertThat(service.touchWithSecret(opened.sessionId(), "")).isNull();
        assertThat(service.touchWithSecret(opened.sessionId(), token(opened))).isNull();
    }

    @Test
    void signingOutOfKubemindClosesThatUsersTunnels() throws Exception {
        var alices = service.registerSession("alice", 0L, forward, false);
        String secret = service.redeemHandoff(alices.sessionId(), token(alices));
        LocalPortForward bobsForward = mock(LocalPortForward.class);
        var bobs = service.registerSession("bob", 0L, bobsForward, false);

        service.onLogout(new LogoutSuccessEvent(new TestingAuthenticationToken("alice", null)));

        assertThat(service.touchWithSecret(alices.sessionId(), secret)).isNull();
        verify(forward).close();
        assertThat(service.touch(bobs.sessionId(), "bob")).isNotNull();
    }

    private static String token(PortForwardService.OpenedSession opened) {
        return URI.create(opened.url()).getQuery().substring("token=".length());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
