package com.kubemind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClusterProfileRepository extends JpaRepository<ClusterProfile, Long> {
    Optional<ClusterProfile> findByClusterIdAndSection(Long clusterId, String section);
    List<ClusterProfile> findByClusterId(Long clusterId);
}
