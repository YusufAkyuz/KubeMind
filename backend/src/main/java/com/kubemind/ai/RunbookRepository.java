package com.kubemind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunbookRepository extends JpaRepository<Runbook, UUID> {
    List<Runbook> findByClusterIdOrderByCreatedAtDesc(Long clusterId);
}
