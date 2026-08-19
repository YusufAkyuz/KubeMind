package com.kubemind.helm;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides credentials in Helm release values and manifests.
 *
 * A release's values are where chart authors put database passwords, API
 * tokens and signing keys, and `helm get manifest` renders every Secret the
 * chart defines — KubeMind's own chart writes them as {@code stringData}, so
 * they are not even base64. The Secrets page treats exactly this data as
 * ADMIN-only and audited (SecretService.reveal); this class is what lets the
 * Helm pages hold the same line.
 *
 * <p>Deliberately not {@code ai/Redactor}: its credential-name pattern misses
 * a bare "…Key" — it knows apiKey/privateKey/accessKey but not
 * {@code encryptionKey}, which in this app is the AES key protecting every
 * stored kubeconfig. Masking here errs wide on purpose, and anything hidden is
 * one audited click away.
 */
final class HelmSecretMasker {

    static final String MASK = "[REDACTED]";

    /**
     * Wider than Redactor's: matches a trailing "key" of any kind, so
     * encryptionKey/signingKey/licenseKey are covered rather than only the
     * three spellings that happen to be enumerated.
     */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
        "(?i).*(password|passwd|passphrase|secret|token|credential|auth|cert|salt|dsn"
        + "|connection[_-]?string|keystore|truststore|key|keys)$"
        + "|(?i).*(password|passwd|secret|token|credential|private[_-]?key|api[_-]?key).*");

    /** `data:` / `stringData:` inside a rendered Secret. */
    private static final Pattern SECRET_DATA_BLOCK = Pattern.compile("^(\\s*)(data|stringData):\\s*$");
    private static final Pattern YAML_ENTRY = Pattern.compile("^(\\s*)([^\\s:#][^:]*):\\s*(\\S.*)$");

    private HelmSecretMasker() {}

    /**
     * Masks credential-looking entries in a values document.
     *
     * Only string scalars are touched: masking {@code enabled: true} because the
     * path contains "auth" would bury the configuration a reader came for,
     * without hiding anything.
     *
     * The result is re-serialised and therefore reformatted, which is fine
     * because it is display-only — the editor works on the revealed original
     * (see HelmReleaseService.reveal).
     */
    static String maskValues(String valuesYaml) {
        if (valuesYaml == null || valuesYaml.isBlank()) return valuesYaml;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(valuesYaml);
            if (!(parsed instanceof Map)) return valuesYaml;

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            return new Yaml(options).dump(maskNode(parsed, null));
        } catch (Exception e) {
            // Unparseable values: refuse to hand back something we could not
            // inspect rather than leaking it.
            return MASK + "\n";
        }
    }

    @SuppressWarnings("unchecked")
    private static Object maskNode(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                out.put(name, maskNode(entry.getValue(), name));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                // Carries the owning key down so `sshKeys: [ ... ]` masks its items.
                out.add(maskNode(item, key));
            }
            return out;
        }
        if (node instanceof String value && !value.isEmpty() && isSensitive(key)) {
            return MASK;
        }
        return node;
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).matches();
    }

    /**
     * Masks the data of every Secret in a rendered manifest, leaving the rest of
     * the document untouched.
     *
     * Line-based rather than parse-and-redump: a manifest is many documents and
     * re-serialising all of them to hide part of one would reformat everything a
     * reader is trying to compare against the cluster.
     */
    static String maskManifest(String manifest) {
        if (manifest == null || manifest.isBlank()) return manifest;

        StringBuilder out = new StringBuilder(manifest.length());
        boolean first = true;
        // Documents are separated by a line that is exactly "---".
        for (String document : manifest.split("(?m)^---\\s*$", -1)) {
            if (!first) out.append("---\n");
            first = false;
            out.append(isSecretDocument(document) ? maskSecretData(document) : document);
        }
        return out.toString();
    }

    private static boolean isSecretDocument(String document) {
        return Pattern.compile("(?m)^kind:\\s*Secret\\s*$").matcher(document).find();
    }

    private static String maskSecretData(String document) {
        StringBuilder out = new StringBuilder(document.length());
        String blockIndent = null;
        for (String line : document.split("\n", -1)) {
            Matcher block = SECRET_DATA_BLOCK.matcher(line);
            if (block.matches()) {
                blockIndent = block.group(1);
                out.append(line).append('\n');
                continue;
            }
            if (blockIndent != null) {
                Matcher entry = YAML_ENTRY.matcher(line);
                boolean nested = entry.matches() && entry.group(1).length() > blockIndent.length();
                if (nested) {
                    out.append(entry.group(1)).append(entry.group(2)).append(": ").append(MASK).append('\n');
                    continue;
                }
                // Dedented back out of the block (or a blank line ends it).
                if (!line.isBlank()) blockIndent = null;
            }
            out.append(line).append('\n');
        }
        // split with -1 keeps a trailing empty element for a trailing newline;
        // appending '\n' to it would add one the original did not have.
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
