package com.diagramio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureFlagsTest {

    @Test
    void allDisabled_turnsOffEveryPhase2Flag() {
        FeatureFlags flags = FeatureFlags.allDisabled();

        assertThat(flags.listProjection()).isFalse();
        assertThat(flags.readCache()).isFalse();
        assertThat(flags.shareTokenCache()).isFalse();
        assertThat(flags.etag()).isFalse();
        assertThat(flags.gzip()).isFalse();
        assertThat(flags.rateLimiting()).isFalse();
        assertThat(flags.bcryptBulkhead()).isFalse();
    }

    @Test
    void constructor_togglesEachFlagIndependently() {
        FeatureFlags flags = new FeatureFlags(true, false, true, false, true, false, true);

        assertThat(flags.listProjection()).isTrue();
        assertThat(flags.readCache()).isFalse();
        assertThat(flags.shareTokenCache()).isTrue();
        assertThat(flags.etag()).isFalse();
        assertThat(flags.gzip()).isTrue();
        assertThat(flags.rateLimiting()).isFalse();
        assertThat(flags.bcryptBulkhead()).isTrue();
    }
}
