package com.kubemind.auth.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Resolves the OIDC registration on first use instead of at startup.
 *
 * OIDC discovery is an HTTP call to the identity provider. Making it while the
 * Spring context is coming up means an unreachable IdP fails a bean, fails the
 * context, and the application never starts — taking local username/password
 * login down with it. That login exists precisely for when SSO is broken, so
 * tying its availability to the IdP's defeats the point. Deferring the call to
 * the moment someone actually clicks "Sign in with SSO" keeps an IdP outage
 * confined to SSO.
 *
 * Success is cached for the life of the process (a registration doesn't
 * change). Failure is deliberately NOT cached: the next attempt retries, so an
 * IdP that comes back needs no restart here. Nothing calls this per-request —
 * only the authorization redirect, the callback and logout do — so retrying
 * costs nothing worth optimising away.
 */
class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(LazyClientRegistrationRepository.class);

    /**
     * A refused connection fails immediately; a black-holed one never would.
     * ClientRegistrations offers no hook for RestOperations timeouts, so the
     * bound lives on this side of the call instead.
     */
    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    private final String registrationId;
    private final Supplier<ClientRegistration> discovery;
    private final Duration timeout;
    private volatile ClientRegistration resolved;
    /** The one in-flight attempt, so concurrent clicks share a call rather than each starting one. */
    private final AtomicReference<CompletableFuture<ClientRegistration>> inFlight = new AtomicReference<>();

    LazyClientRegistrationRepository(String registrationId, Supplier<ClientRegistration> discovery) {
        this(registrationId, discovery, DISCOVERY_TIMEOUT);
    }

    /** Timeout parameterised so the hang case is testable in milliseconds rather than seconds. */
    LazyClientRegistrationRepository(String registrationId, Supplier<ClientRegistration> discovery, Duration timeout) {
        this.registrationId = registrationId;
        this.discovery = discovery;
        this.timeout = timeout;
    }

    /**
     * @return the registration, or null when the id is unknown or the IdP could
     *         not be reached. Callers must treat null as "SSO is unavailable
     *         right now" — see SsoAvailabilityFilter and SecurityConfig's
     *         logout handler, which turn it into a message rather than a 500.
     */
    @Override
    public ClientRegistration findByRegistrationId(String id) {
        if (!registrationId.equals(id)) {
            return null;
        }
        ClientRegistration cached = resolved;
        if (cached != null) {
            return cached;
        }
        return discover();
    }

    private ClientRegistration discover() {
        CompletableFuture<ClientRegistration> attempt = inFlight.updateAndGet(
            existing -> existing != null ? existing : CompletableFuture.supplyAsync(discovery));
        try {
            ClientRegistration registration =
                attempt.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            resolved = registration;
            inFlight.set(null);
            return registration;
        } catch (TimeoutException e) {
            // The attempt is abandoned, not cancelled: the underlying HTTP call
            // has no interruptible seam, so one thread may linger until the
            // socket gives up. Clearing it lets the next click start fresh.
            inFlight.set(null);
            log.warn("OIDC discovery did not answer within {}s — treating SSO as unavailable. "
                + "Local login is unaffected.", timeout.toSeconds());
            return null;
        } catch (Exception e) {
            inFlight.set(null);
            log.warn("OIDC discovery failed — treating SSO as unavailable, local login is unaffected: {}",
                rootMessage(e));
            return null;
        }
    }

    /** CompletableFuture wraps the real cause; the wrapper's message is noise in a log line. */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
