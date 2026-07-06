package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Wraps the metrics.k8s.io API (metrics-server). metrics-server is optional on
 * a cluster, so every listing method degrades to an empty list instead of
 * failing the page when it isn't installed or the API call errors out.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private static final BigDecimal MILLI = BigDecimal.valueOf(1000);
    private static final BigDecimal MEBIBYTE = BigDecimal.valueOf(1024L * 1024);

    private final ClusterClientFactory clientFactory;

    public MetricsService(ClusterClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public List<NodeMetricsDto> listNodeMetrics(long clusterId) {
        try {
            return clientFactory.getClient(clusterId).top().nodes().metrics().getItems().stream()
                .map(this::toNodeMetricsDto)
                .toList();
        } catch (Exception e) {
            log.debug("Node metrics unavailable for cluster {} (metrics-server not installed?): {}", clusterId, e.getMessage());
            return List.of();
        }
    }

    public List<PodMetricsDto> listPodMetrics(long clusterId, String namespace) {
        try {
            return clientFactory.getClient(clusterId).top().pods().metrics(namespace).getItems().stream()
                .map(this::toPodMetricsDto)
                .toList();
        } catch (Exception e) {
            log.debug("Pod metrics unavailable for cluster {}/{} (metrics-server not installed?): {}", clusterId, namespace, e.getMessage());
            return List.of();
        }
    }

    private NodeMetricsDto toNodeMetricsDto(NodeMetrics nm) {
        Map<String, Quantity> usage = nm.getUsage() != null ? nm.getUsage() : Map.of();
        return new NodeMetricsDto(
            nm.getMetadata().getName(),
            formatCpuMillicores(usage.get("cpu")),
            formatMemoryMi(usage.get("memory"))
        );
    }

    private PodMetricsDto toPodMetricsDto(PodMetrics pm) {
        BigDecimal cpuCores = BigDecimal.ZERO;
        BigDecimal memBytes = BigDecimal.ZERO;
        List<ContainerMetrics> containers = pm.getContainers() != null ? pm.getContainers() : List.of();
        for (ContainerMetrics cm : containers) {
            Map<String, Quantity> usage = cm.getUsage();
            if (usage == null) continue;
            if (usage.get("cpu") != null) cpuCores = cpuCores.add(usage.get("cpu").getNumericalAmount());
            if (usage.get("memory") != null) memBytes = memBytes.add(Quantity.getAmountInBytes(usage.get("memory")));
        }
        return new PodMetricsDto(
            pm.getMetadata().getName(),
            pm.getMetadata().getNamespace(),
            millisString(cpuCores.multiply(MILLI)),
            mebibyteString(memBytes)
        );
    }

    /**
     * metrics-server reports CPU usage in nanocores (e.g. "142856942n"), which
     * is unreadable — normalize to millicores ("143m") like `kubectl top` does.
     */
    static String formatCpuMillicores(Quantity q) {
        if (q == null) return null;
        return millisString(q.getNumericalAmount().multiply(MILLI));
    }

    static String formatMemoryMi(Quantity q) {
        if (q == null) return null;
        return mebibyteString(Quantity.getAmountInBytes(q));
    }

    private static String millisString(BigDecimal millis) {
        return millis.setScale(0, RoundingMode.HALF_UP).toBigInteger() + "m";
    }

    private static String mebibyteString(BigDecimal bytes) {
        return bytes.divide(MEBIBYTE, 0, RoundingMode.HALF_UP).toBigInteger() + "Mi";
    }
}
