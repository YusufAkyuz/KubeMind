package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of `helm get metadata <release> -o json` — only the field we need. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmMetadataDto(String chart) {}
