package com.kubemind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One section of a cluster's derived, historical "profile" (topology, workload
 * summary, incident patterns, recent changes, AI diagnosis history) — background
 * knowledge that makes AI answers cluster-aware. This is NOT a live state mirror:
 * live lookups always go straight to the Kubernetes API; this table only holds
 * summaries/trends the background job computes periodically. See ClusterProfileService.
 */
@Entity
@Table(name = "cluster_profiles")
public class ClusterProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "state_hash")
    private String stateHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClusterProfile() {} // JPA

    public ClusterProfile(Long clusterId, String section, String content, String stateHash) {
        this.clusterId = clusterId;
        this.section = section;
        this.content = content;
        this.stateHash = stateHash;
        this.updatedAt = Instant.now();
    }

    public void update(String content, String stateHash) {
        this.content = content;
        this.stateHash = stateHash;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getClusterId() { return clusterId; }
    public String getSection() { return section; }
    public String getContent() { return content; }
    public String getStateHash() { return stateHash; }
    public Instant getUpdatedAt() { return updatedAt; }
}
