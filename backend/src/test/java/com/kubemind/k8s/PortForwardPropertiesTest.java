package com.kubemind.k8s;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which hosts are a session's own origin, and what that origin is. */
class PortForwardPropertiesTest {

    private static final String ID = "0b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    @Test
    void blankDomainMeansSessionsOpenUnderAPathPrefix() {
        var props = new PortForwardProperties(null, null, null);

        assertThat(props.subdomainsEnabled()).isFalse();
        assertThat(props.sessionIdFromHost(ID + ".pf.kubemind.my.kubernetes")).isNull();
    }

    @Test
    void recognisesASessionsOwnHost() {
        var props = new PortForwardProperties("pf.kubemind.my.kubernetes", "http", null);

        assertThat(props.sessionIdFromHost(ID + ".pf.kubemind.my.kubernetes")).isEqualTo(ID);
        assertThat(props.sessionIdFromHost(ID.toUpperCase() + ".PF.kubemind.my.kubernetes")).isEqualTo(ID);
    }

    @Test
    void ignoresEveryOtherHost() {
        var props = new PortForwardProperties("pf.kubemind.my.kubernetes", "http", null);

        assertThat(props.sessionIdFromHost("kubemind.my.kubernetes")).isNull();
        assertThat(props.sessionIdFromHost("pf.kubemind.my.kubernetes")).isNull();
        assertThat(props.sessionIdFromHost("grafana.pf.kubemind.my.kubernetes")).isNull();
        assertThat(props.sessionIdFromHost("x." + ID + ".pf.kubemind.my.kubernetes")).isNull();
        assertThat(props.sessionIdFromHost(ID + ".pf.kubemind.my.kubernetes.evil.com")).isNull();
        assertThat(props.sessionIdFromHost(ID + "pf.kubemind.my.kubernetes")).isNull();
        assertThat(props.sessionIdFromHost(null)).isNull();
    }

    @Test
    void buildsTheOriginWithAPortOnlyWhenItIsNotTheDefault() {
        assertThat(new PortForwardProperties("pf.example.internal", "http", null).originFor(ID))
            .isEqualTo("http://" + ID + ".pf.example.internal");
        assertThat(new PortForwardProperties("pf.example.internal", "https", 443).originFor(ID))
            .isEqualTo("https://" + ID + ".pf.example.internal");
        assertThat(new PortForwardProperties("pf.example.internal", "http", 8080).originFor(ID))
            .isEqualTo("http://" + ID + ".pf.example.internal:8080");
    }

    @Test
    void normalisesTheDomainAndRejectsAnUnknownScheme() {
        assertThat(new PortForwardProperties(" .PF.Example.Internal. ", "HTTPS", null).domain())
            .isEqualTo("pf.example.internal");
        assertThat(new PortForwardProperties("*.pf.example.internal", "http", null).domain())
            .isEqualTo("pf.example.internal");
        assertThatThrownBy(() -> new PortForwardProperties("pf.example.internal", "ftp", null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
