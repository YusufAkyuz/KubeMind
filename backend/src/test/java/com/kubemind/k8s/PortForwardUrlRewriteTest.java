package com.kubemind.k8s;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
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
    void rewritesAbsoluteLocationBackToTheTunnelItself() {
        // argocd-server answering plain HTTP with an https hop to the Host it saw.
        assertThat(PortForwardController.rewriteLocation("https://127.0.0.1:42363/applications?x=1", PREFIX))
            .isEqualTo(PREFIX + "/applications?x=1");
        assertThat(PortForwardController.rewriteLocation("http://localhost:8080", PREFIX))
            .isEqualTo(PREFIX + "/");
        assertThat(PortForwardController.rewriteLocation("http://[::1]:9000/a", PREFIX))
            .isEqualTo(PREFIX + "/a");
    }

    @Test
    void leavesProtocolRelativeLocationAlone() {
        assertThat(PortForwardController.rewriteLocation("//evil.example.com/x", PREFIX))
            .isEqualTo("//evil.example.com/x");
    }

    @Test
    void leavesAbsoluteLocationToAnotherHostAlone() {
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
        assertThat(PortForwardController.rewriteSetCookie("session=xyz; HttpOnly", PREFIX, false))
            .isEqualTo("session=xyz; HttpOnly; Path=" + PREFIX);
    }

    @Test
    void rewritesRootCookiePath() {
        assertThat(PortForwardController.rewriteSetCookie("session=xyz; Path=/; HttpOnly", PREFIX, false))
            .isEqualTo("session=xyz; Path=" + PREFIX + "; HttpOnly");
    }

    @Test
    void prefixesNonRootCookiePath() {
        assertThat(PortForwardController.rewriteSetCookie("session=xyz; Path=/app", PREFIX, false))
            .isEqualTo("session=xyz; Path=" + PREFIX + "/app");
    }

    @Test
    void dropsCookieDomainBecauseItNamesTheAppsHostNotOurs() {
        assertThat(PortForwardController.rewriteSetCookie("s=1; Domain=argocd.internal; Path=/", PREFIX, false))
            .isEqualTo("s=1; Path=" + PREFIX);
    }

    @Test
    void dropsSecureWhenTheBrowserIsOnPlainHttpAndKeepsSameSiteValid() {
        // An HTTPS pod's session cookie, proxied to a browser on http:// — kept
        // Secure, the browser would discard it and the app would loop on login.
        assertThat(PortForwardController.rewriteSetCookie(
                "argocd.token=t; path=/; SameSite=None; httpOnly; Secure", PREFIX, false))
            .isEqualTo("argocd.token=t; Path=" + PREFIX + "; SameSite=Lax; httpOnly");
    }

    @Test
    void keepsSecureWhenTheBrowserIsOnHttps() {
        assertThat(PortForwardController.rewriteSetCookie("s=1; Secure; SameSite=None", PREFIX, true))
            .isEqualTo("s=1; Secure; SameSite=None; Path=" + PREFIX);
    }

    @Test
    void stripsKubemindsOwnCookiesButKeepsTheApps() {
        assertThat(PortForwardController.stripKubemindCookies(
                "XSRF-TOKEN=abc; JSESSIONID=SECRET; argocd.token=t; theme=dark"))
            .isEqualTo("argocd.token=t; theme=dark");
        assertThat(PortForwardController.stripKubemindCookies("JSESSIONID=SECRET; XSRF-TOKEN=abc"))
            .isEmpty();
    }

    @Test
    void rewritesHtmlAttributesWithRootAbsolutePaths() {
        String html = "<link href=\"/style.css\"><script src='/app.js'></script>"
            + "<form action=\"/login\"><a href=\"/dashboard\">go</a>";
        String rewritten = rewriteHtml(html);

        assertThat(rewritten)
            .contains("href=\"" + PREFIX + "/style.css\"")
            .contains("src='" + PREFIX + "/app.js'")
            .contains("action=\"" + PREFIX + "/login\"")
            .contains("href=\"" + PREFIX + "/dashboard\"");
    }

    @Test
    void rewritesBaseHrefSoDocumentRelativeReferencesFollow() {
        // Argo CD's index.html: <base href="/"> plus relative script/API paths.
        assertThat(rewriteHtml("<head><base href=\"/\"><script src=\"main.js\"></script>"))
            .isEqualTo("<head><base href=\"" + PREFIX + "/\"><script src=\"main.js\"></script>");
    }

    @Test
    void leavesDocumentRelativeAndExternalHtmlUrlsAlone() {
        String html = "<link href=\"css/style.css\"><img src=\"https://cdn.example.com/logo.png\">"
            + "<script src=\"//cdn.example.com/lib.js\"></script>";

        assertThat(rewriteHtml(html)).isEqualTo(html);
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
            .isEqualTo(Charset.forName("ISO-8859-1"));
        assertThat(PortForwardController.extractCharset("text/html; charset=\"utf-8\""))
            .isEqualTo(StandardCharsets.UTF_8);
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

    private static String rewriteHtml(String html) {
        return new String(
            PortForwardController.rewriteHtmlUrls(html.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    }
}
