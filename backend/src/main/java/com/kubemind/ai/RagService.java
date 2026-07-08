package com.kubemind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG over two corpora, one pgvector-backed {@link VectorStore} (Phase A3):
 *
 * <ul>
 *   <li><b>k8s-docs</b> — curated troubleshooting knowledge that ships with the product,
 *       global (no cluster scoping). See {@link K8sDocsSeeder}.</li>
 *   <li><b>runbook</b> / <b>diagnosis</b> — tenant knowledge: admin-authored runbooks and
 *       past AI diagnoses, always scoped by {@code clusterId}. Never cross-tenant.</li>
 * </ul>
 *
 * Deliberately never indexes live cluster state — that's always fresher read straight
 * from the Kubernetes API (existing pattern); a vector index of live state goes stale
 * the moment it's built. RAG here is documentation + history only.
 */
@Service
public class RagService {

    static final String CORPUS_K8S_DOCS = "k8s-docs";
    static final String CORPUS_RUNBOOK = "runbook";
    static final String CORPUS_DIAGNOSIS = "diagnosis";

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int TOP_K_DOCS = 3;
    // Runbooks are usually short and few per cluster, and generic phrases (e.g. a runbook
    // mentioning "KubeMind" by name) can out-rank a more specific but shorter one for a
    // given query. A slightly larger K gives more runbooks a chance to reach the model
    // instead of only the single closest match(es) — cheap since this corpus stays small.
    private static final int TOP_K_TENANT = 4;

    private final VectorStore vectorStore;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ── Indexing ─────────────────────────────────────────────────────────────

    /** Idempotent — a stable id upserts rather than duplicating (ON CONFLICT DO UPDATE). */
    void indexK8sDoc(String id, String title, String content) {
        vectorStore.add(List.of(new Document(toDocId(id), content, Map.of("corpus", CORPUS_K8S_DOCS, "title", title))));
    }

    public void indexRunbook(long clusterId, String id, String title, String content) {
        String redacted = Redactor.redactText(content);
        vectorStore.add(List.of(new Document(id, redacted,
            Map.of("corpus", CORPUS_RUNBOOK, "clusterId", String.valueOf(clusterId), "title", title))));
    }

    public void deleteRunbook(String id) {
        vectorStore.delete(List.of(id));
    }

    /** Best-effort — a diagnosis is already cached in {@code ai_diagnoses}; RAG indexing is a bonus, never a blocker. */
    public void indexDiagnosisBestEffort(long clusterId, String id, String resourceRef, String response) {
        try {
            String redacted = Redactor.redactText(response);
            vectorStore.add(List.of(new Document(toDocId(id), redacted,
                Map.of("corpus", CORPUS_DIAGNOSIS, "clusterId", String.valueOf(clusterId), "resourceRef", resourceRef))));
        } catch (Exception e) {
            log.debug("Diagnosis indexing skipped for {}: {}", resourceRef, e.getMessage());
        }
    }

    // ── Retrieval ────────────────────────────────────────────────────────────

    /**
     * @return a "Reference material" prompt block from both corpora, or "" if nothing
     * relevant was found (or the vector store is unavailable — never blocks the caller).
     */
    public String buildReferenceBlock(long clusterId, String querySignal) {
        List<Document> docs = search(querySignal, TOP_K_DOCS, "corpus == '" + CORPUS_K8S_DOCS + "'");
        List<Document> runbooks = search(querySignal, TOP_K_TENANT,
            "corpus == '" + CORPUS_RUNBOOK + "' && clusterId == '" + clusterId + "'");
        List<Document> diagnoses = search(querySignal, TOP_K_TENANT,
            "corpus == '" + CORPUS_DIAGNOSIS + "' && clusterId == '" + clusterId + "'");

        if (docs.isEmpty() && runbooks.isEmpty() && diagnoses.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== REFERENCE MATERIAL (cite which reference you used, if any) ===\n");
        appendSection(sb, "Kubernetes knowledge base", docs, d -> String.valueOf(d.getMetadata().get("title")));
        appendSection(sb, "Your runbooks", runbooks, d -> String.valueOf(d.getMetadata().get("title")));
        appendSection(sb, "Past diagnoses in this cluster", diagnoses, d -> String.valueOf(d.getMetadata().get("resourceRef")));
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String label, List<Document> docs, java.util.function.Function<Document, String> titleOf) {
        if (docs.isEmpty()) return;
        sb.append("-- ").append(label).append(" --\n");
        for (Document d : docs) {
            sb.append('[').append(titleOf.apply(d)).append("] ").append(d.getText()).append('\n');
        }
    }

    /** PgVectorStore's id column is uuid — deterministically map our human-readable ids
     *  (e.g. "k8s-doc-oomkilled", "diag-&lt;stateHash&gt;") to a stable UUID so re-indexing
     *  the same logical document still upserts instead of erroring or duplicating. Runbook
     *  ids are already real UUIDs (from the Runbook entity) and must NOT go through this —
     *  {@link #deleteRunbook} relies on the exact same id round-tripping. */
    private static String toDocId(String rawId) {
        return UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private List<Document> search(String query, int topK, String filterExpression) {
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterExpression)
                .build());
        } catch (Exception e) {
            // e.g. Ollama unreachable, embedding model not pulled, or the store is empty —
            // RAG is an enhancement, never a hard dependency for Explain/Chat to work.
            log.debug("RAG search skipped ({}): {}", filterExpression, e.getMessage());
            return List.of();
        }
    }
}
