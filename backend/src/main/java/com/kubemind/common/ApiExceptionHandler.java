package com.kubemind.common;

import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Invalid username or password"));
    }

    @ExceptionHandler(KubernetesClientException.class)
    public ResponseEntity<Map<String, String>> handleKubernetesError(KubernetesClientException ex) {
        if (ex.getCode() == HttpStatus.FORBIDDEN.value()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "The cluster denied this request (RBAC forbidden)."));
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
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Unexpected error: " + ex.getMessage()));
    }
}
