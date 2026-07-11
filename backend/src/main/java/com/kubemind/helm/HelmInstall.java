package com.kubemind.helm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Remembers which chart reference a Helm release was installed from via
 * KubeMind — see the migration comment (V8__helm_installs.sql) for why this
 * one piece of provenance is worth persisting despite the "never mirror live
 * state" rule.
 */
@Entity
@Table(name = "helm_installs")
public class HelmInstall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false)
    private long clusterId;

    @Column(nullable = false)
    private String namespace;

    @Column(name = "release_name", nullable = false)
    private String releaseName;

    @Column(name = "chart_ref", nullable = false)
    private String chartRef;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    protected HelmInstall() {} // JPA

    public HelmInstall(long clusterId, String namespace, String releaseName, String chartRef) {
        this.clusterId = clusterId;
        this.namespace = namespace;
        this.releaseName = releaseName;
        this.chartRef = chartRef;
    }

    public String getChartRef() { return chartRef; }
    public void setChartRef(String chartRef) { this.chartRef = chartRef; }
}
