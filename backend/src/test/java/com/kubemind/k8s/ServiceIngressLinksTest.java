package com.kubemind.k8s;

import com.kubemind.cluster.ClusterClientFactory;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressLoadBalancerIngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Open via Ingress" next to Forward: for a Service that's already exposed, the
 * real URL beats any tunnel. Only URLs that can actually be opened are offered.
 */
@EnableKubernetesMockClient(crud = true)
class ServiceIngressLinksTest {

    KubernetesClient client; // injected by @EnableKubernetesMockClient

    private KubernetesService service;

    @BeforeEach
    void setUp() {
        var factory = mock(ClusterClientFactory.class);
        when(factory.getClient(0L)).thenReturn(client);
        service = new KubernetesService(factory);
    }

    @Test
    void linksEveryLiteralRouteToTheServiceWithTheRightScheme() {
        create(new IngressBuilder()
            .withNewMetadata().withName("argocd").withNamespace("argocd").endMetadata()
            .withNewSpec()
                .addNewTl().withHosts("argocd.my.kubernetes").endTl()
                .addToRules(rule("argocd.my.kubernetes", "/", "argocd-server"))
                .addToRules(rule("plain.my.kubernetes", "/ui", "argocd-server"))
                .addToRules(rule("other.my.kubernetes", "/", "something-else"))
            .endSpec()
            .build());

        assertThat(service.serviceIngressLinks(0L, "argocd", "argocd-server"))
            .containsExactly(
                new ServiceIngressLinkDto("argocd", "https://argocd.my.kubernetes/"),
                new ServiceIngressLinkDto("argocd", "http://plain.my.kubernetes/ui"));
    }

    @Test
    void wildcardTlsHostsCoverASingleLabel() {
        create(new IngressBuilder()
            .withNewMetadata().withName("web").withNamespace("apps").endMetadata()
            .withNewSpec()
                .addNewTl().withHosts("*.apps.internal").endTl()
                .addToRules(rule("shop.apps.internal", "/", "web"))
                .addToRules(rule("a.b.apps.internal", "/", "web"))
            .endSpec()
            .build());

        assertThat(service.serviceIngressLinks(0L, "apps", "web"))
            .extracting(ServiceIngressLinkDto::url)
            .containsExactly("https://shop.apps.internal/", "http://a.b.apps.internal/");
    }

    @Test
    void skipsRegexPathsAndWildcardHostsBecauseThereIsNoSingleUrl() {
        create(new IngressBuilder()
            .withNewMetadata().withName("rewrites").withNamespace("apps").endMetadata()
            .withNewSpec()
                .addToRules(rule("api.apps.internal", "/api(/|$)(.*)", "web"))
                .addToRules(rule("*.apps.internal", "/", "web"))
            .endSpec()
            .build());

        assertThat(service.serviceIngressLinks(0L, "apps", "web")).isEmpty();
    }

    @Test
    void fallsBackToTheLoadBalancerAddressForHostlessRulesAndDefaultBackends() {
        Ingress ingress = create(new IngressBuilder()
            .withNewMetadata().withName("catch-all").withNamespace("apps").endMetadata()
            .withNewSpec()
                .withDefaultBackend(backend("web"))
                .addToRules(rule(null, "/admin", "web"))
            .endSpec()
            .build());
        ingress.setStatus(new io.fabric8.kubernetes.api.model.networking.v1.IngressStatusBuilder()
            .withNewLoadBalancer()
                .withIngress(new IngressLoadBalancerIngressBuilder().withIp("192.168.252.240").build())
            .endLoadBalancer()
            .build());
        client.network().v1().ingresses().inNamespace("apps").resource(ingress).updateStatus();

        assertThat(service.serviceIngressLinks(0L, "apps", "web"))
            .extracting(ServiceIngressLinkDto::url)
            .containsExactly("http://192.168.252.240/admin", "http://192.168.252.240/");
    }

    @Test
    void noIngressesMeansNoLinks() {
        assertThat(service.serviceIngressLinks(0L, "empty", "web")).isEmpty();
    }

    private Ingress create(Ingress ingress) {
        return client.network().v1().ingresses().inNamespace(ingress.getMetadata().getNamespace())
            .resource(ingress).create();
    }

    private static io.fabric8.kubernetes.api.model.networking.v1.IngressRule rule(String host, String path, String serviceName) {
        return new IngressRuleBuilder()
            .withHost(host)
            .withNewHttp()
                .addToPaths(new HTTPIngressPathBuilder()
                    .withPath(path)
                    .withPathType("Prefix")
                    .withBackend(backend(serviceName))
                    .build())
            .endHttp()
            .build();
    }

    private static io.fabric8.kubernetes.api.model.networking.v1.IngressBackend backend(String serviceName) {
        return new IngressBackendBuilder()
            .withNewService().withName(serviceName).withNewPort().withNumber(80).endPort().endService()
            .build();
    }
}
