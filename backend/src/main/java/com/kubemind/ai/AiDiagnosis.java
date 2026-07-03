package com.kubemind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ai_diagnoses")
public class AiDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(name = "resource_kind", nullable = false)
    private String resourceKind;

    @Column(name = "resource_ns", nullable = false)
    private String resourceNs;

    @Column(name = "resource_name", nullable = false)
    private String resourceName;

    @Column(name = "state_hash", nullable = false, unique = true)
    private String stateHash;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(nullable = false, columnDefinition = "text")
    private String response;

    @Column(nullable = false)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected AiDiagnosis() {} // JPA

    public AiDiagnosis(Long clusterId, String resourceKind, String resourceNs, String resourceName,
                       String stateHash, String prompt, String response, String model) {
        this.clusterId = clusterId;
        this.resourceKind = resourceKind;
        this.resourceNs = resourceNs;
        this.resourceName = resourceName;
        this.stateHash = stateHash;
        this.prompt = prompt;
        this.response = response;
        this.model = model;
    }

    public Long getId() { return id; }
    public String getResourceKind() { return resourceKind; }
    public String getResourceNs() { return resourceNs; }
    public String getResourceName() { return resourceName; }
    public String getStateHash() { return stateHash; }
    public String getPrompt() { return prompt; }
    public String getResponse() { return response; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
}
