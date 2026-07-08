package com.kubemind.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagServiceTest {

    private VectorStore vectorStore;
    private RagService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new RagService(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    }

    @Test
    void referenceBlockIsEmptyWhenNothingMatches() {
        assertThat(service.buildReferenceBlock(1L, "pod crashlooping")).isEmpty();
    }

    @Test
    void tenantSearchesAreScopedByClusterIdAndK8sDocsSearchIsGlobal() {
        service.buildReferenceBlock(42L, "OOMKilled pod");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(3)).similaritySearch(captor.capture());

        List<String> filters = captor.getAllValues().stream()
            .map(r -> r.getFilterExpression().toString())
            .toList();

        // The k8s-docs search must never carry a clusterId filter (it's the shared, global corpus)...
        assertThat(filters).anySatisfy(f -> {
            assertThat(f).contains("k8s-docs");
            assertThat(f).doesNotContain("clusterId");
        });
        // ...while both tenant corpora (runbook, diagnosis) must be scoped to exactly this cluster.
        assertThat(filters).filteredOn(f -> f.contains("runbook") || f.contains("diagnosis"))
            .hasSize(2)
            .allSatisfy(f -> assertThat(f).contains("clusterId").contains("42"));
    }

    @Test
    void differentClustersNeverShareAFilterValue() {
        service.buildReferenceBlock(1L, "query");
        ArgumentCaptor<SearchRequest> first = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(3)).similaritySearch(first.capture());

        org.mockito.Mockito.clearInvocations(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.buildReferenceBlock(2L, "query");
        ArgumentCaptor<SearchRequest> second = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(3)).similaritySearch(second.capture());

        boolean clusterOneLeaksIntoClusterTwo = second.getAllValues().stream()
            .map(r -> r.getFilterExpression().toString())
            .anyMatch(f -> f.contains("clusterId") && f.contains("1") && !f.contains("2"));
        assertThat(clusterOneLeaksIntoClusterTwo).isFalse();
    }

    @Test
    void buildReferenceBlockDegradesGracefullyWhenVectorStoreFails() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("Ollama unreachable"));

        assertThat(service.buildReferenceBlock(1L, "query")).isEmpty();
    }

    @Test
    void indexRunbookRedactsSecretsBeforeEmbedding() {
        service.indexRunbook(1L, "rb-1", "DB creds runbook",
            "connect using password=hunter2-super-secret");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        String indexedText = captor.getValue().get(0).getText();

        assertThat(indexedText).doesNotContain("hunter2-super-secret");
    }
}
