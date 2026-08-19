package com.kubemind.helm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures are this installation's own release, read out of a running
 * cluster — the values a USER could fetch from the Helm page while the Secrets
 * page guards the identical data behind an ADMIN gate and an audit record.
 */
class HelmSecretMaskerTest {

    @Test
    void masksTheCredentialsInARealReleasesValues() {
        String masked = HelmSecretMasker.maskValues("""
            auth:
              adminUsername: admin
              adminPassword: admin123
              encryptionKey: RiKiE23AFHj9RbUjcgka3/Z/dvOMLalPhIvK6ehHO+0=
              oidc:
                clientId: kubemind
                clientSecret: UFRJ3ulcHD9RwVrwkzCZ9ISlMCc7XCKv
            postgresql:
              username: kubemind
              password: dbpass
            """);

        assertThat(masked).doesNotContain("admin123", "RiKiE23AFHj9", "UFRJ3ulcHD9", "dbpass");
        // Non-credential configuration has to survive, or the page stops being useful.
        assertThat(masked).contains("adminUsername: admin", "clientId: kubemind", "username: kubemind");
    }

    /**
     * The reason this class exists instead of reusing ai/Redactor: its
     * credential-name pattern knows apiKey/privateKey/accessKey but not a bare
     * "…Key", and the value here is 44 chars so its long-base64 rule
     * (64+) misses it too. This is the AES key protecting every stored
     * kubeconfig.
     */
    @Test
    void masksEncryptionKeyWhichTheGeneralRedactorMisses() {
        String masked = HelmSecretMasker.maskValues(
            "encryptionKey: RiKiE23AFHj9RbUjcgka3/Z/dvOMLalPhIvK6ehHO+0=\n");

        assertThat(masked).doesNotContain("RiKiE23AFHj9");
    }

    /** Masking `enabled: true` because the path says "auth" hides nothing and buries what matters. */
    @Test
    void leavesNonStringSettingsAlone() {
        String masked = HelmSecretMasker.maskValues("""
            auth:
              enabled: true
            tokenRefreshSeconds: 300
            """);

        assertThat(masked).contains("enabled: true").contains("300");
    }

    @Test
    void masksCredentialsInsideLists() {
        String masked = HelmSecretMasker.maskValues("""
            sshKeys:
              - ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB
              - ssh-rsa BBBBB3NzaC1yc2EAAAADAQABAAAB
            """);

        assertThat(masked).doesNotContain("AAAAB3NzaC1", "BBBBB3NzaC1");
    }

    /** Values we cannot parse are values we cannot inspect — do not pass them through. */
    @Test
    void refusesToEmitValuesItCouldNotParse() {
        assertThat(HelmSecretMasker.maskValues("\tthis: [is not: valid yaml"))
            .isEqualTo(HelmSecretMasker.MASK + "\n");
    }

    @Test
    void masksRenderedSecretDataButLeavesEveryOtherResourceIntact() {
        String masked = HelmSecretMasker.maskManifest("""
            ---
            # Source: kubemind/templates/secret.yaml
            apiVersion: v1
            kind: Secret
            metadata:
              name: kubemind
            type: Opaque
            stringData:
              admin-password: "admin123"
              encryption-key: "RiKiE23AFHj9RbUjcgka3"
            ---
            # Source: kubemind/templates/backend-deployment.yaml
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: kubemind-backend
            spec:
              replicas: 1
            """);

        assertThat(masked).doesNotContain("admin123", "RiKiE23AFHj9");
        assertThat(masked).contains("admin-password: " + HelmSecretMasker.MASK);
        // The Deployment is what someone opened the manifest tab to read.
        assertThat(masked).contains("kind: Deployment", "name: kubemind-backend", "replicas: 1");
        // Structure preserved, so the tab still reads as a manifest.
        assertThat(masked).contains("kind: Secret", "type: Opaque");
    }

    @Test
    void masksBase64DataAsWellAsStringData() {
        String masked = HelmSecretMasker.maskManifest("""
            apiVersion: v1
            kind: Secret
            metadata:
              name: creds
            data:
              password: YWRtaW4xMjM=
            """);

        assertThat(masked).doesNotContain("YWRtaW4xMjM=");
    }

    /** A `data:` block in a ConfigMap is configuration, not a credential. */
    @Test
    void doesNotTouchConfigMapData() {
        String manifest = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: settings
            data:
              log-level: debug
            """;

        assertThat(HelmSecretMasker.maskManifest(manifest)).isEqualTo(manifest);
    }

    @Test
    void stopsMaskingWhenTheSecretsDataBlockEnds() {
        String masked = HelmSecretMasker.maskManifest("""
            apiVersion: v1
            kind: Secret
            metadata:
              name: creds
            stringData:
              password: hunter2
            type: Opaque
            """);

        assertThat(masked).doesNotContain("hunter2");
        // `type` sits outside the block and must not be swallowed by it.
        assertThat(masked).contains("type: Opaque");
    }
}
