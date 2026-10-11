package com.kubemind.k8s;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where port-forward sessions open in the browser. Blank {@code domain} (the
 * default) means under a path prefix on KubeMind's own origin; set, each
 * session gets its own origin at {@code <session-id>.<domain>} — which needs a
 * wildcard DNS record (and, for https, a wildcard certificate) for that domain
 * pointing at KubeMind. See PortForwardProxy for why the second works with
 * every app and the first doesn't.
 *
 * @param domain e.g. {@code pf.kubemind.example.internal}
 * @param scheme the scheme browsers use to reach that domain: http or https
 * @param port   only when browsers reach it on a non-default port (docker-compose's :8080)
 */
@ConfigurationProperties(prefix = "kubemind.port-forward.subdomain")
public record PortForwardProperties(String domain, String scheme, Integer port) {

    private static final Pattern SESSION_LABEL =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public PortForwardProperties {
        // Tolerates the wildcard spelling ("*.pf.example") an operator may copy from DNS.
        domain = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT)
            .replaceFirst("^\\*\\.", "").replaceAll("^\\.+|\\.+$", "");
        scheme = scheme == null || scheme.isBlank() ? "http" : scheme.strip().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("kubemind.port-forward.subdomain.scheme must be http or https, not '" + scheme + "'");
        }
    }

    public boolean subdomainsEnabled() {
        return !domain.isEmpty();
    }

    public boolean https() {
        return scheme.equals("https");
    }

    /** The session id a request's host names, or null when it isn't a session's own origin. */
    public String sessionIdFromHost(String host) {
        if (!subdomainsEnabled() || host == null) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String suffix = "." + domain;
        if (!h.endsWith(suffix)) {
            return null;
        }
        String label = h.substring(0, h.length() - suffix.length());
        return SESSION_LABEL.matcher(label).matches() ? label : null;
    }

    /** The origin a session lives at, e.g. {@code http://<id>.pf.kubemind.example.internal}. */
    public String originFor(String sessionId) {
        boolean defaultPort = port == null || (https() ? port == 443 : port == 80);
        return scheme + "://" + sessionId + "." + domain + (defaultPort ? "" : ":" + port);
    }
}
