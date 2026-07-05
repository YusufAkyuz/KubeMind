package com.kubemind.k8s;

import java.util.List;

/** List/detail view of a Secret. Values are NEVER included — see SecretService.reveal. */
public record SecretDto(
    String name,
    String namespace,
    String type,
    List<String> keys,
    String creationTimestamp
) {}
