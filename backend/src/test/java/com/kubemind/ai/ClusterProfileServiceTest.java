package com.kubemind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubemind.audit.AuditLog;
import com.kubemind.audit.AuditLogRepository;
import com.kubemind.cluster.ClusterClientFactory;
import com.kubemind.cluster.ClusterRepository;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class ClusterProfileServiceTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private AuditLogRepository auditLogRepository;
    private ChatClient chatClient;
    private ClusterProfileService service;

    // In-memory stand-in for the (cluster_id, section) upsert the real JPA repository does —
    // this test cares about ClusterProfileService's aggregation/merge/hash logic, not JPA itself.
    private final Map<String, ClusterProfile> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        var clientFactory = mock(ClusterClientFactory.class);
        when(clientFactory.getClient(0L)).thenReturn(client);
        var clusterRepository = mock(ClusterRepository.class);
        when(clusterRepository.findAll()).thenReturn(List.of());
        auditLogRepository = mock(AuditLogRepository.class);
        when(auditLogRepository.findTop10ByClusterIdOrderByCreatedAtDesc(0L)).thenReturn(List.of());
        var aiDiagnosisRepository = mock(AiDiagnosisRepository.class);
        when(aiDiagnosisRepository.findTop10ByClusterIdOrderByCreatedAtDesc(0L)).thenReturn(List.of());

        var profileRepository = mock(ClusterProfileRepository.class);
        when(profileRepository.findByClusterIdAndSection(org.mockito.ArgumentMatchers.anyLong(), anyString()))
            .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(profileRepository.findByClusterId(org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> store.values().stream().filter(p -> p.getClusterId().equals(inv.getArgument(0))).toList());
        when(profileRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ClusterProfile p = inv.getArgument(0);
            store.put(p.getClusterId() + ":" + p.getSection(), p);
            return p;
        });

        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("coredns keeps crashing under memory pressure.");
        org.mockito.Mockito.clearInvocations(chatClient); // stubbing above itself invokes prompt() once

        service = new ClusterProfileService(clientFactory, clusterRepository, auditLogRepository,
            aiDiagnosisRepository, profileRepository, chatClient, new ObjectMapper());

        client.nodes().resource(new NodeBuilder()
            .withNewMetadata().withName("node-1").endMetadata()
            .withNewStatus().withNewNodeInfo().withKubeletVersion("v1.30.0").endNodeInfo().endStatus()
            .build()).create();
        client.namespaces().resource(new NamespaceBuilder()
            .withNewMetadata().withName("default").endMetadata().build()).create();
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("healthy-pod").withNamespace("default").endMetadata()
            .withNewStatus().withPhase("Running").endStatus()
            .build()).create();
        client.pods().inNamespace("default").resource(new PodBuilder()
            .withNewMetadata().withName("broken-pod").withNamespace("default").endMetadata()
            .withNewStatus().withPhase("Failed").endStatus()
            .build()).create();
    }

    @Test
    void buildBriefingIsEmptyBeforeAnyRefresh() {
        assertThat(service.buildBriefing(0L)).isEmpty();
    }

    @Test
    void refreshPopulatesTopologyAndWorkloadSummary() {
        service.refreshCluster(0L);

        String briefing = service.buildBriefing(0L);
        assertThat(briefing)
            .contains("1 node(s)")
            .contains("v1.30.0")
            .contains("1 namespace(s)")
            .contains("2 pod(s)")
            .contains("1 unhealthy");
    }

    @Test
    void refreshIncludesRecentAuditedChanges() {
        var entry = new AuditLog(1L, "admin", 0L, "DELETE_RESOURCE", "ConfigMap/default/app-config", null, "SUCCESS");
        when(auditLogRepository.findTop10ByClusterIdOrderByCreatedAtDesc(0L)).thenReturn(List.of(entry));

        service.refreshCluster(0L);

        assertThat(service.buildBriefing(0L))
            .contains("DELETE_RESOURCE")
            .contains("ConfigMap/default/app-config");
    }

    @Test
    void incidentNarrativeIsRegeneratedOnlyWhenPatternsChange() {
        client.resources(Event.class).inNamespace("default").resource(new EventBuilder()
            .withNewMetadata().withName("evt-1").withNamespace("default").endMetadata()
            .withType("Warning").withReason("BackOff").withCount(3)
            .withInvolvedObject(new ObjectReferenceBuilder().withKind("Pod").withNamespace("default").withName("broken-pod").build())
            .build()).create();

        service.refreshCluster(0L);
        verify(chatClient, times(1)).prompt();
        assertThat(service.buildBriefing(0L)).contains("coredns keeps crashing");

        // Same events again — the underlying aggregate hash is unchanged, so no second LLM call.
        service.refreshCluster(0L);
        verify(chatClient, times(1)).prompt();
    }

    @Test
    void getInsightsReportsUnavailableBeforeAnyRefresh() {
        var insights = service.getInsights(0L);

        assertThat(insights.available()).isFalse();
    }

    @Test
    void getInsightsReflectsAggregatedState() {
        service.refreshCluster(0L);

        var insights = service.getInsights(0L);

        assertThat(insights.available()).isTrue();
        assertThat(insights.nodeCount()).isEqualTo(1);
        assertThat(insights.podCount()).isEqualTo(2);
        assertThat(insights.unhealthyPodCount()).isEqualTo(1);
    }
}
