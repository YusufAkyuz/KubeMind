package com.kubemind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackService.class);

    private final AiFeedbackRepository repository;

    public AiFeedbackService(AiFeedbackRepository repository) {
        this.repository = repository;
    }

    // REQUIRES_NEW mirrors AuditService: a feedback write is a side effect of viewing
    // an AI answer, not part of any surrounding transaction, and must never fail loudly
    // enough to break the page the user is rating something on.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long clusterId, String surface, String contextHash, String rating, String username) {
        try {
            repository.save(new AiFeedback(clusterId, surface, contextHash, rating, username));
        } catch (Exception e) {
            log.warn("AI feedback write failed surface={} user={} - {}", surface, username, e.getMessage());
        }
    }
}
