package com.kubemind.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_RESULT_LEN = 512;

    private final AuditLogRepository repository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository,
                        UserRepository userRepository,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records a write-action attempt. REQUIRES_NEW so the audit row survives even
     * if the caller's transaction rolls back — an audit trail that disappears with
     * the failure it should document is useless.
     *
     * Never throws: an audit failure must not mask the action's own outcome,
     * but it is logged loudly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String action, String resourceRef,
                       Map<String, Object> payload, boolean success, String message) {
        try {
            Long userId = userRepository.findByUsername(username)
                .map(u -> u.getId())
                .orElse(null);

            String payloadJson = null;
            if (payload != null && !payload.isEmpty()) {
                try {
                    payloadJson = objectMapper.writeValueAsString(payload);
                } catch (JsonProcessingException e) {
                    payloadJson = "{\"_serializationError\":true}";
                }
            }

            String result = success ? "SUCCESS" : truncate("FAILED: " + message);
            repository.save(new AuditLog(userId, username, action, resourceRef, payloadJson, result));
        } catch (Exception e) {
            log.error("AUDIT WRITE FAILED action={} resource={} user={} - {}",
                action, resourceRef, username, e.getMessage());
        }
    }

    private String truncate(String s) {
        return s.length() > MAX_RESULT_LEN ? s.substring(0, MAX_RESULT_LEN - 1) + "…" : s;
    }
}
