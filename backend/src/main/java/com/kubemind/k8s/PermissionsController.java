package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the caller's kubeconfig may do in a namespace, so the UI can disable the
 * controls that would be refused instead of letting the user find out via a 403.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/permissions")
public class PermissionsController {

    private final PermissionsService permissionsService;

    public PermissionsController(PermissionsService permissionsService) {
        this.permissionsService = permissionsService;
    }

    @GetMapping
    public PermissionsService.NamespacePermissions permissions(@PathVariable long clusterId,
                                                               @RequestParam String namespace) {
        return permissionsService.forNamespace(clusterId, namespace);
    }

    public record TerminalPermissions(boolean nodeShell, boolean clusterTerminal) {}

    /** Whether this identity can actually open the Node Shell / Cluster Terminal —
     *  see PermissionsService for why these don't fit the namespace-scoped check above. */
    @GetMapping("/terminals")
    public TerminalPermissions terminals(@PathVariable long clusterId) {
        return new TerminalPermissions(
            permissionsService.canOpenNodeShell(clusterId),
            permissionsService.canOpenClusterTerminal(clusterId));
    }
}
