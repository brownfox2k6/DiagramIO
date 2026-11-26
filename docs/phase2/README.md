# Đặc tả Kiến trúc Tổng thể Phase 2 — DiagramIO (Theo phương pháp ADD)

Tài liệu này đặc tả kiến trúc tổng thể cho **Phase 2** của hệ thống **DiagramIO** dựa trên phương pháp **Attribute-Driven Design (ADD)**, các **Chiến thuật Kiến trúc (Architectural Tactics)** theo *Software Architecture in Practice* (Bass, Clements, Kazman), khung **Key Issues** trong *Microsoft Application Architecture Guide* và các **Cloud Design Patterns** của Microsoft Azure.

Tài liệu đóng vai trò hợp đồng kiến trúc chung ở cổng **M0** cho 3 track phát triển song song, làm căn cứ kiểm thử tải (`S1–S4`), chấm review chéo PR giữa 3 thành viên và đối chiếu kết quả thực nghiệm Before/After cuối kỳ.

---

## 1. Tổng quan & Mục tiêu Kiến trúc Phase 2 (Architectural Drivers & Goals)

### 1.1 Bối cảnh chuyển tiếp từ Phase 1 Baseline

Ở Phase 1 (mốc `ca41f27` trên Kaggle Notebook CPU: **4 vCPU, 31 GB RAM**, Java 21, Spring Boot 3.4.5, PostgreSQL 16), DiagramIO ưu tiên tính đúng đắn nghiệp vụ và ranh giới 3 tầng sạch (`api` -> `domain` <- `infrastructure`, lắp ghép tại `config`). Kết quả đo baseline Phase 1 với payload mặc định rỗng đạt trần thông lượng **~747 RPS** tại 100 Virtual Users (`p95 = 110 ms`, `0.0%` lỗi).

Tuy nhiên, khi chuyển sang tải thực tế với sơ đồ UML/ERD kích thước **100 KB – 1.5 MB** (tối đa **2 MB**), nhiều phiên bản snapshot, hàng nghìn sơ đồ và lưu lượng hỗn hợp có đột biến/tấn công xác thực, kiến trúc baseline Phase 1 bộc lộ các nút thắt cấu trúc:

1. **Nút thắt dữ liệu và ORM:** Mọi API danh sách (`GET /diagrams`, `GET /diagrams/public`, `GET /diagrams/{id}/versions`) và bước kiểm quyền `DiagramAccess` đều nạp thực thể JPA đầy đủ kèm cột `data` (`JSONB` tối đa 2 MB) lên JVM; tìm kiếm tiêu đề dùng `LIKE '%...%'` gây quét toàn bảng (`Sequential Scan`); thiếu index trên `diagram_shares(diagram_id)` và `diagrams(is_public)`; thao tác ghi (`PUT`, snapshot, restore) chở lặp khối `JSONB` nhiều vòng giữa PostgreSQL và JVM; Jackson serialize lại chuỗi JSON thô tốn CPU trên payload lớn.
2. **Nút thắt đọc lặp và truyền tải HTTP:** Mọi request đọc sơ đồ nóng (`GET /diagrams/{id}`, `GET /shares/{token}`) và mọi request có `X-Share-Token` đều truy vấn thẳng PostgreSQL; thiếu `ETag`/`304 Not Modified` và nén `gzip`, làm lãng phí CPU serialize và băng thông trên payload tới 2 MB.
3. **Giới hạn triển khai đơn bản (Single-Instance) & Cô lập tài nguyên:** Hệ thống chạy 1 container ứng dụng không có API Gateway bảo vệ/cân bằng tải, chưa cấu hình Graceful Shutdown, chưa tinh chỉnh HikariCP/JVM cgroup cho đa bản sao, và tác vụ băm mật khẩu BCrypt nặng CPU chạy chung luồng tài nguyên với các API sơ đồ.
4. **Khoảng trống phòng thủ chiều sâu (Defense-in-Depth) & Quan sát:** Chưa có Rate Limiting phân tán cho `/auth/*`, chưa phát hiện tái sử dụng Refresh Token (Token Replay), chưa chặn sớm request body chunked vượt 2 MB trước khi Jackson dựng cây JSON, chưa tách profile `prod` fail-fast cho `JWT_SECRET`, chưa cấu hình CORS tường minh, chưa chặn mật khẩu > 72 byte UTF-8 trước BCrypt, và thiếu metrics chi tiết (Prometheus/Actuator).

### 1.2 Cấu trúc thực thi Phase 2 theo Gate và 3 Track

Phase 2 kế thừa nguyên vẹn mô hình **Layered Modular Monolith** (chi tiết lý do chọn tại [ADR-0001](../adr/0001-layered-modular-monolith-api-gateway-shared-redis.md)) và tổ chức triển khai theo các cổng kiểm soát và 3 Track song song:

