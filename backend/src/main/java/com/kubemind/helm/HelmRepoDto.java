package com.kubemind.helm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of one entry from `helm repo list -o json`. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelmRepoDto(String name, String url) {}
