package com.kubemind.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the API server's "forbidden" wording into a sentence a person can act on.
 *
 * Kubernetes already says exactly who was refused, which verb, which resource and
 * where — the raw form just buries it:
 *
 * <pre>
 * list: failed to list: secrets is forbidden: User
 * "system:serviceaccount:dev-team:kubemind-developer" cannot list resource
 * "secrets" in API group "" in the namespace "dev-team"
 * </pre>
 *
 * Nothing is dropped in translation: the readable version names the same four
 * facts, so an operator loses no detail and everyone else gains a sentence they
 * can read. When the wording is not one we recognise, the original is passed
 * through untouched rather than replaced by something vaguer.
 */
public final class KubernetesRefusal {

    /**
     * The standard message RBAC produces. The trailing scope is optional because
     * cluster-scoped refusals end in "at the cluster scope" instead of naming a
     * namespace, and some versions omit it entirely.
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
        "User \"([^\"]+)\" cannot (\\S+) resource \"([^\"]+)\" in API group \"([^\"]*)\""
        + "(?: in the namespace \"([^\"]+)\"| at the cluster scope)?");

    /** "system:serviceaccount:<namespace>:<name>" */
    private static final Pattern SERVICE_ACCOUNT = Pattern.compile("system:serviceaccount:([^:]+):(.+)");

    /**
     * @param subject   readable form of who was refused
     * @param verb      the action, as Kubernetes named it ("list", "create", …)
     * @param resource  the resource type, subresource included ("pods/exec")
     * @param namespace null for a cluster-scoped refusal
     */
    public record Refusal(String subject, String verb, String resource, String namespace) {}

    private KubernetesRefusal() {}

    public static Optional<Refusal> parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return Optional.empty();
        Matcher m = FORBIDDEN.matcher(rawMessage);
        if (!m.find()) return Optional.empty();
        return Optional.of(new Refusal(describeSubject(m.group(1)), m.group(2), m.group(3), m.group(5)));
    }

    /**
     * A ServiceAccount's full name is mostly boilerplate; the interesting part is
     * which account, in which namespace.
     */
    private static String describeSubject(String user) {
        Matcher sa = SERVICE_ACCOUNT.matcher(user);
        if (sa.matches()) {
            return "ServiceAccount \"" + sa.group(2) + "\" (namespace " + sa.group(1) + ")";
        }
        return "\"" + user + "\"";
    }

    /**
     * The headline sentence, without advice — callers add whatever hint fits
     * their surface (see HelmCliService for the Helm one).
     */
    public static String describe(Refusal refusal) {
        String where = refusal.namespace() != null
            ? " in namespace " + refusal.namespace()
            : " cluster-wide";
        return refusal.subject() + " is not allowed to " + refusal.verb()
            + " " + refusal.resource() + where + ".";
    }

    /**
     * Full message for a refusal that came from the Kubernetes API directly.
     * Says whose limit it is, because "forbidden" in a dashboard reads as the
     * dashboard's decision when it is really the cluster's.
     */
    public static String explain(String rawMessage) {
        return parse(rawMessage)
            .map(r -> describe(r) + " That limit comes from this cluster's RBAC, not from KubeMind — "
                + "a cluster admin can grant it.")
            .orElse(rawMessage);
    }

    /** Whether a message looks like an RBAC refusal at all. */
    public static boolean isForbidden(String rawMessage) {
        if (rawMessage == null) return false;
        String s = rawMessage.toLowerCase(Locale.ROOT);
        return s.contains("is forbidden:") || s.contains("forbidden: user");
    }
}
