package com.kubemind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiDiagnosisRepository extends JpaRepository<AiDiagnosis, Long> {
    Optional<AiDiagnosis> findFirstByStateHash(String stateHash);

    /** Most recent diagnosis for this resource identity, regardless of state — used to tell
     *  the model "here's what we last found for this same resource" when the state changed. */
    Optional<AiDiagnosis> findFirstByClusterIdAndResourceKindAndResourceNsAndResourceNameOrderByCreatedAtDesc(
        Long clusterId, String resourceKind, String resourceNs, String resourceName);

    /** Recent diagnoses across the whole cluster — feeds the "ai_history" section of the cluster profile. */
    List<AiDiagnosis> findTop10ByClusterIdOrderByCreatedAtDesc(Long clusterId);
}
