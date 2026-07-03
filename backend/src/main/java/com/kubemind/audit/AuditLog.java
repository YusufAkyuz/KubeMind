package com.kubemind.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String username;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_ref", nullable = false)
    private String resourceRef;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected AuditLog() {} // JPA

    public AuditLog(Long userId, String username, Long clusterId, String action,
                    String resourceRef, String payload, String result) {
        this.userId = userId;
        this.username = username;
        this.clusterId = clusterId;
        this.action = action;
        this.resourceRef = resourceRef;
        this.payload = payload;
        this.result = result;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public Long getClusterId() { return clusterId; }
    public String getAction() { return action; }
    public String getResourceRef() { return resourceRef; }
    public String getPayload() { return payload; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
}
