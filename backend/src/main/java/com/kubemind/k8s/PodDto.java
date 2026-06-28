package com.kubemind.k8s;

import java.util.List;

public record PodDto(
    String name,
    String namespace,
    String phase,
    String nodeName,
    int restartCount,
    String podIP,
    String creationTimestamp,
    List<ContainerInfo> containers
) {
    public record ContainerInfo(String name, String image, boolean ready, int restartCount) {}
}
