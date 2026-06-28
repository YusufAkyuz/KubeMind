package com.kubemind.k8s;

public record NamespaceDto(String name, String phase, String creationTimestamp) {
}
