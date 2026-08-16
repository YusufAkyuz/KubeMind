package com.kubemind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One turn in a {@link ChatSession} — the user's question or the assistant's
 * answer, and nothing else. The prompt scaffolding around it (system prompt,
 * cluster snapshot, retrieved reference material) is rebuilt per turn and
 * never persisted; see V12__chat_sessions.sql for why.
 *
 * The id doubles as the {@code contextHash} for AI feedback, which is what
 * finally lets a thumbs-up be joined back to the answer it rated.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID sessionId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** Which model produced this answer. Null on user turns. */
    @Column(name = "model")
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    protected ChatMessage() {} // JPA

    public ChatMessage(UUID sessionId, String role, String content, String model) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.model = model;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
}
