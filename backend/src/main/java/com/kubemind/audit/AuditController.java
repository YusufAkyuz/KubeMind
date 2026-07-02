package com.kubemind.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record AuditEntryDto(Long id, String username, String action, String resourceRef,
                                String payload, String result, Instant createdAt) {}

    public record AuditPageDto(List<AuditEntryDto> entries, int page, int totalPages, long totalElements) {}

    @GetMapping
    public AuditPageDto list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = repository.findAllByOrderByCreatedAtDesc(
            PageRequest.of(Math.max(page, 0), clampedSize));
        var entries = result.getContent().stream()
            .map(a -> new AuditEntryDto(a.getId(), a.getUsername(), a.getAction(),
                a.getResourceRef(), a.getPayload(), a.getResult(), a.getCreatedAt()))
            .toList();
        return new AuditPageDto(entries, result.getNumber(), result.getTotalPages(),
            result.getTotalElements());
    }
}
