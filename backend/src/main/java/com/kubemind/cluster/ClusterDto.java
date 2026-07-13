package com.kubemind.cluster;

import java.time.Instant;

/** Public view of a cluster. The kubeconfig NEVER leaves the server. */
public record ClusterDto(
    long id,
    String name,
    boolean builtIn,
    String createdBy,
    Instant createdAt,
    Instant lastCheckedAt,
    Boolean lastCheckOk,
    String status
) {}
