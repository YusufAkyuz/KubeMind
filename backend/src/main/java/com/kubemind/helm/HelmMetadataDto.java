package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shape of `helm get metadata <release> -o json` — only the fields we need.
 * `chart` is the bare name ("grafana"), unlike `helm list`'s "chart" field which
 * is name and version glued together and needs splitting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmMetadataDto(String chart, String version) {}
