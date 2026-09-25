package com.kubemind.ai;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** Full transcript, oldest first — what the history panel renders. */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /** Newest first with a limit, so a long session doesn't load entirely just
     *  to build a bounded prompt window. The caller reverses it. */
    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);
}
