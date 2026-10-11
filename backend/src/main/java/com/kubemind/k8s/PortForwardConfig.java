package com.kubemind.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(PortForwardProperties.class)
class PortForwardConfig {

    private static final Logger log = LoggerFactory.getLogger(PortForwardConfig.class);

    @Bean
    FilterRegistrationBean<ForwardHostFilter> forwardHostFilter(PortForwardProperties properties,
                                                                PortForwardService service,
                                                                PortForwardProxy proxy) {
        if (properties.subdomainsEnabled()) {
            log.info("Port-forward sessions open on their own origins: {}", properties.originFor("<session-id>"));
        }
        var registration = new FilterRegistrationBean<>(new ForwardHostFilter(properties, service, proxy));
        // After ForwardedHeaderFilter (HIGHEST_PRECEDENCE), which resolves the
        // public host this keys on. Before Spring Security (-100), so a session's
        // origin never reaches KubeMind's own API, and before FormContentFilter
        // (-9900), which would consume a proxied PUT/PATCH form body.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