| Nhóm / Track | Phạm vi & Ticket liên quan | Mục tiêu trọng tâm |
|---|---|---|
| **Gate M0 — Nền tảng & Đo lường** | `#1` Đặc tả ADD & ADR nền, `#2` Feature Flags, `#3` CI Pipeline, `#4` Observability (Actuator/Prometheus & Probes), `#5` Bộ tải v2 (`S1–S4`) & Data Generator, `#6` Đo Baseline `C0` | Thiết lập khung QAS/ADR, cờ bật/tắt chiến thuật để đo Ablation, tầng quan sát, CI 3 tầng + Docker, bộ kiểm thử tải tái lập trên Kaggle. |
| **Track 1 — Data & Query Optimization** | `#7` B-tree Indexes, `#8` GIN `pg_trgm`, `#9` CQRS-lite Projection danh sách sơ đồ, `#10` Projection & phân trang tùy chọn `versions`, `#11` `DiagramAccess` metadata nhẹ, `#29` Tối ưu đường ghi `PUT`/Snapshot/Restore trong PostgreSQL, `#30` Tối ưu serialize JSONB thô | Giảm số câu SQL/request, triệt tiêu việc kéo thừa cột `data` JSONB tới 2 MB ở mọi đường không cần thiết, loại bỏ `Seq Scan`, giảm CPU serialize JSONB. |
| **Track 2 — Cache, HTTP, Scale-out & Gateway** | `#12` Hạ tầng Redis & Cache Port, `#13` Cache-Aside sơ đồ (after-commit eviction), `#14` Cache `X-Share-Token` (thu hồi tức thì), `#15` `ETag`/`If-None-Match` (`304`), `#16` Gzip có ngưỡng, `#17` Graceful Shutdown/HikariCP/JVM container tuning, `#18` Nginx API Gateway (Routing, Offloading, Gatekeeper, Failover) | Giảm tải đọc PostgreSQL cho sơ đồ nóng và `X-Share-Token`, tiết kiệm băng thông & serialization, vận hành cụm đa bản sao stateless có failover và gatekeeper. |
| **Track 3 — Security, Resiliency & Guardrails** | `#19` Redis Rate Limiting `/auth/*`, `#20` BCrypt Bulkhead & Load Shedding, `#21` Refresh Token Replay Detection (Token Family), `#22` Chặn body > 2 MB / JSON lồng sâu (`413`), `#23` Spring Profiles `dev`/`prod` & Fail-fast secrets, `#24` CORS tường minh, `#25` Chặn mật khẩu > 72 byte UTF-8 | Ngăn bão hòa CPU do Auth Flood, phát hiện đánh cắp phiên, phòng chống OOM DoS và cấu hình sai ở production. |
| **Stretch & Gate Final** | `#28` Xuất sơ đồ bất đồng bộ (Queue-Based Load Leveling), `#26` Benchmark Ablation `C0 -> Full` & Gateway Overhead, `#27` Báo cáo Before/After, đồng bộ OpenAPI/README & tổng duyệt ADR | Chứng minh định lượng đóng góp từng chiến thuật trên cùng phần cứng Kaggle 4 vCPU, đồng bộ tài liệu bàn giao. |

---

## 2. Các Ràng buộc Bất biến từ Phase 1 (Architectural Constraints & Invariants)

Mọi thay đổi thiết kế và mã nguồn trong Phase 2 **tuyệt đối không được phép vi phạm** 6 bất biến nền tảng sau từ Phase 1:

1. **Bất biến 1 — Tương thích ngược 100% Hợp đồng API Phase 1:**
   - Giữ nguyên tiền tố `/api/v1`, toàn bộ đường dẫn endpoint, HTTP methods, cấu trúc JSON response và khung lỗi thống nhất `ErrorResponse` (`timestamp`, `status`, `error`, `message`, `path`).
   - Mọi mở rộng giao thức ở Phase 2 (tham số `page`/`size` trên `GET /diagrams/{id}/versions`, header `ETag`/`If-None-Match` -> `304`, `Content-Encoding: gzip`, `X-Total-Count`, `Retry-After`) đều phải ở dạng tùy chọn (opt-in) hoặc bổ sung header không phá vỡ client cũ.
   - *Kiểm chứng:* Toàn bộ bộ kiểm thử hộp đen `com.diagramio.e2e.**` viết cho Phase 1 phải pass 100% ở cả trạng thái bật lẫn tắt các Feature Flags Phase 2.

2. **Bất biến 2 — Ranh giới Kiến trúc 3 Tầng Sạch:**
   - Tuân thủ chiều phụ thuộc hướng tâm: `com.diagramio.api` -> `com.diagramio.domain` <- `com.diagramio.infrastructure`, lắp ghép bean tại `com.diagramio.config`.
   - Tầng `domain` chỉ được phép sử dụng **Java thuần**: không import `org.springframework..`, `jakarta.persistence..`, `jakarta.servlet..`, `org.hibernate..`, cũng như không import các thư viện Redis/Lettuce, Micrometer, Resilience4j hay Servlet.
   - Mọi năng lực hạ tầng mới (Cache, Feature Flags, Metrics, Rate Limit, Bulkhead) phải được định nghĩa qua Port interface ở `domain` hoặc xử lý ở `api`/`infrastructure`/`config`.
   - *Kiểm chứng:* `ArchitectureTest` (ArchUnit) chạy bắt buộc trong mọi lần test local và CI để khóa các import `org.springframework..`, `jakarta.persistence..`, `jakarta.servlet..`, `org.hibernate..` (và mở rộng chặn thêm thư viện Redis/Micrometer/Resilience4j khi các thư viện này được thêm ở Phase 2).

3. **Bất biến 3 — Xác thực một lần & Kiểm quyền Tập trung qua `Caller` + `DiagramAccess`:**
   - Xác thực JWT (`Authorization: Bearer`) và link chia sẻ (`X-Share-Token`) chỉ thực hiện ở Security Filter; controller nhận `Caller` hoặc `@CurrentUser UUID` qua `CallerArgumentResolver` (không đọc trực tiếp `Authentication` trong controller).
   - Mọi kiểm tra quyền trên tài nguyên sơ đồ trong service phải đi qua `DiagramAccess.require(diagramId, caller, Permission)` (`VIEW`, `VIEW_HISTORY`, `EDIT`, `MANAGE`) với quy tắc mã lỗi nhất quán: `404` (sơ đồ không tồn tại), `401` (người gọi không có tài khoản lẫn link hợp lệ của sơ đồ đó), `403` (đã nhận diện người gọi nhưng không đủ quyền).

