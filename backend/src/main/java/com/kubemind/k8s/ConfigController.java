package com.kubemind.k8s;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class ConfigController {

    private final KubernetesService kubernetesService;
    private final SecretService secretService;

    public ConfigController(KubernetesService kubernetesService, SecretService secretService) {
        this.kubernetesService = kubernetesService;
        this.secretService = secretService;
    }

    @GetMapping("/namespaces/{ns}/configmaps")
    public List<ConfigMapDto> configMaps(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listConfigMaps(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/secrets")
    public List<SecretDto> secrets(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listSecrets(clusterId, ns);
    }

    /** Decoded Secret values: ADMIN-only, audited (REVEAL_SECRET). */
    @GetMapping("/namespaces/{ns}/secrets/{name}/reveal")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> reveal(@PathVariable long clusterId, @PathVariable String ns,
                                      @PathVariable String name, Authentication auth) {
        return secretService.reveal(auth.getName(), clusterId, ns, name);
    }
}
