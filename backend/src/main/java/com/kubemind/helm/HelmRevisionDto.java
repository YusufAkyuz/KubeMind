package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shape of one entry from `helm history -o json`, remapped to camelCase for the
 * frontend. Unlike {@link HelmReleaseDto}'s revision — a string there, because
 * `helm list` reports it that way — this one is a number, which is what
 * `helm rollback` takes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmRevisionDto(
    int revision,
    String updated,
    String status,
    String chart,
    @JsonProperty("app_version") String appVersion,
    /** Helm's own summary of what the revision did, e.g. "Upgrade complete". */
    String description
) {}
