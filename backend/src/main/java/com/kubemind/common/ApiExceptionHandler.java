package com.kubemind.common;

import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Invalid username or password"));
    }

    /**
     * The login path failed for a reason that is NOT "wrong password":
     * DaoAuthenticationProvider wraps anything thrown out of the UserDetailsService
     * (a dropped DB connection, for instance) in this exception. Without this mapping
     * it fell through to the generic handler as an opaque 500, which the UI then
     * rendered as "Invalid username or password" — sending the user off to reset a
     * password that was never the problem. Log it loudly and say it's us, not them.
     */
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    public ResponseEntity<Map<String, String>> handleAuthInfrastructureFailure(
        InternalAuthenticationServiceException ex) {
        log.error("Authentication could not be completed (infrastructure failure)", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "Sign-in is temporarily unavailable. This is a server-side "
                + "problem, not your password — check the server logs."));
    }

    /** Any other authentication failure (disabled/locked account, ...). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Authentication failed"));
    }

    @ExceptionHandler(KubernetesClientException.class)
    public ResponseEntity<Map<String, String>> handleKubernetesError(KubernetesClientException ex) {
        if (ex.getCode() == HttpStatus.FORBIDDEN.value()) {
            // KubernetesRefusal keeps every fact the API server gave (who, verb,
            // resource, namespace) and puts them in a sentence, so nothing is lost
            // by not showing the raw wording. It also makes clear the limit is the
            // kubeconfig's rather than something KubeMind decided. Anything it
            // does not recognise falls through as the cluster wrote it.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", KubernetesRefusal.explain(kubernetesReason(ex))));
        }
        if (ex.getCode() == HttpStatus.NOT_FOUND.value()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "The resource no longer exists in the cluster."));
        }
        if (ex.getCode() == HttpStatus.CONFLICT.value()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "The resource already exists, or was changed by someone else. Reload and retry."));
        }
        if (ex.getCode() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "The cluster rejected the manifest: " + ex.getMessage()));
        }
        // code == 0 usually means the cluster was unreachable (connection refused, no kubeconfig, etc.).
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of("error", "Could not reach the Kubernetes cluster: " + ex.getMessage()));
    }

    // Thrown by @PreAuthorize inside MVC handlers; without this mapping the generic
    // handler below would report a 500 instead of the honest 403.
    /** The API server's explanation, or a usable fallback when it didn't give one. */
    private static String kubernetesReason(KubernetesClientException ex) {
        String message = ex.getStatus() != null && ex.getStatus().getMessage() != null
            ? ex.getStatus().getMessage()
            : ex.getMessage();
        return message == null || message.isBlank() ? "The cluster gave no further detail." : message;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "You need the ADMIN role for this action."));
    }

    // Without this, the generic Exception handler below would swallow deliberate
    // 404/400/503 responses thrown as ResponseStatusException and turn them into 500s.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
            .body(Map.of("error", ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex, HttpServletResponse response) {
        if (response.isCommitted()) {
            // A streaming response (chat/draft/analyze/edit-with-AI) already wrote content
            // under a non-JSON Content-Type before failing — writing a JSON body here would
            // itself throw (no converter for the committed Content-Type) and bury the real
            // error under a second, more confusing one. Those endpoints already write their
            // own in-band fallback message (see AiStreaming.writeFallbackSafely); nothing
            // more to do here.
            log.warn("Unhandled exception after the response was already committed: {}", ex.getMessage());
            return null;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Unexpected error: " + ex.getMessage()));
    }
}
