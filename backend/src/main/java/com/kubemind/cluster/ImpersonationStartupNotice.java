package com.kubemind.cluster;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Says out loud, at boot, what switching impersonation on actually did — same
 * spirit as PrivilegedFeatures#logMode.
 *
 * Worth a dedicated component because the failure mode is silent and
 * bewildering: with impersonation on and no RBAC bound to the local admin's
 * name, cluster 0 simply renders empty and every action 403s, with nothing in
 * the app's own logs pointing at the cause. Naming the exact binding here turns
 * a support ticket into a copy-paste.
 */
@Component
public class ImpersonationStartupNotice {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationStartupNotice.class);

    private final ImpersonationProperties impersonation;
    private final String adminUsername;

    public ImpersonationStartupNotice(ImpersonationProperties impersonation,
                                      @Value("${kubemind.admin.username:admin}") String adminUsername) {
        this.impersonation = impersonation;
        this.adminUsername = adminUsername;
    }

    @PostConstruct
    void logMode() {
        if (!impersonation.enabled()) {
            return; // default; nothing changed, nothing to warn about
        }
        log.warn("Kubernetes user impersonation ENABLED for the built-in cluster. Calls now act as the "
            + "logged-in user, so that cluster is open to every account and Kubernetes RBAC decides what "
            + "each may do. Local accounts are impersonated too — '{}' included. Without a binding for "
            + "them the built-in cluster will appear empty and every action will be refused: "
            + "kubectl create clusterrolebinding kubemind-admin --clusterrole=cluster-admin --user={}",
            adminUsername, adminUsername);
    }
}
