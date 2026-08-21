package com.kubemind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditLogRepository;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ClusterRepository;
import io.fabric8.kubernetes.api.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Background "learning" for the AI: periodically summarizes each cluster into a
 * few small, derived sections (topology, workload health, recurring incidents,
 * recent changes, past AI diagnoses) and stores them in {@code cluster_profiles}.
 *
 * This is NOT a live-state mirror — every section is either a plain aggregate
 * (counts, top-K lists) or, for {@link #SECTION_INCIDENT_PATTERNS} only, a short
 * LLM narrative generated from that aggregate. Live resource lookups elsewhere in
 * the app always hit the Kubernetes API directly; this table only ever holds
 * historical/derived knowledge used to make AI answers cluster-aware.
 */
@Service
public class ClusterProfileService {

    public static final String SECTION_TOPOLOGY = "topology";
    public static final String SECTION_WORKLOAD_SUMMARY = "workload_summary";
    public static final String SECTION_INCIDENT_PATTERNS = "incident_patterns";
    public static final String SECTION_CHANGES = "changes";
    public static final String SECTION_CHANGE_EFFECTS = "change_effects";
    public static final String SECTION_AI_HISTORY = "ai_history";

    private static final Logger log = LoggerFactory.getLogger(ClusterProfileService.class);
    private static final int MAX_INCIDENT_PATTERNS = 30;

    private final ClusterClientFactory clientFactory;
    private final ClusterRepository clusterRepository;
    private final AuditLogRepository auditLogRepository;
    private final AiDiagnosisRepository aiDiagnosisRepository;
    private final ClusterProfileRepository profileRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ClusterProfileService(ClusterClientFactory clientFactory, ClusterRepository clusterRepository,
                                 AuditLogRepository auditLogRepository, AiDiagnosisRepository aiDiagnosisRepository,
                                 ClusterProfileRepository profileRepository, ChatClient chatClient,
                                 ObjectMapper objectMapper) {
        this.clientFactory = clientFactory;
        this.clusterRepository = clusterRepository;
        this.auditLogRepository = auditLogRepository;
        this.aiDiagnosisRepository = aiDiagnosisRepository;
        this.profileRepository = profileRepository;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Kubernetes discards Events after about an hour; older changes have nothing left to link to. */
    private static final Duration CHANGE_LOOKBACK = Duration.ofHours(1);

    /**
     * Audit actions that open a session or administer the app rather than alter
     * the cluster. A pod exec cannot make a workload start crash-looping, so
     * pairing one with a warning would only manufacture a coincidence.
     */
    private static final Set<String> NON_MUTATING_ACTIONS = Set.of(
        "EXEC_POD", "OPEN_CLUSTER_TERMINAL", "NODE_EXEC", "OPEN_PORT_FORWARD",
        "REVEAL_SECRET", "REVEAL_HELM_VALUES", "CREATE_RUNBOOK", "DELETE_RUNBOOK",
        "RESET_USER_PASSWORD", "CREATE_USER", "DELETE_USER",
        "REQUEST_CLUSTER", "APPROVE_CLUSTER", "REJECT_CLUSTER", "DELETE_CLUSTER",
        "ADD_HELM_REPO", "REMOVE_HELM_REPO", "LINK_HELM_CHART_REF");

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public void refreshAll() {
        List<Long> clusterIds = new ArrayList<>();
        clusterIds.add(ClusterClientFactory.DEFAULT_CLUSTER_ID);
        clusterRepository.findAll().forEach(c -> clusterIds.add(c.getId()));
        for (Long clusterId : clusterIds) {
            try {
                refreshCluster(clusterId);
            } catch (Exception e) {
                // One cluster's failure (unreachable, no permissions, ...) must not stop the others.
                log.warn("Cluster profile refresh failed for cluster {}: {}", clusterId, e.getMessage());
            }
        }
    }

    /**
     * Reached only from the scheduled refresh above — the controller and the AI
     * callers read the stored profile, they never trigger this. That matters
     * for the getSystemClient calls below: this runs on nobody's behalf, so
     * there is no caller identity to impersonate.
     */
    public void refreshCluster(long clusterId) {
        refreshTopology(clusterId);
        refreshWorkloadSummary(clusterId);
        refreshChanges(clusterId);
        refreshAiHistory(clusterId);

        // One Event listing serves both sections below; they read the same data
        // for different questions ("what keeps happening" / "what did we just
        // cause"), and a cluster-wide event list is not cheap enough to fetch twice.
        List<Event> warnings = clientFactory.getSystemClient(clusterId)
            .resources(Event.class).inAnyNamespace().list().getItems().stream()
            .filter(e -> "Warning".equals(e.getType()))
            .toList();
        refreshIncidentPatterns(clusterId, warnings);
        refreshChangeEffects(clusterId, warnings);
    }

    // ── Topology (pure aggregation) ─────────────────────────────────────────

    record TopologyContent(int nodeCount, List<String> versions) {}

    private void refreshTopology(long clusterId) {
        var nodes = clientFactory.getSystemClient(clusterId).nodes().list().getItems();
        List<String> versions = nodes.stream()
            .map(n -> n.getStatus() != null && n.getStatus().getNodeInfo() != null
                ? n.getStatus().getNodeInfo().getKubeletVersion() : null)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        String content = writeJson(new TopologyContent(nodes.size(), versions));
        upsert(clusterId, SECTION_TOPOLOGY, content, sha256(content));
    }

    // ── Workload summary (pure aggregation) ─────────────────────────────────

    record WorkloadSummaryContent(int namespaceCount, int podCount, int unhealthyPodCount, List<String> unhealthyHighlights) {}

    private void refreshWorkloadSummary(long clusterId) {
        var client = clientFactory.getSystemClient(clusterId);
        int namespaceCount = client.namespaces().list().getItems().size();
        var pods = client.pods().inAnyNamespace().list().getItems();
        var unhealthy = pods.stream()
            .filter(p -> {
                String phase = p.getStatus() != null ? p.getStatus().getPhase() : null;
                return !"Running".equals(phase) && !"Succeeded".equals(phase);
            })
            .toList();
        List<String> highlights = unhealthy.stream()
            .limit(10)
            .map(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName())
            .toList();
        String content = writeJson(new WorkloadSummaryContent(namespaceCount, pods.size(), unhealthy.size(), highlights));
        upsert(clusterId, SECTION_WORKLOAD_SUMMARY, content, sha256(content));
    }

    // ── Recent changes (pure aggregation, from the existing audit log) ──────

    record ChangeEntry(String action, String resourceRef, String result, String createdAt) {}

    private void refreshChanges(long clusterId) {
        List<ChangeEntry> entries = auditLogRepository.findTop10ByClusterIdOrderByCreatedAtDesc(clusterId).stream()
            .map(a -> new ChangeEntry(a.getAction(), a.getResourceRef(), a.getResult(),
                a.getCreatedAt() != null ? a.getCreatedAt().toString() : null))
            .toList();
        String content = writeJson(entries);
        upsert(clusterId, SECTION_CHANGES, content, sha256(content));
    }

    // ── AI diagnosis history (pure aggregation, from the existing ai_diagnoses cache) ──

    record AiHistoryEntry(String resourceRef, String excerpt, String createdAt) {}

    private void refreshAiHistory(long clusterId) {
        List<AiHistoryEntry> entries = aiDiagnosisRepository.findTop10ByClusterIdOrderByCreatedAtDesc(clusterId).stream()
            .map(d -> new AiHistoryEntry(
                d.getResourceKind() + "/" + d.getResourceNs() + "/" + d.getResourceName(),
                firstLine(d.getResponse()),
                d.getCreatedAt().toString()))
            .toList();
        String content = writeJson(entries);
        upsert(clusterId, SECTION_AI_HISTORY, content, sha256(content));
    }

    private String firstLine(String response) {
        if (response == null || response.isBlank()) return "";
        String line = response.strip().split("\n", 2)[0].strip();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    // ── Change effects (join of the audit log with what the cluster reported next) ──

    /**
     * Ties changes made through KubeMind to warnings that followed them.
     *
     * The one thing here that a desktop Kubernetes client structurally cannot
     * do: it needs a record of who changed what and when, which only a server
     * that mediates the changes has. See ChangeEffectLinker for why this looks
     * forward from a change rather than back from an incident, and for the
     * measurement that ruled the backward version out.
     *
     * Only changes from the last hour are considered, because Kubernetes
     * discards Events after roughly that long — older changes have nothing left
     * to corroborate them either way.
     */
    private void refreshChangeEffects(long clusterId, List<Event> warnings) {
        Instant since = Instant.now().minus(CHANGE_LOOKBACK);
        List<ChangeEffectLinker.Change> changes =
            auditLogRepository.findByClusterIdAndCreatedAtAfterOrderByCreatedAtDesc(clusterId, since).stream()
                // A change that failed changed nothing, so it cannot be the cause of anything.
                .filter(a -> "SUCCESS".equals(a.getResult()))
                .filter(a -> !NON_MUTATING_ACTIONS.contains(a.getAction()))
                .map(a -> new ChangeEffectLinker.Change(
                    a.getAction(), a.getResourceRef(), a.getUsername(), a.getCreatedAt()))
                .toList();

        List<ChangeEffectLinker.Warning> observed = warnings.stream()
            .map(ClusterProfileService::toWarning)
            .filter(Objects::nonNull)
            .toList();

        String content = writeJson(ChangeEffectLinker.link(changes, observed));
        upsert(clusterId, SECTION_CHANGE_EFFECTS, content, sha256(content));
    }

    /**
     * An Event's own firstTimestamp is when the condition began; lastTimestamp
     * only says it is still going. Using the latter would make a warning that
     * started yesterday look like it began seconds after today's change.
     */
    private static ChangeEffectLinker.Warning toWarning(Event e) {
        var obj = e.getInvolvedObject();
        if (obj == null || obj.getNamespace() == null || obj.getName() == null || e.getReason() == null) {
            return null;
        }
        String started = e.getFirstTimestamp() != null ? e.getFirstTimestamp() : e.getEventTime() != null
            ? e.getEventTime().getTime() : e.getLastTimestamp();
        if (started == null) return null;
        try {
            return new ChangeEffectLinker.Warning(obj.getKind(), obj.getNamespace(), obj.getName(),
                e.getReason(), Instant.parse(started));
        } catch (Exception parseFailure) {
            return null; // an unparseable timestamp is one we cannot order against a change
        }
    }

    // ── Incident patterns (aggregation, cumulative across runs, + hash-gated LLM narrative) ──

    record IncidentPattern(String signature, int count, String firstSeen, String lastSeen) {}
    record IncidentPatternsContent(String narrative, List<IncidentPattern> patterns) {}

    private void refreshIncidentPatterns(long clusterId, List<Event> warnings) {
        Map<String, Integer> currentCounts = new HashMap<>();
        for (Event e : warnings) {
            var obj = e.getInvolvedObject();
            if (obj == null || e.getReason() == null) continue;
            String signature = obj.getKind() + "/"
                + (obj.getNamespace() != null ? obj.getNamespace() + "/" : "")
                + obj.getName() + ": " + e.getReason();
            currentCounts.merge(signature, e.getCount() != null ? e.getCount() : 1, Integer::sum);
        }

        var existingRow = profileRepository.findByClusterIdAndSection(clusterId, SECTION_INCIDENT_PATTERNS);
        IncidentPatternsContent existingContent = existingRow.map(r -> readJson(r.getContent(), IncidentPatternsContent.class)).orElse(null);

        // Merge cumulatively — a still-live K8s Event's own .count keeps growing, so taking the
        // max avoids double-counting the same event across runs while still picking up brand new ones.
        Map<String, IncidentPattern> bySignature = new LinkedHashMap<>();
        if (existingContent != null && existingContent.patterns() != null) {
            existingContent.patterns().forEach(p -> bySignature.put(p.signature(), p));
        }
        String now = Instant.now().toString();
        for (var e : currentCounts.entrySet()) {
            var prev = bySignature.get(e.getKey());
            if (prev == null) {
                bySignature.put(e.getKey(), new IncidentPattern(e.getKey(), e.getValue(), now, now));
            } else {
                bySignature.put(e.getKey(), new IncidentPattern(e.getKey(), Math.max(prev.count(), e.getValue()), prev.firstSeen(), now));
            }
        }
        List<IncidentPattern> merged = bySignature.values().stream()
            .sorted(Comparator.comparingInt(IncidentPattern::count).reversed())
            .limit(MAX_INCIDENT_PATTERNS)
            .toList();

        String rawHash = sha256(merged.stream()
            .map(p -> p.signature() + ":" + p.count())
            .sorted()
            .collect(Collectors.joining("|")));

        String narrative;
        if (merged.isEmpty()) {
            narrative = null;
        } else if (existingRow.isPresent() && rawHash.equals(existingRow.get().getStateHash()) && existingContent != null) {
            narrative = existingContent.narrative(); // unchanged since last run — skip the LLM call
        } else {
            narrative = summarizeIncidents(merged);
        }

        String content = writeJson(new IncidentPatternsContent(narrative, merged));
        upsert(clusterId, SECTION_INCIDENT_PATTERNS, content, rawHash);
    }

    private String summarizeIncidents(List<IncidentPattern> patterns) {
        String bullets = patterns.stream()
            .limit(10)
            .map(p -> "- " + p.signature() + " (x" + p.count() + ", first seen " + p.firstSeen() + ", last seen " + p.lastSeen() + ")")
            .collect(Collectors.joining("\n"));
        String prompt = "Summarize these recurring Kubernetes warning event patterns in 1-3 short, plain-language "
            + "sentences, focused on what's most concerning. No preamble, no markdown headers.\n\n"
            + Redactor.redactText(bullets);
        try {
            String result = chatClient.prompt().user(prompt).call().content();
            return result != null && !result.isBlank() ? result.strip() : null;
        } catch (Exception e) {
            log.debug("Incident narrative generation failed: {}", e.getMessage());
            return null; // the raw pattern list is still useful on its own without a narrative
        }
    }

    // ── Consumption: compact briefing block for Explain/Chat prompts ────────

    /** @return a compact text block for AI prompts, or "" if the background job hasn't run yet for this cluster. */
    public String buildBriefing(long clusterId) {
        Map<String, ClusterProfile> rows = profileRepository.findByClusterId(clusterId).stream()
            .collect(Collectors.toMap(ClusterProfile::getSection, r -> r, (a, b) -> a));
        if (rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== CLUSTER BRIEFING (background knowledge, may be up to ~15 min stale) ===\n");

        var topology = rows.get(SECTION_TOPOLOGY);
        if (topology != null) {
            var t = readJson(topology.getContent(), TopologyContent.class);
            if (t != null) {
                sb.append("Topology: ").append(t.nodeCount()).append(" node(s)");
                if (!t.versions().isEmpty()) sb.append(", versions: ").append(String.join(", ", t.versions()));
                sb.append('\n');
            }
        }

        var workload = rows.get(SECTION_WORKLOAD_SUMMARY);
        if (workload != null) {
            var w = readJson(workload.getContent(), WorkloadSummaryContent.class);
            if (w != null) {
                sb.append("Workloads: ").append(w.namespaceCount()).append(" namespace(s), ")
                  .append(w.podCount()).append(" pod(s), ").append(w.unhealthyPodCount()).append(" unhealthy\n");
            }
        }

        var incidents = rows.get(SECTION_INCIDENT_PATTERNS);
        if (incidents != null) {
            var i = readJson(incidents.getContent(), IncidentPatternsContent.class);
            if (i != null && i.narrative() != null) {
                sb.append("Recurring issues: ").append(i.narrative()).append('\n');
            }
        }

        var effects = rows.get(SECTION_CHANGE_EFFECTS);
        if (effects != null) {
            List<ChangeEffectLinker.ChangeEffect> list =
                readJsonList(effects.getContent(), ChangeEffectLinker.ChangeEffect.class);
            if (!list.isEmpty()) {
                // Worded as sequence, never causation: the model must not upgrade
                // "followed by" into "caused by" when it repeats this back.
                sb.append("Warnings that appeared right after a change made here "
                    + "(timing only — not established causation):\n");
                list.stream().limit(5).forEach(e -> sb.append("- ").append(e.warningSignature())
                    .append(": ").append(e.warningReason())
                    .append(", ").append(e.minutesAfter()).append(" min after ")
                    .append(e.action()).append(' ').append(e.resourceRef())
                    .append(" by ").append(e.username()).append('\n'));
            }
        }

        var changes = rows.get(SECTION_CHANGES);
        if (changes != null) {
            List<ChangeEntry> list = readJsonList(changes.getContent(), ChangeEntry.class);
            if (!list.isEmpty()) {
                sb.append("Recent changes:\n");
                list.stream().limit(5).forEach(c -> sb.append("- ").append(c.action()).append(' ')
                    .append(c.resourceRef()).append(" (").append(c.result()).append(")\n"));
            }
        }

        return sb.toString();
    }

    // ── Consumption: structured DTO for the "Cluster Insights" UI panel ─────

    public record ClusterInsightsDto(
        boolean available, int nodeCount, List<String> nodeVersions,
        int namespaceCount, int podCount, int unhealthyPodCount, List<String> unhealthyHighlights,
        String incidentNarrative, List<IncidentPattern> topIncidents,
        List<ChangeEntry> recentChanges,
        /** Warnings that appeared shortly after a change made through KubeMind. Timing, not proof. */
        List<ChangeEffectLinker.ChangeEffect> changeEffects,
        Instant lastUpdated
    ) {}

    public ClusterInsightsDto getInsights(long clusterId) {
        var rowList = profileRepository.findByClusterId(clusterId);
        if (rowList.isEmpty()) {
            return new ClusterInsightsDto(false, 0, List.of(), 0, 0, 0, List.of(), null,
                List.of(), List.of(), List.of(), null);
        }
        Map<String, ClusterProfile> rows = rowList.stream()
            .collect(Collectors.toMap(ClusterProfile::getSection, r -> r, (a, b) -> a));

        var topology = rows.get(SECTION_TOPOLOGY) != null
            ? readJson(rows.get(SECTION_TOPOLOGY).getContent(), TopologyContent.class) : null;
        var workload = rows.get(SECTION_WORKLOAD_SUMMARY) != null
            ? readJson(rows.get(SECTION_WORKLOAD_SUMMARY).getContent(), WorkloadSummaryContent.class) : null;
        var incidents = rows.get(SECTION_INCIDENT_PATTERNS) != null
            ? readJson(rows.get(SECTION_INCIDENT_PATTERNS).getContent(), IncidentPatternsContent.class) : null;
        List<ChangeEffectLinker.ChangeEffect> changeEffects = rows.get(SECTION_CHANGE_EFFECTS) != null
            ? readJsonList(rows.get(SECTION_CHANGE_EFFECTS).getContent(), ChangeEffectLinker.ChangeEffect.class)
            : List.of();
        var changes = rows.get(SECTION_CHANGES) != null
            ? readJsonList(rows.get(SECTION_CHANGES).getContent(), ChangeEntry.class) : List.<ChangeEntry>of();

        Instant lastUpdated = rowList.stream().map(ClusterProfile::getUpdatedAt).max(Instant::compareTo).orElse(null);

        return new ClusterInsightsDto(
            true,
            topology != null ? topology.nodeCount() : 0,
            topology != null ? topology.versions() : List.of(),
            workload != null ? workload.namespaceCount() : 0,
            workload != null ? workload.podCount() : 0,
            workload != null ? workload.unhealthyPodCount() : 0,
            workload != null ? workload.unhealthyHighlights() : List.of(),
            incidents != null ? incidents.narrative() : null,
            incidents != null ? incidents.patterns() : List.of(),
            changes,
            changeEffects,
            lastUpdated
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void upsert(long clusterId, String section, String content, String hash) {
        var existing = profileRepository.findByClusterIdAndSection(clusterId, section);
        if (existing.isPresent()) {
            existing.get().update(content, hash);
            profileRepository.save(existing.get());
        } else {
            profileRepository.save(new ClusterProfile(clusterId, section, content, hash));
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> readJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
