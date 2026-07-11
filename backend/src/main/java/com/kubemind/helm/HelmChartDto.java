package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Shape of one entry from `helm search repo <term> -o json`. `name` is "repo/chart". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmChartDto(
    String name,
    String version,
    @JsonProperty("app_version") String appVersion,
    String description
) {}
