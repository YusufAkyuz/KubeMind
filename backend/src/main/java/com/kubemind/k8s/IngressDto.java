package com.kubemind.k8s;

import java.util.List;

public record IngressDto(
    String name,
    String namespace,
    String className,
    List<Rule> rules,
    String creationTimestamp
) {
    public record Rule(String host, String path, String backend) {}
}
