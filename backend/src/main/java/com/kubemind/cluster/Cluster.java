package com.kubemind.cluster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "clusters", uniqueConstraints = @UniqueConstraint(columnNames = {"created_by", "name"}))
public class Cluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique per owner, not globally — see V10 migration and ClusterService.create.
    @Column(nullable = false)
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

    /** PENDING (awaiting ADMIN review), APPROVED, or REJECTED. ADMIN-submitted
     *  clusters start APPROVED; USER-submitted ones start PENDING and are
     *  unusable (see ClusterClientFactory) until an ADMIN approves them. */
    @Column(nullable = false)
    private String status = "APPROVED";

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected Cluster() {} // JPA

    public Cluster(String name, String kubeconfigEncrypted, String createdBy, String status) {
        this.name = name;
        this.kubeconfigEncrypted = kubeconfigEncrypted;
        this.createdBy = createdBy;
        this.status = status;
    }

    /** Package-visible: lets tests stand in for what JPA would assign on insert. */
    void setId(Long id) {
        this.id = id;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getKubeconfigEncrypted() { return kubeconfigEncrypted; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Boolean getLastCheckOk() { return lastCheckOk; }
    public String getStatus() { return status; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }

    public void recordCheck(boolean ok) {
        this.lastCheckedAt = Instant.now();
        this.lastCheckOk = ok;
    }

    public void approve(String reviewer) {
        this.status = "APPROVED";
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    public void reject(String reviewer) {
        this.status = "REJECTED";
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }
}
