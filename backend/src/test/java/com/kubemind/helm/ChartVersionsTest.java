package com.kubemind.helm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The badge these answers drive invites a one-click upgrade, so a false "newer
 * available" is an invitation to downgrade a running release.
 */
class ChartVersionsTest {

    @Test
    void recognisesAGenuinelyNewerChart() {
        assertThat(ChartVersions.isNewer("11.2.0", "10.5.15")).isTrue();
        assertThat(ChartVersions.isNewer("10.6.0", "10.5.15")).isTrue();
        assertThat(ChartVersions.isNewer("10.5.16", "10.5.15")).isTrue();
    }

    /** A string comparison would call "10.5.15" newer than "10.5.9" — and "9" newer than "10". */
    @Test
    void comparesSegmentsAsNumbersNotText() {
        assertThat(ChartVersions.isNewer("10.5.9", "10.5.15")).isFalse();
        assertThat(ChartVersions.isNewer("9.0.0", "10.0.0")).isFalse();
    }

    @Test
    void neverCallsTheSameVersionAnUpdate() {
        assertThat(ChartVersions.isNewer("10.5.15", "10.5.15")).isFalse();
        assertThat(ChartVersions.isNewer("v1.2.0", "1.2.0")).isFalse();
        assertThat(ChartVersions.isNewer("1.2", "1.2.0")).isFalse();
    }

    /** A repository that has fallen behind must not advertise a downgrade. */
    @Test
    void neverOffersAnOlderChartAsAnUpdate() {
        assertThat(ChartVersions.isNewer("10.4.0", "10.5.15")).isFalse();
    }

    @Test
    void ordersPreReleasesBelowTheirFinalVersion() {
        assertThat(ChartVersions.isNewer("1.2.0", "1.2.0-rc1")).isTrue();
        assertThat(ChartVersions.isNewer("1.2.0-rc1", "1.2.0")).isFalse();
        assertThat(ChartVersions.isNewer("1.3.0-rc1", "1.2.0")).isTrue();
    }

    /** Build metadata is not a version difference. */
    @Test
    void ignoresBuildMetadata() {
        assertThat(ChartVersions.isNewer("1.2.0+build5", "1.2.0")).isFalse();
    }

    @Test
    void treatsUnknownVersionsAsNothingToOffer() {
        assertThat(ChartVersions.isNewer(null, "1.0.0")).isFalse();
        assertThat(ChartVersions.isNewer("1.0.0", null)).isFalse();
        assertThat(ChartVersions.isNewer("", "1.0.0")).isFalse();
    }
}
