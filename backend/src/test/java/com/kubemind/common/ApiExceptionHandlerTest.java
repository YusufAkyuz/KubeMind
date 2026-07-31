package com.kubemind.common;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone (no Spring context, no DB) check that login failures map to honest
 * status codes. The regression this locks down: only BadCredentialsException was
 * mapped, so any *other* authentication failure fell through to the generic
 * handler as a 500 — which the UI then showed as "Invalid username or password",
 * blaming the user for a server-side fault.
 */
class ApiExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/bad-credentials")
        String badCredentials() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/auth-infrastructure-down")
        String infrastructureDown() {
            throw new InternalAuthenticationServiceException(
                "Could not open JPA EntityManager", new RuntimeException("connection refused"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ThrowingController())
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(get("/bad-credentials"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    void authInfrastructureFailureIs503AndDoesNotBlameThePassword() throws Exception {
        mvc.perform(get("/auth-infrastructure-down"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("not your password")));
    }
}
