package com.kubemind.cluster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClusterRepository extends JpaRepository<Cluster, Long> {
    Optional<Cluster> findByName(String name);
}
