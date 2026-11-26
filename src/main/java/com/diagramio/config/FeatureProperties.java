package com.diagramio.config;

import com.diagramio.domain.FeatureFlags;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình ánh xạ kiểu tường minh cho các cờ bật/tắt chiến thuật Phase 2 tại mục diagramio.features.*. */
@ConfigurationProperties(prefix = "diagramio.features")
public record FeatureProperties(
        boolean listProjection,
        boolean readCache,
        boolean shareTokenCache,
        boolean etag,
        boolean gzip,
        boolean rateLimiting,
        boolean bcryptBulkhead) {

    /** Chuyển cấu hình Spring sang đối tượng thuần Java của tầng domain. */
    public FeatureFlags toDomain() {
        return new FeatureFlags(
                listProjection,
                readCache,
                shareTokenCache,
                etag,
                gzip,
                rateLimiting,
                bcryptBulkhead);
    }
}
