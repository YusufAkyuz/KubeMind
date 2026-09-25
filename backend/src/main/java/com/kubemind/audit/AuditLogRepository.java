package com.kubemind.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Recent writes for one cluster — feeds the "changes" section of the AI cluster profile. */
    List<AuditLog> findTop10ByClusterIdOrderByCreatedAtDesc(Long clusterId);

    /**
     * Changes recent enough that the cluster's own Events could still corroborate
     * them — Kubernetes discards Events after about an hour, so anything older
     * has nothing left to correlate against. Feeds ClusterProfileService's
     * change-effect linking.
     */
    List<AuditLog> findByClusterIdAndCreatedAtAfterOrderByCreatedAtDesc(Long clusterId, Instant since);
}