4. **Bất biến 4 — Toàn vẹn Dữ liệu & Đồng thời (`409 Conflict` Optimistic Locking & Row Locking):**
   - `PUT /api/v1/diagrams/{id}` phải kiểm tra `version` client gửi lên; nếu lệch với CSDL phải từ chối bằng `409 Conflict` và giữ nguyên trạng thái đã lưu.
   - `DiagramData` vẫn kiểm tra hợp lệ hình dạng `{ "nodes": [...], "edges": [...] }` và giới hạn tối đa **2 MB** trước khi lưu; sơ đồ mới khởi tạo là riêng tư (`is_public = false`) với `{"nodes": [], "edges": []}`, loại chỉ nhận `UML` hoặc `ERD`.
   - Các bản ghi trong `diagram_versions` là bất biến (immutable snapshots). Thao tác lưu snapshot và khôi phục phiên bản phải khóa dòng sơ đồ (`SELECT ... FOR UPDATE`) trong giao dịch qua `TransactionRunner` để đảm bảo số thứ tự `version_number` tăng tuyến tính, không trùng lặp dưới tranh chấp đồng thời.

5. **Bất biến 5 — Khôi phục Phiên bản Nguyên tử (Atomic Version Restore):**
   - Thao tác `POST /api/v1/diagrams/{id}/versions/{version_id}/restore` bắt buộc tự động chụp một bản sao lưu trạng thái hiện tại (`Auto backup before restoring version N`) trước khi chép dữ liệu của phiên bản mục tiêu đè vào `diagrams`, toàn bộ nằm trong **một giao dịch ACID duy nhất**.
   - Nếu có bất kỳ lỗi hoặc xung đột nào, toàn bộ giao dịch phải rollback. Mọi thao tác xóa cache trên Redis chỉ được thực thi **sau khi giao dịch commit thành công** (after-commit eviction).

6. **Bất biến 6 — Thu hồi và Đổi quyền Link Chia sẻ Tức thì:**
   - Ngay sau khi chủ sơ đồ thu hồi link (`DELETE /diagrams/{id}/shares/{share_id}`), đổi quyền (`PATCH /diagrams/{id}/shares/{share_id}`) hoặc xóa sơ đồ (`DELETE /diagrams/{id}`), trạng thái mới phải có hiệu lực **ngay lập tức** từ request kế tiếp trên mọi bản sao ứng dụng (mở link đã thu hồi nhận `404`; sửa bằng link đã hạ xuống `view` nhận `403`).
   - Không được phép tồn tại khoảng trễ mất hiệu lực quyền (stale authorization window) do cache cục bộ hay cache không được xóa sau commit.

---

## 3. Yêu cầu Trọng yếu về Kiến trúc (ASR) & Bảng Quality Attribute Scenarios (QAS)

### 3.1 Tách bạch mục tiêu đo kiểm trên cùng một máy Kaggle 4 vCPU

Trên môi trường benchmark Kaggle Notebook (**4 vCPU, 31 GB RAM**), toàn bộ hệ thống gồm bộ phát tải (k6), Nginx API Gateway, các container `app`, PostgreSQL 16 và Redis đều dùng chung một quỹ **4 vCPU vật lý**. Để tránh ngộ nhận kết quả khi đánh giá kiến trúc, Phase 2 **tách bạch rõ ràng** hai nhóm mục tiêu kiểm chứng:

1. **Nhóm mục tiêu Tăng Hiệu năng (Performance Optimization — đo qua chuỗi Ablation `C0 -> C1 Data -> C2 Cache/HTTP -> C3 Security/Bulkhead`):**
   - Thực hiện trên cùng cấu hình đơn bản sao (`1 app` + PostgreSQL + Redis khi bật Track 2/3) để đo đóng góp thuần túy của từng nhóm chiến thuật: giảm số câu SQL, loại bỏ đọc/chở thừa cột `JSONB` 2 MB, chuyển `Seq Scan` sang `Index Scan`/`Bitmap Index Scan`, tăng Cache Hit trên Redis, giảm payload mạng qua `ETag`/`304` và `Gzip`, giải phóng CPU serialize và cô lập BCrypt.
   - Đây là nơi kỳ vọng **tăng RPS rõ rệt và giảm mạnh độ trễ `p95`/`p99`**.

2. **Nhóm mục tiêu Availability, Stateless Scalability, Gatekeeper & Gateway Overhead (đo trên cấu hình cụm `Nginx + Multi-instance App`):**
   - Khi nhân bản từ 1 lên `2–3` bản sao `app` đứng sau Nginx trên cùng 4 vCPU, tổng năng lực CPU vật lý không tăng thêm (thậm chí chia sẻ CPU với tiến trình Nginx). Do đó, mục tiêu của cụm đa bản sao **không phải** là nhân bội thông lượng vượt trần vật lý 4 vCPU, mà nhằm chứng minh:
     - **Availability (Active Redundancy & Failover):** Dừng đột ngột 1 bản sao `app` đang chạy tải nhưng dịch vụ vẫn tiếp tục phục vụ qua bản sao còn lại với 0 sập toàn cục.
     - **Stateless Consistency:** Cache sơ đồ, thu hồi `X-Share-Token` tức thì và bộ đếm Rate Limit hoạt động nhất quán xuyên suốt các bản sao nhờ Redis dùng chung.
     - **Gatekeeper Security:** PostgreSQL và Redis được cô lập trong mạng Docker nội bộ, chặn request body quá cỡ ngay tại biên Nginx, giữ đúng `shareUrl` qua `X-Forwarded-*`.
     - **Gateway Latency Overhead (Trade-off đo thực nghiệm):** Đo đối chứng trực tiếp `1 app gọi thẳng (Direct)` vs `1 app qua Nginx Gateway` vs `N app qua Nginx Gateway` trên cùng phiên Kaggle để định lượng trung thực độ trễ cộng thêm do lớp reverse proxy biên.

### 3.2 Danh sách Yêu cầu Trọng yếu về Kiến trúc (ASR)

