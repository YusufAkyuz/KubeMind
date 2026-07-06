package com.kubemind.k8s;

import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    @Test
    void formatsNanocoreCpuAsMillicores() {
        assertThat(MetricsService.formatCpuMillicores(new Quantity("142856942n"))).isEqualTo("143m");
    }

    @Test
    void formatsMillicoreCpuUnchanged() {
        assertThat(MetricsService.formatCpuMillicores(new Quantity("500m"))).isEqualTo("500m");
    }

    @Test
    void formatsWholeCoreCpuAsMillicores() {
        assertThat(MetricsService.formatCpuMillicores(new Quantity("2"))).isEqualTo("2000m");
    }

    @Test
    void formatCpuReturnsNullWhenMissing() {
        assertThat(MetricsService.formatCpuMillicores(null)).isNull();
    }

    @Test
    void formatsKibibyteMemoryAsMebibytes() {
        assertThat(MetricsService.formatMemoryMi(new Quantity("524288Ki"))).isEqualTo("512Mi");
    }

    @Test
    void formatsGibibyteMemoryAsMebibytes() {
        assertThat(MetricsService.formatMemoryMi(new Quantity("1Gi"))).isEqualTo("1024Mi");
    }

    @Test
    void formatMemoryReturnsNullWhenMissing() {
        assertThat(MetricsService.formatMemoryMi(null)).isNull();
    }
}
