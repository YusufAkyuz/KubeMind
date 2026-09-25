package com.kubemind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByUsernameAndClusterIdOrderByUpdatedAtDesc(String username, Long clusterId);

    /**
     * The only way this app looks a session up. Plain {@code findById} is
     * deliberately never called on sessions: pairing the id with the owner in
     * the query itself means a guessed or leaked id resolves to nothing rather
     * than to somebody else's conversation.
     */
    Optional<ChatSession> findByIdAndUsername(UUID id, String username);
}
