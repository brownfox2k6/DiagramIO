package com.diagramio.domain;

/**
 * Cờ bật/tắt các chiến thuật cải tiến Phase 2 theo từng nhóm độc lập,
 * phục vụ đo kiểm tăng dần (ablation) từ mốc baseline C0 trên cùng bản build.
 */
public record FeatureFlags(
        boolean listProjection,
        boolean readCache,
        boolean shareTokenCache,
        boolean etag,
        boolean gzip,
        boolean rateLimiting,
        boolean bcryptBulkhead) {

    /** Trả về cấu hình mặc định tắt toàn bộ cờ Phase 2 để tái hiện mốc baseline C0. */
    public static FeatureFlags allDisabled() {
        return new FeatureFlags(false, false, false, false, false, false, false);
    }
}
