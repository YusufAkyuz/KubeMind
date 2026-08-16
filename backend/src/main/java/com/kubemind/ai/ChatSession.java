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
 * One saved conversation, owned by exactly one user and pinned to one cluster.
 *
 * Ownership is the whole point of this table: every read path goes through
 * {@code findByIdAndUsername}, never {@code findById}, so one account can
 * never surface another's transcript — including ADMIN, deliberately (see
 * ChatSessionService).
 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Bumped on every turn so the history list sorts by recency, not creation. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    protected ChatSession() {} // JPA

    public ChatSession(String username, Long clusterId, String title) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.clusterId = clusterId;
        this.title = title;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** Deliberately does not touch updatedAt — renaming a chat is not activity in it. */
    public void rename(String title) {
        this.title = title;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public Long getClusterId() { return clusterId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
