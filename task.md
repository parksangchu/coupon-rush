# task.md — 진행 추적

## 전체 요약

| Phase | 내용 | 상태 |
|-------|------|------|
| Phase 0 | 설계 | ✅ 완료 |
| Phase 1 | 인프라 구성 | 🔄 진행중 |
| Phase 2 | 기본 도메인 | 🔄 진행중 |
| Phase 3 | Step 1 — Pessimistic Lock | ⬜ 미시작 |
| Phase 4 | Step 2 — Optimistic Lock | ⬜ 미시작 |
| Phase 5 | Step 3 — Redis Lock | ⬜ 미시작 |
| Phase 6 | Step 4 — Redis Counter | ⬜ 미시작 |
| Phase 7 | Step 5 — Kafka Queue | ⬜ 미시작 |
| Phase 8 | 최종 비교 리포트 | ⬜ 미시작 |

---

## Phase 0: 설계 ✅

- [x] 설계문서.md 작성
- [x] CLAUDE.md 작성
- [x] 커리큘럼.md 작성

---

## Phase 1: 인프라 구성 🔄

### Docker Compose (로컬 개발/디버깅)
- [x] MySQL
- [ ] Redis
- [ ] Kafka
- [ ] Prometheus
- [ ] Grafana

### Terraform + AWS (부하 테스트)
- [ ] EC2
- [ ] RDS
- [ ] ElastiCache
- [ ] MSK

---

## Phase 2: 기본 도메인 🔄

- [x] Coupon 엔티티
- [x] CouponService
- [x] CouponController (생성·조회 API)
- [ ] Issuance 엔티티
- [ ] 발급 API 뼈대
- [ ] IssuanceStrategy 인터페이스
- [ ] 전략 선택 설정 (`coupon.strategy` 프로퍼티)

---

## Phase 3: Step 1 — Pessimistic Lock ⬜

- [ ] 전략 구현체 작성
- [ ] 통합 테스트 (Testcontainers)
- [ ] k6 부하 테스트 실행
- [ ] 결과 분석 (p99, 처리량, 에러율)
- [ ] 대안 검토 및 기각 사유 기록
- [ ] **발견한 한계** →
- [ ] **다음 전략 동기** →

---

## Phase 4: Step 2 — Optimistic Lock ⬜

- [ ] 전략 구현체 작성
- [ ] 통합 테스트 (Testcontainers)
- [ ] k6 부하 테스트 실행
- [ ] 결과 분석 (p99, 처리량, 에러율)
- [ ] 대안 검토 및 기각 사유 기록
- [ ] **발견한 한계** →
- [ ] **다음 전략 동기** →

---

## Phase 5: Step 3 — Redis Lock ⬜

- [ ] 전략 구현체 작성
- [ ] 통합 테스트 (Testcontainers)
- [ ] k6 부하 테스트 실행
- [ ] 결과 분석 (p99, 처리량, 에러율)
- [ ] 대안 검토 및 기각 사유 기록
- [ ] **발견한 한계** →
- [ ] **다음 전략 동기** →

---

## Phase 6: Step 4 — Redis Counter ⬜

- [ ] 전략 구현체 작성
- [ ] 통합 테스트 (Testcontainers)
- [ ] k6 부하 테스트 실행
- [ ] 결과 분석 (p99, 처리량, 에러율)
- [ ] 대안 검토 및 기각 사유 기록
- [ ] **발견한 한계** →
- [ ] **다음 전략 동기** →

---

## Phase 7: Step 5 — Kafka Queue ⬜

- [ ] 전략 구현체 작성
- [ ] 통합 테스트 (Testcontainers)
- [ ] k6 부하 테스트 실행
- [ ] 결과 분석 (p99, 처리량, 에러율)
- [ ] 대안 검토 및 기각 사유 기록
- [ ] **발견한 한계** →
- [ ] **다음 전략 동기** →

---

## Phase 8: 최종 비교 리포트 ⬜

- [ ] `docs/strategy-comparison.md` 작성
- [ ] 전략별 수치 비교표 완성 (TPS, p99, 초과 발급, 특이사항)
- [ ] 전략 선택 가이드라인 정리
