package com.kubemind.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @Test
    void masksValueWhenEnvNameLooksSensitive() {
        assertThat(Redactor.redactEnvValue("DB_PASSWORD", "hunter2")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redactEnvValue("API_KEY", "abc123")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redactEnvValue("GITHUB_TOKEN", "ghp_xxx")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redactEnvValue("AWS_SECRET_ACCESS_KEY", "xyz")).isEqualTo(Redactor.MASK);
        assertThat(Redactor.redactEnvValue("SPRING_DATASOURCE_PASSWORD", "pg")).isEqualTo(Redactor.MASK);
    }

    @Test
    void keepsHarmlessEnvValues() {
        assertThat(Redactor.redactEnvValue("LOG_LEVEL", "debug")).isEqualTo("debug");
        assertThat(Redactor.redactEnvValue("REPLICAS", "3")).isEqualTo("3");
    }

    @Test
    void redactsInlineCredentialsInLogs() {
        assertThat(Redactor.redactText("connecting with password=hunter2 to db"))
            .doesNotContain("hunter2");
        assertThat(Redactor.redactText("Authorization: Bearer abc123def456ghi789"))
            .doesNotContain("abc123def456ghi789");
        assertThat(Redactor.redactText("token: sk-live-abcdef1234567890"))
            .doesNotContain("sk-live-abcdef1234567890");
    }

    @Test
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpM";
        assertThat(Redactor.redactText("got token " + jwt)).doesNotContain(jwt);
    }

    @Test
    void redactsUrlUserinfo() {
        String redacted = Redactor.redactText("postgres://admin:s3cret@db.example.com:5432/app");
        assertThat(redacted).doesNotContain("s3cret");
        assertThat(redacted).contains("db.example.com");
    }

    @Test
    void redactsPemBlocks() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        assertThat(Redactor.redactText("cert: " + pem)).doesNotContain("MIIEpAIBAAKCAQEA");
    }

    @Test
    void redactsLongBase64Blobs() {
        String blob = "YWRtaW46c3VwZXJzZWNyZXRwYXNzd29yZDEyMzQ1Njc4OTBhYmNkZWZnaGlqa2xtbm9w";
        assertThat(Redactor.redactText("data: " + blob)).doesNotContain(blob);
    }

    @Test
    void leavesNormalLogLinesAlone() {
        String line = "2026-07-02 10:00:01 INFO Starting server on port 8080";
        assertThat(Redactor.redactText(line)).isEqualTo(line);
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(Redactor.redactText(null)).isNull();
        assertThat(Redactor.redactText("")).isEmpty();
        assertThat(Redactor.redactEnvValue("PASSWORD", null)).isNull();
    }
}
