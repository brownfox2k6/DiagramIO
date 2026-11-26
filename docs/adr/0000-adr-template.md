# ADR-NNNN: [Tiêu đề ngắn gọn thể hiện quyết định kiến trúc]

- **Trạng thái:** Proposed | Accepted | Superseded by ADR-XXXX | Deprecated
- **Ngày:** YYYY-MM-DD
- **Người đề xuất / Track:** [Tên thành viên — M0 / Track 1 Data / Track 2 Cache-Scale / Track 3 Security]
- **QAS / ASR liên quan:** [Ví dụ: QAS-PERF-02, ASR-01]
- **Ticket liên quan:** [Ví dụ: #9]

---

## 1. Bối cảnh & Vấn đề (Context & Problem Statement)

[Mô tả ngắn gọn bài toán kỹ thuật đang gặp phải ở Baseline hoặc Phase 2, các ràng buộc từ Phase 1 cần giữ nguyên (tương thích ngược API, ranh giới 3 tầng ArchUnit, tính nhất quán dữ liệu, v.v.) và câu hỏi kiến trúc cần ra quyết định.]

## 2. Bằng chứng & QAS / ASR liên quan (Evidence & Related QAS / ASR)

[Đưa ra số liệu thực đo trước thay đổi (từ Baseline `C0`, kết quả `EXPLAIN ANALYZE`, số câu SQL/request, profiling CPU/RAM, hoặc phân tích nguy cơ bảo mật) và đối chiếu trực tiếp tới mã QAS/ASR trong `docs/phase2/README.md`.]

## 3. Phương án được chọn (Decision Outcome / Chosen Option)

[Trình bày rõ quyết định lựa chọn, phạm vi áp dụng theo ranh giới 3 tầng (`api`, `domain`, `infrastructure`, `config`), cơ chế Feature Flag bật/tắt (nếu có), và nêu rõ **Chiến thuật SEI (Tactics)** cùng **Mẫu thiết kế (Patterns)** được vận dụng.]

## 4. Các phương án đã cân nhắc nhưng loại (Considered & Rejected Alternatives)

> Bắt buộc nêu và phân tích tối thiểu **≥ 1 phương án thay thế hợp lý** đã cân nhắc nhưng loại bỏ.

### Phương án A: [Tên phương án bị loại]
- **Mô tả ngắn:** [...]
- **Lý do loại:** [Giải thích dựa trên QAS, ràng buộc Phase 1, độ phức tạp vận hành hoặc kết quả đo trên môi trường Kaggle 4 vCPU.]

## 5. Hệ quả & Đánh đổi (Consequences & Trade-offs)

### Tích cực (Benefits)
- [...]

### Tiêu cực & Đánh đổi (Costs, Risks & Mitigations)
- **Đánh đổi:** [Nêu trung thực chi phí phải trả — ví dụ: độ trễ ghi index, hop mạng qua Gateway, nguy cơ stale cache, từ chối burst request, hoặc phụ thuộc SQL PostgreSQL.]
- **Biện pháp giảm thiểu:** [Cách kiểm soát rủi ro — ví dụ: after-commit eviction, fallback về DB, TTL ngắn, Feature Flag.]

## 6. Cách kiểm chứng & Tiêu chí nghiệm thu (Verification & Compliance)

- **Kiểm thử tự động:** [Unit test, Testcontainers PostgreSQL/Redis, E2E Phase 1, `ArchitectureTest`.]
- **Đo kiểm định lượng:** [Kịch bản `S1–S4`, SQL count / `EXPLAIN ANALYZE`, metrics Actuator/Prometheus hoặc kiểm thử Failover.]
