package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Shape of one entry from `helm list -o json`, remapped to camelCase for the frontend. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmReleaseDto(
    String name,
    String namespace,
    String revision,
    String updated,
    String status,
    String chart,
    @JsonProperty("app_version") String appVersion
) {}
