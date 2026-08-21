package com.kubemind.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Links a change somebody made through KubeMind to warnings that appeared right
 * after it — the "what changed?" question, answered from data no desktop
 * Kubernetes client has: an audit log of who did what, when.
 *
 * <h2>Why it looks forward rather than back</h2>
 * The obvious design — take an incident, hunt for a plausible cause — was
 * measured against seven weeks of this project's own audit log and does not
 * work. Two reasons, both fatal:
 *
 * <ul>
 *   <li>An incident's "first seen" is when the profiler first noticed it, not
 *       when it began: Kubernetes discards Events after about an hour, so a run
 *       sees whatever is left in that window (198 patterns shared only 60
 *       distinct first-seen minutes; one minute alone held 22). Time-ordering
 *       against that is meaningless.</li>
 *   <li>Loose matching invents causes. Matching on "same namespace, near enough
 *       in time" produced four links in that sample and all four were wrong —
 *       ArgoCD pods failing had nothing to do with a Prometheus release being
 *       uninstalled.</li>
 * </ul>
 *
 * Starting from the change instead fixes both: the moment is known exactly from
 * the audit row, and the object touched is known exactly, so nothing has to be
 * guessed. The rules below only ever match objects the change actually names or
 * owns — the namespace-wide guess is deliberately absent.
 */
final class ChangeEffectLinker {

    /** How long after a change a new warning is still plausibly about it. */
    static final Duration EFFECT_WINDOW = Duration.ofMinutes(15);

    private ChangeEffectLinker() {}

    /** A change worth watching: an audit row that actually altered the cluster. */
    record Change(String action, String resourceRef, String username, Instant at) {}

    /** A warning the cluster reported, reduced to what matching needs. */
    record Warning(String kind, String namespace, String name, String reason, Instant startedAt) {}

    /**
     * How confident the link is.
     *
     * <p>OWNED_OBJECT exists because the interesting case is indirect: you edit a
     * Deployment and its <em>pods</em> start failing, never the Deployment
     * itself. Both levels are reported so the UI can word them differently.
     */
    enum MatchLevel { SAME_OBJECT, OWNED_OBJECT }

    record ChangeEffect(String action, String resourceRef, String username, String changedAt,
                        String warningSignature, String warningReason,
                        long minutesAfter, String matchLevel) {}

    /**
     * @return one entry per change and affected object, newest change first
     *
     * <p>Deduplicated on purpose. Kubernetes reports one condition as several
     * Events with the same reason — pulling a bad image yields "Failed to pull
     * image", then "Error: ErrImagePull", then "Error: ImagePullBackOff", all of
     * them reason {@code Failed} on the same pod. Emitting a row per Event
     * repeats a single fact three times and reads like a bug. The earliest
     * sighting wins, since that is the one closest to the change.
     */
    static List<ChangeEffect> link(List<Change> changes, List<Warning> warnings) {
        Map<String, ChangeEffect> earliestPerObject = new LinkedHashMap<>();
        for (Change change : changes) {
            Ref ref = Ref.parse(change.resourceRef());
            if (ref == null) continue;
            for (Warning warning : warnings) {
                if (warning.startedAt() == null || change.at() == null) continue;
                // At or after, not strictly after. A warning already burning
                // beforehand is no evidence about the change, but Event
                // timestamps land on whole seconds and the sharpest real signal
                // — an HPA created against a target that does not exist —
                // reports FailedGetScale in the same second it is created.
                // Requiring "strictly after" would systematically drop exactly
                // the cases where cause and effect are least in doubt.
                if (warning.startedAt().isBefore(change.at())) continue;
                if (warning.startedAt().isAfter(change.at().plus(EFFECT_WINDOW))) continue;

                MatchLevel level = match(ref, warning);
                if (level == null) continue;

                String signature = warning.kind() + "/" + warning.namespace() + "/" + warning.name();
                var effect = new ChangeEffect(
                    change.action(), change.resourceRef(), change.username(), change.at().toString(),
                    signature, warning.reason(),
                    Duration.between(change.at(), warning.startedAt()).toMinutes(),
                    level.name());

                String key = change.resourceRef() + "|" + change.at() + "|" + signature + "|" + warning.reason();
                earliestPerObject.merge(key, effect,
                    (kept, candidate) -> candidate.minutesAfter() < kept.minutesAfter() ? candidate : kept);
            }
        }
        return new ArrayList<>(earliestPerObject.values());
    }

    private static MatchLevel match(Ref ref, Warning warning) {
        if (!ref.namespace().equals(warning.namespace())) return null;

        if (ref.kind().equals(warning.kind()) && ref.name().equals(warning.name())) {
            return MatchLevel.SAME_OBJECT;
        }
        // Kubernetes names generated objects after their owner: a Deployment's
        // pods are "<deployment>-<replicaset>-<pod>", a StatefulSet's are
        // "<set>-0". A Helm release names the resources it renders after itself.
        // The trailing "-" is what keeps "my-prom" from matching "my-promtail".
        if (warning.name().startsWith(ref.name() + "-")) {
            return MatchLevel.OWNED_OBJECT;
        }
        return null;
    }

    /** "Kind/namespace/name", the shape audit rows and warning signatures share. */
    private record Ref(String kind, String namespace, String name) {
        static Ref parse(String resourceRef) {
            if (resourceRef == null) return null;
            String[] parts = resourceRef.split("/", 3);
            // Cluster-scoped refs ("Cluster/eba-ex", "User/bob") have nothing in a
            // namespace to affect, so they are not watched at all.
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) return null;
            return new Ref(parts[0], parts[1], parts[2]);
        }
    }
}
