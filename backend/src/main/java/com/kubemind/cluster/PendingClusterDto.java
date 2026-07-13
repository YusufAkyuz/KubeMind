package com.kubemind.cluster;

import java.time.Instant;

/** ADMIN's minimal-exposure view of a cluster request awaiting a decision —
 *  no kubeconfig, no health data. See ClusterService#listPendingRequests. */
public record PendingClusterDto(long id, String name, String createdBy, Instant createdAt) {}
