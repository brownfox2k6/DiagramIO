package com.diagramio.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.FeatureFlags;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class FeatureConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeatureConfig.class);

    @Test
    void defaults_disableAllPhase2FlagsToMatchBaselineC0() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasSingleBean(FeatureFlags.class);
                    assertThat(context).hasSingleBean(FeatureProperties.class);
                    FeatureFlags flags = context.getBean(FeatureFlags.class);
                    assertThat(flags).isEqualTo(FeatureFlags.allDisabled());
                });
    }

    @ParameterizedTest(name = "{0} enables only its flag")
    @MethodSource("envOverrideCases")
    void envVarOverrides_enableEachFlagIndependently(String envVarName, Function<FeatureFlags, Boolean> getter) {
        contextRunner
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("testEnv", Map.of(envVarName, "true")));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .run(context -> {
                    FeatureFlags flags = context.getBean(FeatureFlags.class);
                    assertThat(getter.apply(flags)).isTrue();
                    assertThat(countEnabledFlags(flags)).isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}=false disables a previously enabled flag")
    @MethodSource("envOverrideCases")
    void envVarOverrides_disableFlagWhenPreviouslyEnabled(
            String envVarName, Function<FeatureFlags, Boolean> getter) {
        if (envVarName.startsWith("DIAGRAMIO_FEATURES_")) {
            contextRunner
                    .withPropertyValues(
                            "diagramio.features.list-projection=true",
                            "diagramio.features.read-cache=true",
                            "diagramio.features.share-token-cache=true",
                            "diagramio.features.etag=true",
                            "diagramio.features.gzip=true",
                            "diagramio.features.rate-limiting=true",
                            "diagramio.features.bcrypt-bulkhead=true")
                    .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("testEnv", Map.of(envVarName, "false"))))
                    .run(context -> {
                        FeatureFlags flags = context.getBean(FeatureFlags.class);
                        assertThat(getter.apply(flags)).isFalse();
                        assertThat(countEnabledFlags(flags)).isEqualTo(6);
                    });
        } else {
            contextRunner
                    .withInitializer(context -> {
                        context.getEnvironment().getPropertySources().addLast(
                                new SystemEnvironmentPropertySource("baseEnv", Map.of(
                                        "FEATURE_LIST_PROJECTION", "true",
                                        "FEATURE_READ_CACHE", "true",
                                        "FEATURE_SHARE_TOKEN_CACHE", "true",
                                        "FEATURE_ETAG", "true",
                                        "FEATURE_GZIP", "true",
                                        "FEATURE_RATE_LIMITING", "true",
                                        "FEATURE_BCRYPT_BULKHEAD", "true")));
                        context.getEnvironment().getPropertySources().addFirst(
                                new SystemEnvironmentPropertySource("testEnv", Map.of(envVarName, "false")));
                        new ConfigDataApplicationContextInitializer().initialize(context);
                    })
                    .run(context -> {
                        FeatureFlags flags = context.getBean(FeatureFlags.class);
                        assertThat(getter.apply(flags)).isFalse();
                        assertThat(countEnabledFlags(flags)).isEqualTo(6);
                    });
        }
    }

    private static Stream<Arguments> envOverrideCases() {
        return Stream.of(
                Arguments.of("DIAGRAMIO_FEATURES_LIST_PROJECTION", (Function<FeatureFlags, Boolean>) FeatureFlags::listProjection),
                Arguments.of("DIAGRAMIO_FEATURES_READ_CACHE", (Function<FeatureFlags, Boolean>) FeatureFlags::readCache),
                Arguments.of("DIAGRAMIO_FEATURES_SHARE_TOKEN_CACHE", (Function<FeatureFlags, Boolean>) FeatureFlags::shareTokenCache),
                Arguments.of("DIAGRAMIO_FEATURES_ETAG", (Function<FeatureFlags, Boolean>) FeatureFlags::etag),
                Arguments.of("DIAGRAMIO_FEATURES_GZIP", (Function<FeatureFlags, Boolean>) FeatureFlags::gzip),
                Arguments.of("DIAGRAMIO_FEATURES_RATE_LIMITING", (Function<FeatureFlags, Boolean>) FeatureFlags::rateLimiting),
                Arguments.of("DIAGRAMIO_FEATURES_BCRYPT_BULKHEAD", (Function<FeatureFlags, Boolean>) FeatureFlags::bcryptBulkhead),
                Arguments.of("FEATURE_LIST_PROJECTION", (Function<FeatureFlags, Boolean>) FeatureFlags::listProjection),
                Arguments.of("FEATURE_READ_CACHE", (Function<FeatureFlags, Boolean>) FeatureFlags::readCache),
                Arguments.of("FEATURE_SHARE_TOKEN_CACHE", (Function<FeatureFlags, Boolean>) FeatureFlags::shareTokenCache),
                Arguments.of("FEATURE_ETAG", (Function<FeatureFlags, Boolean>) FeatureFlags::etag),
                Arguments.of("FEATURE_GZIP", (Function<FeatureFlags, Boolean>) FeatureFlags::gzip),
                Arguments.of("FEATURE_RATE_LIMITING", (Function<FeatureFlags, Boolean>) FeatureFlags::rateLimiting),
                Arguments.of("FEATURE_BCRYPT_BULKHEAD", (Function<FeatureFlags, Boolean>) FeatureFlags::bcryptBulkhead)
        );
    }

    private static int countEnabledFlags(FeatureFlags flags) {
        int count = 0;
        if (flags.listProjection()) count++;
        if (flags.readCache()) count++;
        if (flags.shareTokenCache()) count++;
        if (flags.etag()) count++;
        if (flags.gzip()) count++;
        if (flags.rateLimiting()) count++;
        if (flags.bcryptBulkhead()) count++;
        return count;
    }
}
