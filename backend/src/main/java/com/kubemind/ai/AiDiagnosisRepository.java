package com.kubemind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiDiagnosisRepository extends JpaRepository<AiDiagnosis, Long> {
    Optional<AiDiagnosis> findFirstByStateHash(String stateHash);
}