- **ASR-01 (Tối ưu đường đọc/ghi payload lớn):** Loại bỏ việc nạp, chở qua mạng và serialize lại cột `data` (`JSONB` tới 2 MB) ở mọi thao tác không cần nội dung hoặc có thể xử lý trực tiếp trong PostgreSQL.
- **ASR-02 (Truy vấn & tìm kiếm có chỉ mục):** Đảm bảo các truy vấn lọc theo `owner_id`, `is_public`, `diagram_id` (shares) và tìm kiếm chuỗi con không phân biệt hoa thường theo `title` sử dụng chỉ mục PostgreSQL (`B-tree`, `GIN pg_trgm`) thay vì quét tuần tự.
- **ASR-03 (Đọc lặp & truyền tải HTTP hiệu quả):** Đưa trạng thái đọc sơ đồ nóng và phân giải `X-Share-Token` lên Redis dùng chung (kèm xóa sau commit) và hỗ trợ `ETag`/`304`, `Gzip` để giảm tải DB và băng thông.
- **ASR-04 (Phòng vệ chống bão hòa CPU & tấn công phiên):** Phân tách và bảo vệ tài nguyên trước Auth Flood (Rate Limiting phân tán + BCrypt Bulkhead), phát hiện Token Replay trên `refresh_tokens`, chặn payload/JSON độc hại sớm và fail-fast cấu hình bí mật.
- **ASR-05 (Mở rộng ngang không trạng thái & sẵn sàng cao):** Triển khai nhiều bản sao ứng dụng stateless sau Nginx API Gateway (Routing, Offloading, Gatekeeper), hỗ trợ Graceful Shutdown, cấu hình pool/JVM phù hợp container và fallback an toàn khi Redis gián đoạn.
- **ASR-06 (Khả năng triển khai đo đạc Ablation & Quan sát):** Mọi chiến thuật tối ưu có thể bật/tắt độc lập bằng Feature Flags qua biến môi trường để đo kiểm tăng dần từ mốc `C0`; xuất đầy đủ metrics Prometheus và health probes qua Actuator.

### 3.3 Bảng Quality Attribute Scenarios (QAS) Định lượng

Mỗi kịch bản dưới đây được đặc tả theo đủ 6 phần chuẩn ADD (`Source`, `Stimulus`, `Environment`, `Artifact`, `Response`, `Response Measure`) và gắn trực tiếp với 4 kịch bản tải **S1–S4** trên cấu hình **Kaggle 4 vCPU / 31 GB RAM**:

