package com.kubemind.k8s;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied app has no idea it's mounted under /api/port-forward/{id} instead
 * of "/" — these rewrites are what keeps its own redirects, cookies, and
 * HTML/CSS asset references from escaping the tunnel onto KubeMind's own
 * origin. See PortForwardController's class javadoc for what this does and
 * does not cover.
 */
class PortForwardUrlRewriteTest {

    private static final String PREFIX = "/api/port-forward/abc123";

    @Test
    void rewritesRootRelativeLocation() {
        assertThat(PortForwardController.rewriteLocation("/login", PREFIX))
            .isEqualTo(PREFIX + "/login");
    }

    @Test
    void leavesProtocolRelativeLocationAlone() {
        assertThat(PortForwardController.rewriteLocation("//evil.example.com/x", PREFIX))
            .isEqualTo("//evil.example.com/x");
    }

    @Test
    void leavesAbsoluteLocationAlone() {
        assertThat(PortForwardController.rewriteLocation("https://other-host/x", PREFIX))
            .isEqualTo("https://other-host/x");
    }

    @Test
    void leavesDocumentRelativeLocationAlone() {
        assertThat(PortForwardController.rewriteLocation("details?id=1", PREFIX))
            .isEqualTo("details?id=1");
    }

    @Test
    void addsPathToCookieThatHadNone() {
        assertThat(PortForwardController.rewriteSetCookiePath("session=xyz; HttpOnly", PREFIX))
            .isEqualTo("session=xyz; HttpOnly; Path=" + PREFIX);
    }

    @Test
    void rewritesRootCookiePath() {
        assertThat(PortForwardController.rewriteSetCookiePath("session=xyz; Path=/; HttpOnly", PREFIX))
            .isEqualTo("session=xyz; Path=" + PREFIX + "; HttpOnly");
    }

    @Test
    void prefixesNonRootCookiePath() {
        assertThat(PortForwardController.rewriteSetCookiePath("session=xyz; Path=/app", PREFIX))
            .isEqualTo("session=xyz; Path=" + PREFIX + "/app");
    }

    @Test
    void rewritesHtmlAttributesWithRootAbsolutePaths() {
        String html = "<link href=\"/style.css\"><script src='/app.js'></script>"
            + "<form action=\"/login\"><a href=\"/dashboard\">go</a>";
        String rewritten = new String(
            PortForwardController.rewriteHtmlUrls(html.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        assertThat(rewritten)
            .contains("href=\"" + PREFIX + "/style.css\"")
            .contains("src='" + PREFIX + "/app.js'")
            .contains("action=\"" + PREFIX + "/login\"")
            .contains("href=\"" + PREFIX + "/dashboard\"");
    }

    @Test
    void leavesDocumentRelativeAndExternalHtmlUrlsAlone() {
        String html = "<link href=\"css/style.css\"><img src=\"https://cdn.example.com/logo.png\">"
            + "<script src=\"//cdn.example.com/lib.js\"></script>";
        byte[] rewritten = PortForwardController.rewriteHtmlUrls(
            html.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8);

        assertThat(new String(rewritten, StandardCharsets.UTF_8)).isEqualTo(html);
    }

    @Test
    void rewritesCssUrlReferences() {
        String css = "body { background: url(/img/bg.png); } "
            + ".icon { background: url('/img/icon.svg'); } "
            + ".ext { background: url(\"https://cdn.example.com/x.png\"); }";
        String rewritten = new String(
            PortForwardController.rewriteCssUrls(css.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        assertThat(rewritten)
            .contains("url(" + PREFIX + "/img/bg.png)")
            .contains("url('" + PREFIX + "/img/icon.svg')")
            .contains("url(\"https://cdn.example.com/x.png\")");
    }

    @Test
    void extractsCharsetFromContentType() {
        assertThat(PortForwardController.extractCharset("text/html; charset=ISO-8859-1"))
            .isEqualTo(java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    @Test
    void defaultsToUtf8WhenNoCharsetGiven() {
        assertThat(PortForwardController.extractCharset("text/html")).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void defaultsToUtf8OnUnknownCharset() {
        assertThat(PortForwardController.extractCharset("text/html; charset=bogus-charset"))
            .isEqualTo(StandardCharsets.UTF_8);
    }
}
