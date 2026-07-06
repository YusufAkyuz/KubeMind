package com.kubemind.k8s;

public record CronJobDto(
    String name,
    String namespace,
    String schedule,
    boolean suspended,
    int activeJobs,
    String image,
    String lastScheduleTime,
    String creationTimestamp
) {}