| Mã QAS | Thuộc tính & Kịch bản | Nguồn kích thích (Source) | Kích thích (Stimulus) | Môi trường (Environment) | Thành phần (Artifact) | Phản hồi hệ thống (Response) | Thước đo phản hồi định lượng (Response Measure) |
|---|---|---|---|---|---|---|---|
| **QAS-PERF-01** | **Performance** *(Kịch bản S1 — Closed-model Hồi quy Phase 1 với payload thực tế)* | Virtual Users chạy vòng lặp 5 bước nghiệp vụ (Tạo -> `PUT` sửa -> Snapshot -> Restore -> Tạo & Mở Share) | Lưu lượng liên tục với sơ đồ `JSONB` kích thước thực tế **100 KB – 1.5 MB** | Trên cùng 1 máy Kaggle 4 vCPU; so sánh `C0` vs các bước bật Track 1/2/3 | `Diagram`, `Version`, `Share` services, PostgreSQL, Jackson JSON | Đường `PUT` không tải lại cột `data` cũ lên JVM; Snapshot/Restore chép `data` trực tiếp trong PostgreSQL; tối ưu serialize chuỗi JSONB đã xác thực | - Tỷ lệ lỗi: **0.0%** (bảo toàn 100% bất biến `409`/atomic restore).<br>- Số câu SQL `PUT` & số lần chở `data` trên `PUT`/Snapshot/Restore giảm về **1 chiều** (tiết kiệm ≥ 50% lưu lượng `JSONB` JVM <-> DB).<br>- Giảm **≥ 30%** CPU serialize JSON và cải thiện rõ rệt **RPS & `p95`** so với `C0`. |
| **QAS-PERF-02** | **Performance** *(Kịch bản S2 — Open-model Heavy List, Search & Version History)* | Người dùng đồng thời (k6 arrival-rate) | Tải mở gửi liên tục `GET /diagrams`, `GET /diagrams/public`, `GET /diagrams/{id}/versions` và tìm kiếm `GET /diagrams?title=...` | CSDL chứa hàng nghìn sơ đồ có `data` 100 KB – 1.5 MB và nhiều phiên bản; Kaggle 4 vCPU | `DiagramRepository`, `DiagramVersionRepository`, `DiagramAccess`, PostgreSQL | CQRS-lite Projection chỉ chọn cột metadata; gộp truy vấn đếm; dùng B-tree & GIN `pg_trgm` index; `DiagramAccess` kiểm quyền bằng metadata nhẹ | - Số lần đọc cột `data` JSONB ở API danh sách & kiểm quyền metadata: **0 lần**.<br>- Số câu SQL phân trang danh sách giảm từ tối đa 4 câu xuống **≤ 2 câu**.<br>- `EXPLAIN ANALYZE` dùng `Index Scan`/`Bitmap Index Scan` (0 `Seq Scan` trên các truy vấn đích).<br>- Độ trễ **`p95` giảm ≥ 60%** so với mốc `C0` ở cùng arrival-rate. |
| **QAS-PERF-03** | **Performance** *(Kịch bản S3 — Open-model Read-Heavy Hot Diagrams, `X-Share-Token` & HTTP)* | Khách và người dùng đọc lặp nhóm sơ đồ nóng (k6 arrival-rate) | Tải mở đọc lặp `GET /diagrams/{id}` (kèm `X-Share-Token` / `If-None-Match` / `Accept-Encoding: gzip`) và `GET /shares/{token}` | Chế độ bật Track 2 (Redis Cache-Aside, Share Token Cache, ETag, Gzip); Kaggle 4 vCPU | `DiagramCache`, `ShareTokenCache`, API Controller (`ETag`), HTTP/Nginx Gzip | Phục vụ nội dung sơ đồ nóng và phân giải `X-Share-Token` từ Redis; trả `304` khi `If-None-Match` khớp (sau khi đã kiểm quyền); nén `gzip` với JSON lớn vượt ngưỡng | - Cache Hit trên Redis đạt **≥ 80%** cho sơ đồ nóng và `X-Share-Token` (giảm **≥ 80%** `SELECT` DB tương ứng).<br>- Request khớp `ETag` nhận **`304 Not Modified`** (body `0 B`, `p95 < 15 ms` nội bộ).<br>- Phản hồi JSON lớn nén Gzip giảm **≥ 65%** dung lượng truyền tải.<br>- Độ trễ **`p95` tổng thể S3 giảm ≥ 50%** so với `C0`. |
| **QAS-SEC-01** | **Security & Availability under Contention** *(Kịch bản S4 — Open-model Mixed Workload + Auth Flood)* | Kẻ tấn công / client spam kết hợp người dùng hợp lệ | Dòng request ồ ạt gọi `/auth/login`, `/auth/register`, `/auth/refresh-token` (băm BCrypt nặng CPU) xen kẽ người dùng đọc/ghi sơ đồ | Chế độ bật Track 3 (Redis Rate Limiting + BCrypt Bulkhead); Kaggle 4 vCPU | `AuthRateLimitFilter`, `BCryptBulkhead`, `SecurityConfig`, Redis | Rate Limiter chặn IP/tài khoản vượt ngưỡng; BCrypt Bulkhead khóa trần số phép băm đồng thời và load-shed nhanh khi bão hòa, nhường CPU cho API sơ đồ | - Request vượt ngưỡng nhận **`429`** (hoặc `503` khi Bulkhead đầy) kèm header `Retry-After` và JSON `ErrorResponse` chuẩn; **0 lỗi `500`**.<br>- Độ mạnh BCrypt không bị hạ.<br>- Các API sơ đồ hợp lệ duy trì độ trễ **`p95` ổn định** (không bị kéo vọt lên nhiều giây hay timeout như ở `C0`) và **0% lỗi `500`**. |
| **QAS-SEC-02** | **Security** *(Thu hồi tức thì, Replay Token & Phòng thủ chiều sâu)* | Chủ sơ đồ, kẻ tấn công dùng lại token cũ, hoặc gửi payload độc hại | (a) Thu hồi/hạ quyền `X-Share-Token` trên Node A rồi gọi ngay ở Node B;<br>(b) Gửi lại Refresh Token đã xoay vòng;<br>(c) Gửi body chunked > 2 MB, JSON lồng sâu, mật khẩu > 72 byte UTF-8; hoặc chạy profile `prod` thiếu `JWT_SECRET` | Cụm đa bản sao sau Nginx + Redis + PostgreSQL hoặc khởi động profile `prod` | `ShareService`, `AuthService`, `RequestSizeLimitFilter`, `StartupSecretValidator` | Xóa cache `X-Share-Token` ngay sau commit; phát hiện replay thu hồi toàn bộ token family trong 1 transaction; cắt luồng body trước khi Jackson parse; chặn mật khẩu > 72 byte; fail-fast startup | - Thu hồi/hạ quyền link có hiệu lực **100% ngay từ request kế tiếp** trên mọi node (`404`/`403`).<br>- Replay Refresh Token nhận **`401`** và vô hiệu hóa **100%** token còn lại trong cùng chuỗi phiên.<br>- Payload > 2 MB (kể cả chunked) trả **`413`**; mật khẩu > 72 byte UTF-8 trả **`400`/`401`** (**0 lỗi `500`**).<br>- Profile `prod` thiếu/yếu `JWT_SECRET` thất bại khởi động **100%** (fail-fast). |
| **QAS-AVAIL-01** | **Availability** *(Failover đa bản sao & Graceful Degradation khi Redis lỗi)* | Lỗi tiến trình container hoặc mất kết nối Redis tạm thời | (a) Dừng đột ngột (`SIGKILL`/`SIGTERM`) 1 trong ≥ 2 bản sao `app` giữa lúc đang nhận tải;<br>(b) Tạm dừng/ngắt kết nối container Redis | Cụm Docker Compose: `Nginx + ≥ 2 App Replicas + Redis + PostgreSQL` | `Nginx upstream`, Spring Boot Graceful Shutdown, Redis Cache/RateLimiter Adapters | Nginx phát hiện upstream lỗi và chuyển hướng sang bản sao sống; container nhận `SIGTERM` rút tải êm; các adapter Redis tự fallback về PostgreSQL / fail-open có log & metric | - Sau khi mất 1 bản sao, Nginx chuyển hướng sang bản sao sống trong **≤ 5 s**, yêu cầu trên node `SIGTERM` rút tải êm trong **≤ 30 s**, dịch vụ duy trì liên tục với tỷ lệ lỗi chuyển mạch **≤ 0.5%** (0 sập toàn cục).<br>- Khi Redis ngắt kết nối: fallback về PostgreSQL trong giới hạn timeout Redis (**< 100 ms**) với **0% lỗi `500`**; readiness probe phản ánh đúng trạng thái khi tính năng Redis bật. |
| **QAS-SCALE-01** | **Scalability & Gateway Overhead** *(Cô lập Gatekeeper & Độ trễ biên Nginx)* | Client bên ngoài hoặc công cụ benchmark đối chứng | So sánh chạy tải trực tiếp vào `1 app` vs qua `Nginx Gateway` (`1 app` và `N apps`); kiểm tra port nội bộ và `shareUrl` | Kaggle 4 vCPU / Docker Compose chuẩn | `nginx.conf`, Docker internal network, `DiagramShareController` (`X-Forwarded-*`) | Nginx chặn port DB/Redis khỏi bên ngoài (Gatekeeper), giới hạn body ở biên, gánh nén Gzip (Offloading), chuyển tiếp `X-Forwarded-Host`/`Proto` chuẩn | - Ở cấu hình triển khai chuẩn, **0 port** của PostgreSQL/Redis lộ ra host ngoài.<br>- `shareUrl` trỏ đúng địa chỉ ngoài của Gateway; toàn bộ bộ E2E Phase 1 pass **100%** qua cổng Gateway.<br>- Đo và báo cáo tường minh **Gateway latency overhead** (`Direct` vs `Via Nginx`), giữ mức tăng độ trễ biên `p50`/`p95` ở mức **≤ 5 ms** (hoặc **≤ 10%**) trên localhost Kaggle. |
| **QAS-DEPLOY-01** | **Deployability & Observability** *(Feature Flags Ablation, Metrics & CI Gate)* | Kỹ sư vận hành / Pipeline CI GitHub Actions | Chuyển đổi cấu hình cờ `diagramio.features.*` qua biến môi trường hoặc tạo Pull Request mới | Môi trường local, Docker, Kaggle, GitHub Actions | `FeatureProperties`, Actuator/Prometheus, `.github/workflows` | Bật/tắt từng chiến thuật không cần sửa code; xuất histogram HTTP `p95`/`p99`, HikariCP, JVM, Redis; CI chạy Unit + ArchUnit + Testcontainers E2E + Docker build | - Tắt toàn bộ cờ -> tái lập chính xác hành vi `C0` và pass **100%** test Phase 1.<br>- `/actuator/prometheus` và `/actuator/health/{liveness,readiness}` hoạt động độc lập, không lộ thông tin nhạy cảm.<br>- CI pass **100%** mọi tầng kiểm thử và đóng gói Docker trước khi merge. |

