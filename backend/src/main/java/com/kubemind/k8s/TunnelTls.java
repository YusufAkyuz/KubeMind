package com.kubemind.k8s;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * TLS for the last hop of a port-forward session only: this backend to a pod,
 * through a tunnel the Kubernetes API server has already authenticated.
 *
 * Certificate verification is off on purpose. In-cluster pods almost always
 * serve a self-signed or cluster-internal certificate issued for a name that
 * isn't 127.0.0.1, and checking it would add nothing: we picked this pod
 * ourselves and reach it over the API server's own authenticated stream.
 * Never use this context for any other connection.
 */
final class TunnelTls {

    private static final SSLContext CONTEXT = build();

    private TunnelTls() {}

    static SSLContext context() {
        return CONTEXT;
    }

    /**
     * Whether the tunnel's far end completes a TLS handshake. A plain-HTTP
     * server answers a ClientHello with garbage or closes, which fails the
     * handshake fast; one that waits for more input fails on the timeout.
     */
    static boolean speaksTls(InetAddress address, int port, Duration timeout) {
        int millis = (int) timeout.toMillis();
        try (SSLSocket socket = (SSLSocket) CONTEXT.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(address, port), millis);
            socket.setSoTimeout(millis);
            socket.startHandshake();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static SSLContext build() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new TrustAll() }, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialise TLS for port-forward tunnels", e);
        }
    }

    // Extended, not a plain X509TrustManager: JSSE wraps a plain one and adds
    // its own hostname check back, which 127.0.0.1 never passes.
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
