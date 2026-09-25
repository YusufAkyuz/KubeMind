package com.kubemind.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's own saved conversations. No role gate: every route is scoped to the
 * caller's own username inside ChatSessionService, which is a stronger
 * guarantee than a role check — an ADMIN gets exactly their own sessions here,
 * same as anyone else.
 *
 * Living under /api/clusters/{clusterId} is deliberate: ClusterAccessInterceptor
 * already enforces "may this user reach this cluster at all" for that prefix, so
 * these endpoints inherit the door check instead of reimplementing it.
 */
@RestController
@RequestMapping("/api/clusters/{clusterId}/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    public record ChatSessionDto(UUID id, String title, Instant createdAt, Instant updatedAt) {
        static ChatSessionDto from(ChatSession s) {
            return new ChatSessionDto(s.getId(), s.getTitle(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record ChatMessageDto(UUID id, String role, String content, Instant createdAt) {
        static ChatMessageDto from(ChatMessage m) {
            return new ChatMessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }

    @GetMapping
    public List<ChatSessionDto> list(@PathVariable long clusterId, Authentication auth) {
        return chatSessionService.list(auth.getName(), clusterId).stream().map(ChatSessionDto::from).toList();
    }

    @GetMapping("/{id}")
    public List<ChatMessageDto> transcript(@PathVariable long clusterId, @PathVariable UUID id,
                                           Authentication auth) {
        return chatSessionService.transcript(auth.getName(), clusterId, id).stream()
            .map(ChatMessageDto::from).toList();
    }

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {}

    @PatchMapping("/{id}")
    public ChatSessionDto rename(@PathVariable long clusterId, @PathVariable UUID id,
                                 @Valid @RequestBody RenameRequest request, Authentication auth) {
        return ChatSessionDto.from(chatSessionService.rename(auth.getName(), clusterId, id, request.title()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long clusterId, @PathVariable UUID id,
                                       Authentication auth) {
        chatSessionService.delete(auth.getName(), clusterId, id);
        return ResponseEntity.noContent().build();
    }
}