---

## 4. Key Issues, Chiến thuật Kiến trúc (SEI Tactics), Mẫu Kiến trúc (Azure Patterns) & Đánh đổi (Trade-offs)

### 4.1 Ánh xạ Key Issues -> Quality Attributes -> Tactics -> Azure Patterns

Theo khung phân tích **Key Issues** của *Microsoft Application Architecture Guide*, bảng dưới đây liên kết từng vấn đề trọng tâm của Phase 2 với thuộc tính chất lượng, chiến thuật SEI và mẫu kiến trúc được chọn:

| Key Issue (Microsoft App Arch Guide) | Vấn đề cụ thể trong DiagramIO | Thuộc tính chất lượng | Chiến thuật SEI (Architectural Tactics) | Mẫu thiết kế (Azure / Enterprise Patterns) | Tickets thực thi |
|---|---|---|---|---|---|
| **Data Access & Persistence** | Quét tuần tự (`Seq Scan`), ORM nạp thừa cột `JSONB` 2 MB khi liệt kê/kiểm quyền, đếm trang lặp 4 SQL, chép `JSONB` nhiều vòng khi ghi/restore, Jackson duyệt lại chuỗi JSON thô | Performance | *Reduce Computational Overhead*, *Increase Resource Efficiency* | **Index Table** (B-tree, GIN `pg_trgm`), **CQRS-lite (Read Model Projection)**, In-DB SQL Copy | `#7`, `#8`, `#9`, `#10`, `#11`, `#29`, `#30` |
| **Caching & State Management** | Đọc lặp sơ đồ nóng và tra `diagram_shares` trên từng request `X-Share-Token`; cần đồng bộ tức thì giữa nhiều bản sao | Performance, Security (Consistency) | *Maintain Multiple Copies of Data*, *Revoke Access* (After-commit eviction), *Graceful Degradation* | **Cache-Aside** (Redis 1 tầng dùng chung) | `#12`, `#13`, `#14` |
| **Communication & Network Efficiency** | Truyền tải lặp payload JSON tới 2 MB gây nghẽn băng thông và CPU serialize | Performance | *Manage Event Arrival / Reduce Demand* (Conditional Request), *Increase Resource Efficiency* (Compression) | HTTP Conditional Request (`ETag`/`304`), **Gateway Offloading** (Gzip biên) | `#15`, `#16`, `#18` |
| **Concurrency, Scalability & Availability** | Một bản sao duy nhất dễ thành điểm lỗi đơn; đóng container ngang làm đứt giao dịch; pool DB không tương xứng khi scale | Availability, Scalability | *Active Redundancy*, *Removal from Service* (Graceful Shutdown), *Bound Execution Times*, *Ping/Echo & Monitor* | **Gateway Routing**, **Health Endpoint Monitoring** (Liveness/Readiness), Stateless Replicas | `#4`, `#17`, `#18` |
| **Security & Resource Contention** | Auth Flood làm BCrypt chiếm hết 4 vCPU; Replay Refresh Token; OOM từ body chunked/JSON sâu; lộ secret ở `prod`; mật khẩu > 72 byte gây 500; thiếu CORS | Security, Availability | *Limit Access*, *Limit Exposure*, *Throttle / Rate Limit*, *Manage Event Arrival / Bound Queue* (Bulkhead + Load Shedding), *Detect Intrusion*, *Validate Input*, *Self-test* | **Gatekeeper**, **Throttling / Rate Limiting**, **Bulkhead** + Load Shedding | `#19`, `#20`, `#21`, `#22`, `#23`, `#24`, `#25` |
| **Deployment, Modularity & Workload Spikes** | Cần đo tách bạch từng cải tiến trên cùng build; kiểm soát chất lượng tự động; tránh tác vụ xuất nặng chặn API chính | Deployability, Modifiability, Performance | *Defer Binding* (Feature Flags), *Restrict Dependencies* (ArchUnit), *Encapsulate* & *Use an Intermediary* (Ports & Adapters), *Introduce Concurrency* | **Feature Toggle / External Config**, **Queue-Based Load Leveling** & **Competing Consumers** *(Stretch)* | `#2`, `#3`, `#28` |

