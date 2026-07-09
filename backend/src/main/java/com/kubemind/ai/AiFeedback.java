package com.kubemind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A thumbs-up/thumbs-down rating on an AI answer (Explain, Chat, ...). Deliberately
 * minimal for now: no free-text comment field, no linkage back to the exact prompt/
 * response text for surfaces that don't persist one (chat is stateless — see
 * ChatController). The eval harness and any future fine-tuning dataset (see
 * KubeMind-AI-Plan.md §4/A4) are what would consume this table.
 */
@Entity
@Table(name = "ai_feedback")
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(nullable = false)
    private String surface;

    @Column(name = "context_hash", nullable = false)
    private String contextHash;

    @Column(nullable = false)
    private String rating;

    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected AiFeedback() {} // JPA

    public AiFeedback(Long clusterId, String surface, String contextHash, String rating, String username) {
        this.clusterId = clusterId;
        this.surface = surface;
        this.contextHash = contextHash;
        this.rating = rating;
        this.username = username;
    }

    public Long getId() { return id; }
    public Long getClusterId() { return clusterId; }
    public String getSurface() { return surface; }
    public String getContextHash() { return contextHash; }
    public String getRating() { return rating; }
    public String getUsername() { return username; }
    public Instant getCreatedAt() { return createdAt; }
}
