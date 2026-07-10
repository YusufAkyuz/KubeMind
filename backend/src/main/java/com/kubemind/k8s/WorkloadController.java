package com.kubemind.k8s;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Workload resources beyond Deployments (which keep their own controller). */
@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class WorkloadController {

    private final KubernetesService kubernetesService;

    public WorkloadController(KubernetesService kubernetesService) {
        this.kubernetesService = kubernetesService;
    }

    @GetMapping("/namespaces/{ns}/statefulsets")
    public List<StatefulSetDto> statefulSets(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listStatefulSets(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/daemonsets")
    public List<DaemonSetDto> daemonSets(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listDaemonSets(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/jobs")
    public List<JobDto> jobs(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listJobs(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/cronjobs")
    public List<CronJobDto> cronJobs(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listCronJobs(clusterId, ns);
    }

    @GetMapping("/namespaces/{ns}/hpas")
    public List<HpaDto> hpas(@PathVariable long clusterId, @PathVariable String ns) {
        return kubernetesService.listHpas(clusterId, ns);
    }
}