### 4.2 Phân tích Đánh đổi Kiến trúc (Trade-off Analysis)

Không có chiến thuật nào mang lại lợi ích miễn phí. Dưới đây là 6 đánh đổi cốt lõi được chấp nhận có chủ đích trong Phase 2:

1. **Performance đọc/tìm kiếm vs. Chi phí ghi & dung lượng đĩa (`#7`, `#8`):**
   - *Đánh đổi:* Thêm B-tree indexes (`owner_id + updated_at`, partial index `is_public + updated_at`, `diagram_shares.diagram_id`) và `GIN (title gin_trgm_ops)` làm chậm nhẹ thao tác `INSERT`/`UPDATE` tiêu đề và tăng dung lượng lưu trữ index.
   - *Quyết định:* Chấp nhận vì tần suất đọc danh sách và tìm kiếm cao hơn nhiều lần tần suất tạo mới/đổi tiêu đề; không đánh index lên cột `data` (`JSONB`) vì không truy vấn vào bên trong cấu trúc nodes/edges.
2. **Performance vs. Bảo mật tức thì & Độ phức tạp đồng bộ (`#12`, `#13`, `#14`):**
   - *Đánh đổi:* Cache `X-Share-Token` và nội dung sơ đồ giúp giảm tải PostgreSQL > 80%, nhưng bất kỳ lỗi chậm xóa cache nào cũng có thể làm rò rỉ quyền truy cập sau khi link bị thu hồi (vi phạm Bất biến 6) hoặc đọc dữ liệu cũ sau `PUT`/`restore`.
   - *Quyết định:* Chọn **Redis 1 tầng dùng chung** thay vì cache 2 tầng (Caffeine L1 + Redis L2) để tránh rủi ro lệch bộ nhớ cục bộ giữa các bản sao; bắt buộc xóa cache **sau khi transaction commit** và đặt TTL ngắn để phòng hộ.
3. **Security & Availability của luồng chính vs. Từ chối đột biến đăng nhập (`#19`, `#20`):**
   - *Đánh đổi:* Rate Limiting trên Redis và Bulkhead giới hạn số phép băm BCrypt đồng thời bảo vệ 4 vCPU khỏi sập toàn cục khi bị Auth Flood, nhưng có thể trả `429`/`503` (kèm `Retry-After`) cho một số request đăng nhập hợp lệ nếu xảy ra đột biến vượt trần cục bộ cùng một thời điểm.
   - *Quyết định:* Chấp nhận vì bảo vệ tính sẵn sàng của luồng đọc/sửa sơ đồ cốt lõi và giữ nguyên độ mạnh cost factor của BCrypt quan trọng hơn việc để hàng đợi đăng nhập phình to gây timeout dây chuyền.
4. **Băng thông mạng vs. Chu kỳ CPU nén (`#15`, `#16`, `#18`):**
   - *Đánh đổi:* Nén `gzip` giảm > 65% kích thước phản hồi JSONB lớn nhưng tiêu tốn CPU trên máy 4 vCPU.
   - *Quyết định:* Kết hợp `ETag`/`304` (tiết kiệm cả CPU lẫn băng thông khi nội dung không đổi) và chỉ nén `gzip` cho payload vượt ngưỡng kích thước tối thiểu, đồng thời ưu tiên đẩy việc nén ra biên Nginx (*Gateway Offloading*) khi chạy cụm để giảm tải cho JVM.
5. **Availability / Gatekeeper / Offloading vs. Độ trễ cộng thêm của Gateway (`#18`, `#26`):**
   - *Đánh đổi:* Đưa toàn bộ lưu lượng qua Nginx API Gateway thêm 1 hop mạng nội bộ (tăng nhẹ độ trễ mỗi request) và chia sẻ CPU với app trên cùng máy Kaggle.
   - *Quyết định:* Chấp nhận và **đo bóc tách riêng** độ trễ tăng thêm này trong Benchmark cuối kỳ (`#26`) để đổi lấy khả năng failover tự động, cân bằng tải và cô lập tầng dữ liệu trong mạng nội bộ.
6. **Tính tổng quát ORM vs. Hiệu năng SQL chuyên biệt PostgreSQL (`#29`, `#30`):**
   - *Đánh đổi:* Thực hiện `UPDATE ... WHERE version = ?` có điều kiện, chép cột `JSONB` trực tiếp trong DB khi tạo snapshot/restore (`INSERT ... SELECT`, `UPDATE ... FROM`) và tối ưu serialize JSON ở biên `api`/`infrastructure` trên dữ liệu `DiagramData` đã được kiểm định làm mã tầng hạ tầng phụ thuộc hơn vào cú pháp PostgreSQL/Jackson cụ thể.
   - *Quyết định:* Cô lập hoàn toàn các tối ưu này tại tầng `infrastructure`/`api`, giữ nguyên hợp đồng `DiagramData` ở `domain` (tuyệt đối không truyền chuỗi JSON thô xuyên qua `domain`) và bọc sau Feature Flag để kiểm chứng tính tương đương hành vi với JPA chuẩn.

---

## 5. Bảng Tiêu chí Review Chéo PR giữa 3 Thành viên (PR Review Rubric)

Nếu Phase 1 chia 4 phần nghiệp vụ cho 4 thành viên (`README.md`), thì ở Phase 2 công việc kỹ thuật được tổ chức theo **3 Track song song (Data, Cache/Scale, Security)** với 3 thành viên phụ trách review chéo lẫn nhau. Để bảo đảm chất lượng đồng đều giữa 3 Track và mọi PR đều đóng góp vào bảng đánh giá cuối kỳ, mỗi PR trước khi merge vào `main` bắt buộc phải được **ít nhất 1 thành viên từ Track khác** rà soát và chấm điểm theo bảng Rubric dưới đây.

