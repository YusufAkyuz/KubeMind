package com.kubemind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin-authored runbook — RAG corpus 2 (Phase A3), always scoped to one
 * cluster. This table is the source of truth for the admin UI; the matching
 * {@code vector_store} row (same id) holds only the embedding for retrieval.
 */
@Entity
@Table(name = "runbooks")
public class Runbook {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected Runbook() {} // JPA

    public Runbook(Long clusterId, String title, String content, String createdBy) {
        this.id = UUID.randomUUID();
        this.clusterId = clusterId;
        this.title = title;
        this.content = content;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public Long getClusterId() { return clusterId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
