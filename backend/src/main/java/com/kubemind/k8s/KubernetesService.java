package com.kubemind.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KubernetesService {

    private final KubernetesClient client;

    public KubernetesService(KubernetesClient client) {
        this.client = client;
    }

    public List<NamespaceDto> listNamespaces() {
        return client.namespaces().list().getItems().stream()
            .map(ns -> new NamespaceDto(
                ns.getMetadata().getName(),
                ns.getStatus() != null ? ns.getStatus().getPhase() : null,
                ns.getMetadata().getCreationTimestamp()))
            .toList();
    }
}
