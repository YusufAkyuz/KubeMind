package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RBAC visibility: ServiceAccounts, Roles, RoleBindings (namespaced) and
 * ClusterRoles, ClusterRoleBindings (cluster-scoped). Read-only by design —
 * see the "Access Control (RBAC)" section of KubernetesService for why these
 * kinds never go through the generic create/edit-YAML write paths.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class AccessControlController {

    private final KubernetesService kubernetesService;

    public AccessControlController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/serviceaccounts")
    public List<ServiceAccountDto> serviceAccounts(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listServiceAccounts(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/roles")
    public List<RoleDto> roles(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listRoles(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/rolebindings")
    public List<RoleBindingDto> roleBindings(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listRoleBindings(clusterId, ns);
    }

    /** ClusterRoles are cluster-scoped. */
    @GetMapping("/clusterroles")
    public List<ClusterRoleDto> clusterRoles(@PathVariable long clusterId) {
        return kubernetesService.listClusterRoles(clusterId);
    }

    /** ClusterRoleBindings are cluster-scoped. */
    @GetMapping("/clusterrolebindings")
    public List<ClusterRoleBindingDto> clusterRoleBindings(@PathVariable long clusterId) {
        return kubernetesService.listClusterRoleBindings(clusterId);
    }
}
