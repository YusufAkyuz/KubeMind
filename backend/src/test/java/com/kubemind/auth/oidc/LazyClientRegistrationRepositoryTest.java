package com.kubemind.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behaviour that keeps an IdP outage from becoming a KubeMind outage:
 * discovery must be able to fail without throwing, must not poison itself when
 * it does, and must not block forever when the IdP accepts connections but
 * never answers.
 */
class LazyClientRegistrationRepositoryTest {

    private static ClientRegistration aRegistration() {
        return ClientRegistration.withRegistrationId("oidc")
            .clientId("kubemind")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/oidc")
            .authorizationUri("https://idp.example.com/auth")
            .tokenUri("https://idp.example.com/token")
            .build();
    }

    @Test
    void resolvesAndReturnsTheRegistration() {
        var repo = new LazyClientRegistrationRepository("oidc", LazyClientRegistrationRepositoryTest::aRegistration);

        assertThat(repo.findByRegistrationId("oidc")).isNotNull();
    }

    @Test
    void anUnknownRegistrationIdIsNotDiscovered() {
        var calls = new AtomicInteger();
        var repo = new LazyClientRegistrationRepository("oidc", () -> {
            calls.incrementAndGet();
            return aRegistration();
        });

        assertThat(repo.findByRegistrationId("something-else")).isNull();
        assertThat(calls).hasValue(0);
    }

    /** A registration doesn't change, so the IdP is asked once and no more. */
    @Test
    void successIsCached() {
        var calls = new AtomicInteger();
        var repo = new LazyClientRegistrationRepository("oidc", () -> {
            calls.incrementAndGet();
            return aRegistration();
        });

        repo.findByRegistrationId("oidc");
        repo.findByRegistrationId("oidc");
        repo.findByRegistrationId("oidc");

        assertThat(calls).hasValue(1);
    }

    /**
     * The headline: an unreachable IdP produces null, not an exception. Anything
     * that throws here propagates out of a servlet filter as a 500 whitelabel
     * page — and, at startup, used to stop the application booting at all.
     */
    @Test
    void anUnreachableIdpYieldsNullRatherThanThrowing() {
        var repo = new LazyClientRegistrationRepository("oidc", () -> {
            throw new IllegalStateException("Connection refused");
        });

        assertThat(repo.findByRegistrationId("oidc")).isNull();
    }

    /** An IdP that comes back must start working without restarting KubeMind. */
    @Test
    void failureIsNotCachedSoTheNextAttemptRetries() {
        var attempts = new AtomicInteger();
        var repo = new LazyClientRegistrationRepository("oidc", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Connection refused");
            }
            return aRegistration();
        });

        assertThat(repo.findByRegistrationId("oidc")).isNull();
        assertThat(repo.findByRegistrationId("oidc")).isNull();
        assertThat(repo.findByRegistrationId("oidc")).isNotNull();
        assertThat(attempts).hasValue(3);
    }

    /**
     * A black-holed IdP accepts the connection and never answers. Without a
     * bound the request thread would wait on it indefinitely; the click has to
     * come back with "unavailable" instead.
     */
    @Test
    void aHangingIdpIsAbandonedAtTheTimeout() throws Exception {
        var release = new CountDownLatch(1);
        // Milliseconds, not the production 10s — the point under test is that a
        // bound exists and is honoured, not what its value happens to be.
        var repo = new LazyClientRegistrationRepository("oidc", () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return aRegistration();
        }, Duration.ofMillis(200));

        long startedAt = System.nanoTime();
        ClientRegistration result = repo.findByRegistrationId("oidc");
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result).isNull();
        assertThat(waited).isLessThan(Duration.ofSeconds(5));
        release.countDown();
    }
}
