package com.kubemind.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Recent writes for one cluster — feeds the "changes" section of the AI cluster profile. */
    List<AuditLog> findTop10ByClusterIdOrderByCreatedAtDesc(Long clusterId);
}
