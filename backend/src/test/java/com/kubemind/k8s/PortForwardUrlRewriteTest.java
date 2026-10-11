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
        assertThat(PortForwardProxy.rewriteLocation("/login", PREFIX))
            .isEqualTo(PREFIX + "/login");
    }

    @Test
    void rewritesAbsoluteLocationBackToTheTunnelItself() {
        // argocd-server answering plain HTTP with an https hop to the Host it saw.
        assertThat(PortForwardProxy.rewriteLocation("https://127.0.0.1:42363/applications?x=1", PREFIX))
            .isEqualTo(PREFIX + "/applications?x=1");
        assertThat(PortForwardProxy.rewriteLocation("http://localhost:8080", PREFIX))
            .isEqualTo(PREFIX + "/");
        assertThat(PortForwardProxy.rewriteLocation("http://[::1]:9000/a", PREFIX))
            .isEqualTo(PREFIX + "/a");
    }

    @Test
    void leavesProtocolRelativeLocationAlone() {
        assertThat(PortForwardProxy.rewriteLocation("//evil.example.com/x", PREFIX))
            .isEqualTo("//evil.example.com/x");
    }

    @Test
    void leavesAbsoluteLocationToAnotherHostAlone() {
        assertThat(PortForwardProxy.rewriteLocation("https://other-host/x", PREFIX))
            .isEqualTo("https://other-host/x");
    }

    @Test
    void leavesDocumentRelativeLocationAlone() {
        assertThat(PortForwardProxy.rewriteLocation("details?id=1", PREFIX))
            .isEqualTo("details?id=1");
    }

    @Test
    void addsPathToCookieThatHadNone() {
        assertThat(PortForwardProxy.rewriteSetCookie("session=xyz; HttpOnly", PREFIX, false))
            .isEqualTo("session=xyz; HttpOnly; Path=" + PREFIX);
    }

    @Test
    void rewritesRootCookiePath() {
        assertThat(PortForwardProxy.rewriteSetCookie("session=xyz; Path=/; HttpOnly", PREFIX, false))
            .isEqualTo("session=xyz; Path=" + PREFIX + "; HttpOnly");
    }

    @Test
    void prefixesNonRootCookiePath() {
        assertThat(PortForwardProxy.rewriteSetCookie("session=xyz; Path=/app", PREFIX, false))
            .isEqualTo("session=xyz; Path=" + PREFIX + "/app");
    }

    @Test
    void dropsCookieDomainBecauseItNamesTheAppsHostNotOurs() {
        assertThat(PortForwardProxy.rewriteSetCookie("s=1; Domain=argocd.internal; Path=/", PREFIX, false))
            .isEqualTo("s=1; Path=" + PREFIX);
    }

    @Test
    void dropsSecureWhenTheBrowserIsOnPlainHttpAndKeepsSameSiteValid() {
        // An HTTPS pod's session cookie, proxied to a browser on http:// — kept
        // Secure, the browser would discard it and the app would loop on login.
        assertThat(PortForwardProxy.rewriteSetCookie(
                "argocd.token=t; path=/; SameSite=None; httpOnly; Secure", PREFIX, false))
            .isEqualTo("argocd.token=t; Path=" + PREFIX + "; SameSite=Lax; httpOnly");
    }

    @Test
    void keepsSecureWhenTheBrowserIsOnHttps() {
        assertThat(PortForwardProxy.rewriteSetCookie("s=1; Secure; SameSite=None", PREFIX, true))
            .isEqualTo("s=1; Secure; SameSite=None; Path=" + PREFIX);
    }

    @Test
    void stripsKubemindsOwnCookiesButKeepsTheApps() {
        assertThat(PortForwardProxy.stripKubemindCookies(
                "XSRF-TOKEN=abc; JSESSIONID=SECRET; argocd.token=t; theme=dark"))
            .isEqualTo("argocd.token=t; theme=dark");
        assertThat(PortForwardProxy.stripKubemindCookies("JSESSIONID=SECRET; XSRF-TOKEN=abc"))
            .isEmpty();
    }

    @Test
    void aSameOriginOriginHeaderBecomesTheTunnelsOriginSoTheAppDoesNotRejectItsOwnPage() {
        assertThat(PortForwardProxy.rewriteOrigin("http://0b1e.pf.kubemind.my.kubernetes", "0b1e.pf.kubemind.my.kubernetes",
                "http://127.0.0.1:40000"))
            .isEqualTo("http://127.0.0.1:40000");
    }

    @Test
    void anOriginFromAnotherSiteIsLeftForTheAppToRefuse() {
        assertThat(PortForwardProxy.rewriteOrigin("https://evil.example.com", "0b1e.pf.kubemind.my.kubernetes",
                "http://127.0.0.1:40000"))
            .isEqualTo("https://evil.example.com");
        assertThat(PortForwardProxy.rewriteOrigin("null", "0b1e.pf.kubemind.my.kubernetes", "http://127.0.0.1:40000"))
            .isEqualTo("null");
    }

    @Test
    void refererMovesToTheTunnelAndLosesTheSessionPrefix() {
        assertThat(PortForwardProxy.rewriteReferer("http://kubemind.my.kubernetes/api/port-forward/abc123/d/x?a=1",
                "kubemind.my.kubernetes", "http://127.0.0.1:40000", PREFIX))
            .isEqualTo("http://127.0.0.1:40000/d/x?a=1");
        assertThat(PortForwardProxy.rewriteReferer("http://0b1e.pf.test/d/x", "0b1e.pf.test",
                "http://127.0.0.1:40000", ""))
            .isEqualTo("http://127.0.0.1:40000/d/x");
        assertThat(PortForwardProxy.rewriteReferer("https://other.example.com/p", "0b1e.pf.test",
                "http://127.0.0.1:40000", ""))
            .isEqualTo("https://other.example.com/p");
    }

    // ── At the root of a session's own origin there's no prefix to add ──────

    @Test
    void atAnOriginsRootRedirectsBackToTheTunnelBecomePlainPaths() {
        assertThat(PortForwardProxy.rewriteLocation("http://127.0.0.1:52450/login?x=1", "")).isEqualTo("/login?x=1");
        assertThat(PortForwardProxy.rewriteLocation("/login", "")).isEqualTo("/login");
        assertThat(PortForwardProxy.rewriteLocation("https://grafana.com/docs", "")).isEqualTo("https://grafana.com/docs");
    }

    @Test
    void atAnOriginsRootCookiesKeepTheirPathButStillLoseDomainAndHttpOnlySecure() {
        assertThat(PortForwardProxy.rewriteSetCookie(
                "grafana_session=s; Path=/; Domain=127.0.0.1; HttpOnly; Secure; SameSite=Lax", "", false))
            .isEqualTo("grafana_session=s; Path=/; HttpOnly; SameSite=Lax");
        assertThat(PortForwardProxy.rewriteSetCookie("a=1; HttpOnly", "", false))
            .isEqualTo("a=1; HttpOnly");
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
            PortForwardProxy.rewriteCssUrls(css.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        assertThat(rewritten)
            .contains("url(" + PREFIX + "/img/bg.png)")
            .contains("url('" + PREFIX + "/img/icon.svg')")
            .contains("url(\"https://cdn.example.com/x.png\")");
    }

    @Test
    void extractsCharsetFromContentType() {
        assertThat(PortForwardProxy.extractCharset("text/html; charset=ISO-8859-1"))
            .isEqualTo(Charset.forName("ISO-8859-1"));
        assertThat(PortForwardProxy.extractCharset("text/html; charset=\"utf-8\""))
            .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void defaultsToUtf8WhenNoCharsetGiven() {
        assertThat(PortForwardProxy.extractCharset("text/html")).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void defaultsToUtf8OnUnknownCharset() {
        assertThat(PortForwardProxy.extractCharset("text/html; charset=bogus-charset"))
            .isEqualTo(StandardCharsets.UTF_8);
    }

    private static String rewriteHtml(String html) {
        return new String(
            PortForwardProxy.rewriteHtmlUrls(html.getBytes(StandardCharsets.UTF_8), PREFIX, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    }
}
