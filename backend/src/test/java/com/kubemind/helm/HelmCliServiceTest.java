package com.kubemind.helm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * helm exits non-zero for very different reasons and they used to collapse into
 * a single 502 — which told people the cluster was unreachable when it had in
 * fact answered, refusing the request.
 */
class HelmCliServiceTest {

    /** Verbatim from a kubeconfig scoped to a ServiceAccount without secret access. */
    @Test
    void rbacRefusalIsForbiddenNotBadGateway() {
        String stderr = "Error: list: failed to list: secrets is forbidden: User "
            + "\"system:serviceaccount:dev-team:kubemind-developer\" cannot list resource "
            + "\"secrets\" in API group \"\" in the namespace \"dev-team\"";

        assertThat(HelmCliService.statusFor(stderr)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredOrRejectedCredentialsAreUnauthorized() {
        assertThat(HelmCliService.statusFor(
            "Error: Kubernetes cluster unreachable: the server has asked for the client to provide credentials"))
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMissingReleaseIsNotFound() {
        assertThat(HelmCliService.statusFor("Error: release: not found"))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** An actually unreachable cluster keeps the honest 502. */
    @Test
    void connectionFailureStaysBadGateway() {
        assertThat(HelmCliService.statusFor(
            "Error: Kubernetes cluster unreachable: Get \"https://127.0.0.1:6443/version\": "
            + "dial tcp 127.0.0.1:6443: connect: connection refused"))
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void anythingUnrecognisedStaysBadGateway() {
        assertThat(HelmCliService.statusFor("Error: something we've never seen"))
            .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
