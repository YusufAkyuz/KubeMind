package com.kubemind.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        service = new ChatSessionService(sessionRepository, messageRepository);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
    }

    @Test
    void firstMessageOpensASessionTitledAfterTheQuestion() {
        var turn = service.beginTurn("bob", 7L, null, "Why is my payments pod crashing?");

        assertThat(turn.sessionId()).isNotNull();
        verify(sessionRepository).save(any(ChatSession.class));
        verify(messageRepository).save(any(ChatMessage.class));
    }

    @Test
    void theQuestionIsStoredBeforeTheModelIsEverCalled() {
        // beginTurn returns before any streaming happens, so a generation that
        // fails outright must still leave the question in the transcript.
        service.beginTurn("bob", 7L, null, "what changed in the last hour?");

        verify(messageRepository).save(any(ChatMessage.class));
    }

    /**
     * The core guarantee of this feature. A session id that belongs to someone
     * else must be indistinguishable from one that does not exist — hence 404,
     * not 403: a 403 would confirm the session is real.
     */
    @Test
    void anotherUsersSessionIsNotFoundRatherThanForbidden() {
        UUID someoneElses = UUID.randomUUID();
        when(sessionRepository.findByIdAndUsername(someoneElses, "bob")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transcript("bob", 7L, someoneElses))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("not found");
    }

    @Test
    void continuingAConversationLooksItUpByOwnerNotByIdAlone() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findByIdAndUsername(id, "bob"))
            .thenReturn(Optional.of(new ChatSession("bob", 7L, "Existing chat")));

        service.beginTurn("bob", 7L, id, "and now?");

        verify(sessionRepository).findByIdAndUsername(id, "bob");
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    void aSessionCannotBeReachedThroughAnotherClustersUrl() {
        // Its transcript is grounded in a snapshot of cluster 7; serving it under
        // cluster 9 would present it as being about somewhere else.
        UUID id = UUID.randomUUID();
        when(sessionRepository.findByIdAndUsername(id, "bob"))
            .thenReturn(Optional.of(new ChatSession("bob", 7L, "About cluster 7")));

        assertThatThrownBy(() -> service.transcript("bob", 9L, id))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void onlyTheTailOfALongConversationIsFedBackToTheModel() {
        service.beginTurn("bob", 7L, null, "hi");

        verify(messageRepository).findBySessionIdOrderByCreatedAtDesc(
            any(UUID.class), eq(Pageable.ofSize(ChatSessionService.MAX_HISTORY)));
    }

    @Test
    void historyIsHandedBackOldestFirst() {
        UUID id = UUID.randomUUID();
        ChatSession session = new ChatSession("bob", 7L, "chat");
        when(sessionRepository.findByIdAndUsername(id, "bob")).thenReturn(Optional.of(session));
        // The repository returns newest-first (that is how the limit works);
        // the model needs chronological order or the conversation reads backwards.
        ChatMessage newer = new ChatMessage(session.getId(), ChatMessage.ROLE_ASSISTANT, "second", "m");
        ChatMessage older = new ChatMessage(session.getId(), ChatMessage.ROLE_USER, "first", null);
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(), any()))
            .thenReturn(List.of(newer, older));

        var turn = service.beginTurn("bob", 7L, id, "third");

        assertThat(turn.history()).extracting(ChatMessage::getContent).containsExactly("first", "second");
    }

    @Test
    void aPartialAnswerFromAnInterruptedStreamIsStillSaved() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        service.completeTurn(sessionId, "The pod is failing because", "qwen2.5-coder:7b");

        verify(messageRepository).save(any(ChatMessage.class));
    }

    @Test
    void anAnswerThatProducedNothingIsNotStoredAsAnEmptyBubble() {
        service.completeTurn(UUID.randomUUID(), "", "qwen2.5-coder:7b");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void aFailedWriteNeverPropagatesToTheUserWhoAlreadyReadTheAnswer() {
        when(messageRepository.save(any())).thenThrow(new RuntimeException("db down"));

        // No exception: the response has already been streamed and committed.
        assertThat(service.completeTurn(UUID.randomUUID(), "an answer", "m")).isNull();
    }

    @Test
    void titlesComeFromTheQuestionAndStayShort() {
        assertThat(ChatSessionService.deriveTitle("  why   is   it   broken?  ")).isEqualTo("why is it broken?");

        String long_ = "explain in detail why the payments deployment keeps restarting every few minutes";
        assertThat(ChatSessionService.deriveTitle(long_))
            .hasSizeLessThanOrEqualTo(61)
            .endsWith("…")
            // Cut on a word boundary, not mid-word.
            .doesNotContain("resta…");
    }

    @Test
    void listAndDeleteAreScopedToTheCaller() {
        service.list("bob", 7L);
        verify(sessionRepository).findByUsernameAndClusterIdOrderByUpdatedAtDesc("bob", 7L);

        UUID id = UUID.randomUUID();
        when(sessionRepository.findByIdAndUsername(id, "bob"))
            .thenReturn(Optional.of(new ChatSession("bob", 7L, "mine")));
        service.delete("bob", 7L, id);
        verify(sessionRepository).delete(any(ChatSession.class));
    }
}
