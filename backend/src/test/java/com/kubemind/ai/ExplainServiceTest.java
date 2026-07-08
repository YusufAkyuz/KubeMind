package com.kubemind.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplainServiceTest {

    private PodContextCollector podContextCollector;
    private ResourceContextCollector resourceContextCollector;
    private AiDiagnosisRepository repository;
    private ClusterProfileService clusterProfileService;
    private RagService ragService;
    private ChatClient chatClient;
    private ExplainService service;

    @BeforeEach
    void setUp() {
        podContextCollector = mock(PodContextCollector.class);
        resourceContextCollector = mock(ResourceContextCollector.class);
        repository = mock(AiDiagnosisRepository.class);
        clusterProfileService = mock(ClusterProfileService.class);
        ragService = mock(RagService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new ExplainService(podContextCollector, resourceContextCollector, repository,
            clusterProfileService, ragService, chatClient, "test-model");
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(clusterProfileService.buildBriefing(org.mockito.ArgumentMatchers.anyLong())).thenReturn("");
        when(ragService.buildReferenceBlock(org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn("");
    }

    @Test
    void returnsCachedDiagnosisWithoutCallingTheModel() {
        String context = "=== POD ===\nname: p1\nphase: Running\n";
        when(podContextCollector.collect(0L, "default", "p1")).thenReturn(context);
        var cached = new AiDiagnosis(0L, "Pod", "default", "p1", sha256(context), context, "cached explanation", "old-model");
        when(repository.findFirstByStateHash(sha256(context))).thenReturn(java.util.Optional.of(cached));

        var result = service.explainPod(0L, "default", "p1");

        assertThat(result.explanation()).isEqualTo("cached explanation");
        assertThat(result.cached()).isTrue();
        verify(chatClient, org.mockito.Mockito.never()).prompt();
    }

    @Test
    void firstDiagnosisSendsRawContextWithNoPreviousDiagnosisBlock() {
        String context = "=== POD ===\nname: p1\nphase: Running\n";
        when(podContextCollector.collect(0L, "default", "p1")).thenReturn(context);
        when(repository.findFirstByStateHash(anyString())).thenReturn(java.util.Optional.empty());
        when(repository.findFirstByClusterIdAndResourceKindAndResourceNsAndResourceNameOrderByCreatedAtDesc(
                0L, "Pod", "default", "p1")).thenReturn(java.util.Optional.empty());
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("fresh explanation");
        org.mockito.Mockito.clearInvocations(chatClient.prompt());

        var result = service.explainPod(0L, "default", "p1");

        assertThat(result.explanation()).isEqualTo("fresh explanation");
        assertThat(result.cached()).isFalse();
        ArgumentCaptor<String> sentPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(sentPrompt.capture());
        assertThat(sentPrompt.getValue()).isEqualTo(context).doesNotContain("PREVIOUS DIAGNOSIS");
    }

    @Test
    void enrichesPromptWithPriorDiagnosisWhenStateChanged() {
        String context = "=== POD ===\nname: p1\nphase: CrashLoopBackOff\n";
        when(podContextCollector.collect(0L, "default", "p1")).thenReturn(context);
        when(repository.findFirstByStateHash(anyString())).thenReturn(java.util.Optional.empty());
        var prior = new AiDiagnosis(0L, "Pod", "default", "p1", "old-hash", "old-context",
            "previously: OOMKilled due to low memory limit", "old-model");
        when(repository.findFirstByClusterIdAndResourceKindAndResourceNsAndResourceNameOrderByCreatedAtDesc(
                0L, "Pod", "default", "p1")).thenReturn(java.util.Optional.of(prior));
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("new explanation");
        org.mockito.Mockito.clearInvocations(chatClient.prompt());

        var result = service.explainPod(0L, "default", "p1");

        assertThat(result.explanation()).isEqualTo("new explanation");
        ArgumentCaptor<String> sentPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(sentPrompt.capture());
        assertThat(sentPrompt.getValue())
            .contains(context)
            .contains("PREVIOUS DIAGNOSIS")
            .contains("previously: OOMKilled due to low memory limit");
    }

    @Test
    void liveNodeUsageNeverAffectsTheCacheKey() {
        // Regression test: node CPU/memory usage fluctuates on every call. If it ever leaks
        // into the hashed context, the diagnosis cache never hits and every Explain click
        // re-runs the (slow) model for reasons unrelated to the pod's actual problem.
        String context = "=== POD ===\nname: p1\nphase: CrashLoopBackOff\n";
        when(podContextCollector.collect(0L, "default", "p1")).thenReturn(context);
        when(repository.findFirstByStateHash(anyString())).thenReturn(java.util.Optional.empty());
        when(repository.findFirstByClusterIdAndResourceKindAndResourceNsAndResourceNameOrderByCreatedAtDesc(
                0L, "Pod", "default", "p1")).thenReturn(java.util.Optional.empty());
        when(podContextCollector.liveNodeUsage(0L, "default", "p1"))
            .thenReturn("node n1 current usage: cpu=500m memory=1024Mi");
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("diagnosis A");

        service.explainPod(0L, "default", "p1");

        ArgumentCaptor<AiDiagnosis> saved = ArgumentCaptor.forClass(AiDiagnosis.class);
        verify(repository).save(saved.capture());
        String expectedHash = sha256(context);
        assertThat(saved.getValue().getStateHash()).isEqualTo(expectedHash);

        // Same resource state, node usage has since drifted — this must still be a cache hit.
        when(podContextCollector.liveNodeUsage(0L, "default", "p1"))
            .thenReturn("node n1 current usage: cpu=812m memory=1400Mi");
        var cachedDiagnosis = new AiDiagnosis(0L, "Pod", "default", "p1", expectedHash, context, "diagnosis A", "test-model");
        when(repository.findFirstByStateHash(expectedHash)).thenReturn(java.util.Optional.of(cachedDiagnosis));
        org.mockito.Mockito.clearInvocations(chatClient.prompt());

        var secondResult = service.explainPod(0L, "default", "p1");

        assertThat(secondResult.cached()).isTrue();
        verify(chatClient.prompt(), org.mockito.Mockito.never()).user(anyString());
    }

    private String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
