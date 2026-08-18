package com.kubemind.helm;

/**
 * Just enough semver to decide whether a repository is actually offering
 * something newer than what a release is running.
 *
 * A plain string inequality would call a repository that has fallen behind an
 * "update available" and invite a silent downgrade, which is the one thing this
 * badge must not do.
 */
final class ChartVersions {

    private ChartVersions() {}

    /** True only when {@code candidate} sorts strictly above {@code current}. */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || current == null || candidate.isBlank() || current.isBlank()) return false;
        return compare(candidate, current) > 0;
    }

    static int compare(String a, String b) {
        String[] left = core(a);
        String[] right = core(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = numberAt(left, i);
            int r = numberAt(right, i);
            if (l != r) return Integer.compare(l, r);
        }
        // Equal numerically: a release outranks a pre-release of the same version
        // (1.2.0 is newer than 1.2.0-rc1), which is how semver orders them.
        boolean leftPre = hasPreRelease(a);
        boolean rightPre = hasPreRelease(b);
        if (leftPre == rightPre) return 0;
        return leftPre ? -1 : 1;
    }

    /** Strips a leading "v" and anything from the first "-" or "+" onward. */
    private static String[] core(String version) {
        String v = version.strip();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int cut = indexOfAny(v, '-', '+');
        if (cut >= 0) v = v.substring(0, cut);
        return v.split("\\.");
    }

    private static boolean hasPreRelease(String version) {
        return indexOfAny(version, '-') >= 0;
    }

    private static int indexOfAny(String s, char... chars) {
        for (int i = 0; i < s.length(); i++) {
            for (char c : chars) {
                if (s.charAt(i) == c) return i;
            }
        }
        return -1;
    }

    /** Missing or non-numeric segments count as 0, so "1.2" and "1.2.0" compare equal. */
    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
