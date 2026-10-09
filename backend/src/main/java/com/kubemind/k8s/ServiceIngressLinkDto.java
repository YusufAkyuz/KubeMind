package com.kubemind.k8s;

/** A browsable URL for a Service, through the Ingress named here. */
public record ServiceIngressLinkDto(String ingress, String url) {}
