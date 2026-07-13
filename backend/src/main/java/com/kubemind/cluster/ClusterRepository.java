package com.kubemind.cluster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClusterRepository extends JpaRepository<Cluster, Long> {
    List<Cluster> findByCreatedBy(String createdBy);

    Optional<Cluster> findByCreatedByAndName(String createdBy, String name);

    List<Cluster> findByStatus(String status);
}