### 5.1 Điều kiện chặn bắt buộc (Hard Blockers — Vi phạm bất kỳ mục nào = Yêu cầu sửa ngay, không merge)

- [ ] **HB-1:** `ArchitectureTest` (ArchUnit) thất bại hoặc có import trái phép (`Spring`, `JPA`, `Servlet`, `Redis`, `Resilience4j`, `Micrometer`) lọt vào `com.diagramio.domain`.
- [ ] **HB-2:** Vi phạm bất kỳ bất biến nào trong 6 bất biến Phase 1 (phá vỡ hợp đồng `/api/v1`, làm fail `com.diagramio.e2e.**`, mất `409 Conflict` khi lệch `version`, làm `restore` không nguyên tử, hoặc để trễ thu hồi `X-Share-Token`).
- [ ] **HB-3:** PR thuộc nhóm chiến thuật đo Ablation nhưng thiếu Feature Flag (hoặc làm thay đổi hành vi mốc `C0` khi toàn bộ cờ ở trạng thái tắt).
- [ ] **HB-4:** Pipeline CI GitHub Actions chưa xanh đầy đủ (Unit + ArchUnit + Testcontainers E2E + Docker Build).

### 5.2 Thang điểm đánh giá chất lượng (Tổng 100 điểm — Yêu cầu đạt ≥ 85/100 và không có Hard Blocker)

| Hạng mục | Trọng số | Tiêu chí đạt điểm tối đa | Dấu hiệu bị trừ điểm / Yêu cầu chỉnh sửa |
|---|---|---|---|
| **1. Kiến trúc 3 Tầng & Tách biệt Trách nhiệm** | **20 đ** | - `domain` thuần Java 100%, dùng Port/Adapter sạch cho hạ tầng mới.<br>- Controller mỏng, dùng `Caller`/`@CurrentUser`, quyền đi qua `DiagramAccess`.<br>- Giao dịch tuân thủ đúng bảng quy tắc `TransactionRunner` (ngắn gọn, không gọi I/O mạng hay xóa cache bên trong transaction). | - Trừ 10–20 đ nếu đặt logic nghiệp vụ trong controller/filter, mở transaction thừa cho pure read, hoặc xóa cache trước khi commit DB. |
| **2. Tương thích Ngược & Toàn vẹn Nghiệp vụ** | **25 đ** | - Toàn bộ E2E Phase 1 pass không sửa assertion cũ.<br>- Khung lỗi JSON thống nhất (`ErrorResponse`) cho cả các mã mới (`413`, `429`, `503`).<br>- Xử lý an toàn cạnh biên đồng thời (`SELECT ... FOR UPDATE`, optimistic locking, fallback khi Redis lỗi không trả `500`). | - Trừ 10–25 đ nếu trả sai mã HTTP (`500` thay vì `400/413/429`), rò rỉ dữ liệu riêng tư qua cache/ETag `304`, hoặc sai quy tắc `401`/`403`/`404`. |
| **3. Feature Flags & Khả năng Đo Ablation** | **15 đ** | - Chiến thuật mới bật/tắt sạch sẽ qua `diagramio.features.*` / biến môi trường.<br>- Có kiểm thử tự động xác nhận cả 2 nhánh `flag=false` (hành vi `C0`) và `flag=true` (hành vi tối ưu). | - Trừ 5–15 đ nếu hard-code bật luôn tối ưu không tắt được khi đo `C0 -> Full`, hoặc tắt cờ nhưng vẫn chạy nhánh mới. |
| **4. Kiểm thử Tự động & Bằng chứng Định lượng** | **25 đ** | - Đủ Unit + Integration (Testcontainers PostgreSQL/Redis thật) cho cả happy path và failure/security path.<br>- Có bằng chứng định lượng bất biến phần cứng phù hợp ticket: số câu SQL/request, số lần chở cột `data`, kế hoạch `EXPLAIN ANALYZE`, hoặc metrics Prometheus. | - Trừ 10–25 đ nếu thiếu test luồng lỗi/tranh chấp đồng thời, dùng mock thay cho PostgreSQL/Redis ở chỗ cần kiểm tra hành vi thật, hoặc thiếu số liệu kiểm chứng hiệu quả. |
| **5. Tài liệu ADR, OpenAPI & Trade-offs** | **15 đ** | - Tạo/cập nhật ADR theo đúng [mẫu chuẩn 6 mục](../adr/0000-adr-template.md) trong `docs/adr/`, liên kết đúng mã `QAS-*`.<br>- Phân tích ít nhất 1 phương án đã loại và đánh đổi trung thực.<br>- Cập nhật ghi chú/OpenAPI nếu có tham số hoặc header tùy chọn mới. | - Trừ 5–15 đ nếu ADR sơ sài thiếu phương án loại/đánh đổi, dùng thuật ngữ không nhất quán với ADD/Tactics, hoặc quên cập nhật tài liệu hợp đồng API khi thêm header/param. |

### 5.3 Quy trình Review Chéo trong Nhóm 3 Thành viên

1. **Tác giả PR:** Điền rõ số Issue, mã `QAS-*` liên quan, trạng thái cờ Feature Flag mặc định, dán kết quả chạy test local/CI và liên kết file ADR mới (nếu ticket yêu cầu).
2. **Người Review (từ Track khác):** Kiểm tra lần lượt 4 Hard Blockers trước -> chấm 5 hạng mục theo bảng 5.2 -> ghi nhận cụ thể các điểm mạnh/điểm cần sửa ngay trong bình luận PR.
3. **Điều kiện Merge:** Không vi phạm `HB-1..HB-4`, tổng điểm tự đánh giá/review đạt `≥ 85/100`, mọi bình luận chặn đã được giải quyết và CI xanh.
