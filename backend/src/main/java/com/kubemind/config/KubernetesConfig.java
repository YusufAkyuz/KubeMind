package com.kubemind.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfig {

    /**
     * Phase 0: auto-configures from the ambient kubeconfig (~/.kube/config) or an
     * in-cluster service account. Multi-cluster support and stored, encrypted
     * kubeconfigs arrive in a later phase (see KubeMind-MVP-Plan.md).
     */
    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
