package com.kubemind.cluster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "clusters")
public class Cluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "kubeconfig_encrypted", nullable = false, columnDefinition = "text")
    private String kubeconfigEncrypted;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_check_ok")
    private Boolean lastCheckOk;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected Cluster() {} // JPA

    public Cluster(String name, String kubeconfigEncrypted, String createdBy) {
        this.name = name;
        this.kubeconfigEncrypted = kubeconfigEncrypted;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getKubeconfigEncrypted() { return kubeconfigEncrypted; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Boolean getLastCheckOk() { return lastCheckOk; }

    public void recordCheck(boolean ok) {
        this.lastCheckedAt = Instant.now();
        this.lastCheckOk = ok;
    }
}
