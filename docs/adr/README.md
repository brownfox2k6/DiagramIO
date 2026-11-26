# Hồ sơ Quyết định Kiến trúc (Architecture Decision Records — ADR)

Thư mục này lưu giữ các **Architecture Decision Records (ADR)** cho quá trình thiết kế và nâng cấp **DiagramIO Phase 2**. Mỗi ADR ghi lại bối cảnh, cơ sở định lượng từ QAS/ASR, quyết định đã chọn, các phương án bị loại, đánh đổi (trade-offs) và cách kiểm chứng thực nghiệm.

---

## 1. Quy ước đặt tên và Vòng đời Trạng thái

- **Đặt tên tệp:** `docs/adr/NNNN-slug-tieng-anh.md` với số thứ tự 4 chữ số tăng dần (`0001`, `0002`, ...).
- **Khung mẫu chuẩn:** Sao chép từ [0000-adr-template.md](0000-adr-template.md). Mọi ADR trong Phase 2 phải điền đủ **6 mục bắt buộc**:
  1. Bối cảnh & Vấn đề (Context & Problem Statement)
  2. Bằng chứng & QAS / ASR liên quan (Evidence & Related QAS / ASR)
  3. Phương án được chọn (Decision Outcome / Chosen Option)
  4. Các phương án đã cân nhắc nhưng loại (Considered & Rejected Alternatives — tối thiểu ≥ 1 phương án)
  5. Hệ quả & Đánh đổi (Consequences & Trade-offs)
  6. Cách kiểm chứng & Tiêu chí nghiệm thu (Verification & Compliance)
- **Trạng thái (Status):**
  - `Proposed`: Đang thảo luận và chờ review chéo.
  - `Accepted`: Đã thống nhất và áp dụng vào mã nguồn/tài liệu.
  - `Superseded by ADR-XXXX`: Đã bị thay thế bởi quyết định mới hơn.
  - `Deprecated`: Không còn hiệu lực.

---

## 2. Danh mục ADR hiện có

| Mã ADR | Tiêu đề | Trạng thái | Ticket / QAS liên quan |
|---|---|---|---|
| [ADR-0000](0000-adr-template.md) | Khung mẫu ADR chuẩn cho DiagramIO Phase 2 | Template | `#1` |
| [ADR-0001](0001-layered-modular-monolith-api-gateway-shared-redis.md) | Giữ kiến trúc Layered Modular Monolith kết hợp Nginx API Gateway và Redis dùng chung cho mở rộng ngang không trạng thái | Accepted | `#1` (`QAS-PERF-01..03`, `QAS-SEC-01..02`, `QAS-AVAIL-01`, `QAS-SCALE-01`, `QAS-DEPLOY-01`) |

---

## 3. Quy hoạch ADR dự kiến cho các Track Phase 2

Các thành viên tiếp tục đánh số tăng dần từ `ADR-0002` khi hoàn thiện từng nhóm ticket trong quá trình thực thi:

| Nhóm / Track | Chủ đề ADR dự kiến | Ticket liên quan |
|---|---|---|
| **Gate M0** | Cơ chế Feature Flags cho Ablation & Tầng quan sát Actuator/Prometheus | `#2`, `#4` |
| **Track 1 — Data** | Chiến lược chỉ mục B-tree & GIN `pg_trgm` trên PostgreSQL | `#7`, `#8` |
| **Track 1 — Data** | CQRS-lite Read Model Projection (cùng CSDL vs tách Read DB) & Tối ưu đường ghi/JSONB | `#9`, `#10`, `#11`, `#29`, `#30` |
| **Track 2 — Cache & Scale** | Redis Cache-Aside 1 tầng dùng chung (vs Caffeine L1 + Redis L2) & Thu hồi tức thì `X-Share-Token` | `#12`, `#13`, `#14` |
| **Track 2 — HTTP & Gateway** | HTTP Conditional Request (`ETag`/`304`), Gzip có ngưỡng & Nginx API Gateway (Routing, Offloading, Gatekeeper) | `#15`, `#16`, `#18` |
| **Track 3 — Security** | Redis Rate Limiting, BCrypt Bulkhead & Load Shedding, Token Family Revocation, Defense-in-Depth Payload/Secrets/CORS & Password 72-byte | `#19`, `#20`, `#21`, `#22`, `#23`, `#24`, `#25` |
| **Stretch & Gate Final** | Xuất sơ đồ bất đồng bộ theo Queue-Based Load Leveling & Tổng duyệt benchmark Before/After | `#26`, `#27`, `#28` |
