package com.kubemind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Saved chat history, scoped to one user and one cluster.
 *
 * Two rules shape everything here:
 *
 * 1. <b>A session is only ever reachable by its owner.</b> Every lookup pairs
 *    the id with the username, and a miss is reported as 404 rather than 403 —
 *    a 403 would confirm that someone else's session exists at that id. This
 *    holds for ADMIN too: admins administer clusters, they do not read other
 *    people's conversations. Making that possible would be a separate, audited
 *    feature, not a side effect of a role check.
 *
 * 2. <b>Only the turns are stored.</b> Never the system prompt or the live
 *    cluster snapshot built around it — see V12__chat_sessions.sql.
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** Turns fed back to the model. Matches the window the stateless version used. */
    static final int MAX_HISTORY = 12;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * The session, the bounded chronological history to prompt the model with,
     * and the id the answer will be stored under once it finishes streaming.
     *
     * That last one is reserved rather than inserted: the browser needs an id
     * for the answer bubble at the moment streaming starts (it is what a
     * thumbs-up is filed against), but an answer row written before the answer
     * exists would leave an empty message behind whenever a generation
     * produces nothing. Reserving a UUID keeps the id server-authoritative and
     * costs nothing if it ends up unused.
     */
    public record TurnContext(UUID sessionId, List<ChatMessage> history, UUID assistantMessageId) {}

    public List<ChatSession> list(String username, long clusterId) {
        return sessionRepository.findByUsernameAndClusterIdOrderByUpdatedAtDesc(username, clusterId);
    }

    public List<ChatMessage> transcript(String username, long clusterId, UUID sessionId) {
        ChatSession session = requireOwned(username, clusterId, sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    @Transactional
    public void delete(String username, long clusterId, UUID sessionId) {
        ChatSession session = requireOwned(username, clusterId, sessionId);
        // chat_messages cascades on the FK, so the transcript goes with it.
        sessionRepository.delete(session);
    }

    /**
     * Opens or continues a conversation and records the user's question, before
     * the model is called — so a generation that fails, or a user who navigates
     * away mid-answer, still leaves the question they asked in the history.
     *
     * @param sessionId null to start a new conversation
     */
    @Transactional
    public TurnContext beginTurn(String username, long clusterId, UUID sessionId, String userMessage) {
        ChatSession session = sessionId == null
            ? new ChatSession(username, clusterId, deriveTitle(userMessage))
            : requireOwned(username, clusterId, sessionId);
        session.touch();

        // One save covers both cases — an insert for a new conversation, an
        // updated_at bump for an existing one — and has to land before the
        // message that references it.
        sessionRepository.save(session);
        messageRepository.save(new ChatMessage(session.getId(), ChatMessage.ROLE_USER, userMessage, null));

        return new TurnContext(session.getId(), recentHistory(session.getId()), UUID.randomUUID());
    }

    /**
     * Stores the assistant's reply once streaming has finished.
     *
     * REQUIRES_NEW because this runs from a StreamingResponseBody on an async
     * dispatch — the request's own transaction closed long before the last
     * token arrived. It also never throws: the user has already read the answer
     * on screen, and failing the response at that point would be worse than
     * losing the record, which is logged instead.
     *
     * A partial answer (stream interrupted, client disconnected) is stored as
     * it stands, so the history matches what was actually on screen. An empty
     * one is skipped — an empty assistant bubble is noise, not history.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTurn(UUID sessionId, UUID messageId, String assistantMessage, String model) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return;
        }
        try {
            // Stored under the id already given to the browser, so the feedback
            // the user files against the bubble on screen points at this row.
            messageRepository.save(
                new ChatMessage(messageId, sessionId, ChatMessage.ROLE_ASSISTANT, assistantMessage, model));
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.touch();
                sessionRepository.save(s);
            });
        } catch (Exception e) {
            log.warn("Chat message write failed session={} - {}", sessionId, e.getMessage());
        }
    }

    /** Auto-derived titles come from whatever was asked first, which is not always a good name for the chat. */
    @Transactional
    public ChatSession rename(String username, long clusterId, UUID sessionId, String title) {
        ChatSession session = requireOwned(username, clusterId, sessionId);
        session.rename(title.strip());
        return sessionRepository.save(session);
    }

    /** Oldest-first tail of the conversation, bounded to {@link #MAX_HISTORY}. */
    private List<ChatMessage> recentHistory(UUID sessionId) {
        List<ChatMessage> newestFirst = new ArrayList<>(
            messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, MAX_HISTORY)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private ChatSession requireOwned(String username, long clusterId, UUID sessionId) {
        return sessionRepository.findByIdAndUsername(sessionId, username)
            // A session belongs to the cluster it was started against; reaching it
            // through another cluster's URL would hand back a transcript grounded
            // in a snapshot of somewhere else.
            .filter(s -> s.getClusterId() != null && s.getClusterId() == clusterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
    }

    /**
     * Titles come from the first question rather than a second model call —
     * cosmetic text is not worth another round trip to the LLM.
     */
    static String deriveTitle(String firstMessage) {
        String flattened = firstMessage.strip().replaceAll("\\s+", " ");
        if (flattened.length() <= 60) {
            return flattened.isEmpty() ? "New chat" : flattened;
        }
        String cut = flattened.substring(0, 60);
        int lastSpace = cut.lastIndexOf(' ');
        // Only break on a word boundary if one sits reasonably near the end;
        // otherwise (a long unbroken token) take the hard cut.
        if (lastSpace > 30) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "…";
    }
}
