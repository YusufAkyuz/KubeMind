package com.kubemind.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Eval harness for the Explain prompt/model combo (KubeMind-AI-Plan.md §A4):
 * runs a fixture set of broken-resource snapshots (src/test/resources/eval/
 * scenarios.yaml) through the REAL system prompt ({@link AiConfig#SYSTEM_PROMPT})
 * against the local Ollama model, and scores each answer by expected-keyword
 * groups. This is what makes prompt/model changes measurable instead of vibes:
 * run it before and after the change and compare the two summary lines.
 *
 * Deliberately NOT part of the normal build: needs a running Ollama and takes
 * minutes, so it only runs when explicitly asked for.
 *
 *   cd backend && mvn test -Dtest=EvalHarness -Deval=true
 *
 * Options (system properties):
 *   -Deval.filter=oom       run only scenarios whose id contains the substring
 *   -Deval.minScore=0.8     fail the run if the pass ratio drops below this
 *
 * Talks to Ollama's HTTP API directly (not through Spring AI's ChatClient) to
 * keep the harness a plain JUnit class with zero Spring context — the prompt,
 * model name, and temperature are the same ones application.yml configures, so
 * the measurement still reflects production behavior.
 */
class EvalHarness {

    private static final String OLLAMA_BASE_URL =
        System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String MODEL =
        System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5-coder:7b");
    // Mirrors spring.ai.ollama.chat.options.temperature in application.yml.
    private static final double TEMPERATURE = 0.2;
    private static final Duration PER_CALL_TIMEOUT = Duration.ofMinutes(5);

    record Scenario(String id, List<String> expect, String context) {}

    record ScenarioResult(String id, boolean passed, List<String> missedGroups, long durationMs, String answer) {}

    @Test
    @EnabledIfSystemProperty(named = "eval", matches = "true")
    void runEval() throws Exception {
        List<Scenario> scenarios = loadScenarios();

        String filter = System.getProperty("eval.filter", "");
        if (!filter.isBlank()) {
            scenarios = scenarios.stream().filter(s -> s.id().contains(filter)).toList();
        }
        assertTrue(!scenarios.isEmpty(), "No scenarios matched filter '" + filter + "'");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper json = new ObjectMapper();

        System.out.printf("%n=== KubeMind Eval · model=%s · %d scenario(s) ===%n%n", MODEL, scenarios.size());

        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario s : scenarios) {
            long start = System.currentTimeMillis();
            String answer;
            try {
                answer = ask(http, json, s.context());
            } catch (Exception e) {
                fail("Ollama call failed for scenario '" + s.id() + "' — is Ollama running at "
                    + OLLAMA_BASE_URL + "? (" + e.getMessage() + ")");
                return;
            }
            long durationMs = System.currentTimeMillis() - start;

            String lower = answer.toLowerCase(Locale.ROOT);
            List<String> missed = s.expect().stream()
                .filter(group -> java.util.Arrays.stream(group.split("\\|"))
                    .map(alt -> alt.trim().toLowerCase(Locale.ROOT))
                    .noneMatch(lower::contains))
                .toList();

            boolean passed = missed.isEmpty();
            results.add(new ScenarioResult(s.id(), passed, missed, durationMs, answer));
            System.out.printf("%-42s %s  (%.1fs)%s%n",
                s.id(),
                passed ? "PASS" : "FAIL",
                durationMs / 1000.0,
                passed ? "" : "  missed: " + missed);
        }

        long passed = results.stream().filter(ScenarioResult::passed).count();
        double score = (double) passed / results.size();
        System.out.printf("%n=== Score: %d/%d (%.0f%%) ===%n", passed, results.size(), score * 100);

        writeResultsJson(json, results, score);

        String minScore = System.getProperty("eval.minScore", "");
        if (!minScore.isBlank()) {
            assertTrue(score >= Double.parseDouble(minScore),
                "Eval score %.2f is below the required minimum %s".formatted(score, minScore));
        }
    }

    private List<Scenario> loadScenarios() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = getClass().getResourceAsStream("/eval/scenarios.yaml")) {
            return yaml.readValue(in, new TypeReference<>() {});
        }
    }

    private String ask(HttpClient http, ObjectMapper json, String context) throws Exception {
        String body = json.writeValueAsString(Map.of(
            "model", MODEL,
            "stream", false,
            "options", Map.of("temperature", TEMPERATURE),
            "messages", List.of(
                Map.of("role", "system", "content", AiConfig.SYSTEM_PROMPT),
                Map.of("role", "user", "content", context))));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_BASE_URL + "/api/chat"))
            .timeout(PER_CALL_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body()).path("message").path("content").asText();
    }

    /** Full per-scenario results (including answers) for diffing two runs offline. */
    private void writeResultsJson(ObjectMapper json, List<ScenarioResult> results, double score) throws Exception {
        JsonNode out = json.valueToTree(Map.of(
            "timestamp", Instant.now().toString(),
            "model", MODEL,
            "score", score,
            "results", results));
        Path path = Path.of("target", "eval-results.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("Full results (with answers): " + path.toAbsolutePath());
    }
}
