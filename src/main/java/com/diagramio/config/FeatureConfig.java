package com.diagramio.config;

import com.diagramio.domain.FeatureFlags;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lắp ghép bean FeatureFlags thuần Java cho toàn ứng dụng từ cấu hình ngoài. */
@Configuration
@EnableConfigurationProperties(FeatureProperties.class)
public class FeatureConfig {

    @Bean
    public FeatureFlags featureFlags(FeatureProperties properties) {
        return properties.toDomain();
    }
}
