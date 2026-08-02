package com.kubemind.config;

import com.kubemind.cluster.ClusterAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ClusterAccessInterceptor clusterAccessInterceptor;

    public WebMvcConfig(ClusterAccessInterceptor clusterAccessInterceptor) {
        this.clusterAccessInterceptor = clusterAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Every cluster-scoped route goes through the access check — see the
        // interceptor's javadoc for why this isn't per-controller @PreAuthorize.
        registry.addInterceptor(clusterAccessInterceptor)
            .addPathPatterns("/api/clusters/*/**");
    }
}
