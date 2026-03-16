# coupon-rush

> "비관적 락이 5만 RPS에서도 버틸까?" 에서 시작했다.

선착순 쿠폰 발급 시스템에서 5가지 동시성 전략을 구현하고, k6 부하 테스트로 각 전략의 한계를 실측한다.
각 전략은 이전 전략의 한계에서 출발한다. 코드 변경 없이 설정만 바꿔 동일 조건에서 비교한다.

---

## 전략 비교 결과

| 전략 | TPS | p99 latency | 초과 발급 | 특이사항 |
|------|-----|-------------|----------|----------|
| Pessimistic Lock | 측정 예정 | 측정 예정 | - | DB connection 고갈 발생 시점 기록 |
| Optimistic Lock | 측정 예정 | 측정 예정 | - | retry storm 발생 구간 기록 |
| Redis Lock | 측정 예정 | 측정 예정 | - | Lock timeout 실패율 기록 |
| Redis Counter | 측정 예정 | 측정 예정 | - | Redis 장애 시나리오 별도 테스트 |
| Kafka Queue | 측정 예정 | 측정 예정 | - | end-to-end 응답 시간 별도 기록 |

> 각 Step 완료 시 해당 행을 실측 수치로 채운다. 상세 분석은 [설계문서](설계문서.md)의 각 전략 섹션 참고.

---

## 기술 스택

| 기술 | 선택 이유 |
|------|-----------|
| Java 21 | Virtual Thread로 Lock wait 시 스레드 풀 고갈 방지 |
| Spring Boot 4.x | 실무 스택. 전략 패턴과 프로퍼티 기반 전환 |
| MySQL 8.4 | 비관적/낙관적 락 전략의 동시성 제어 대상 |
| Redis (Redisson) | 분산 락, 원자 카운터 (INCR) 전략 |
| Kafka | 큐 기반 순서 보장 전략 |
| k6 | 부하 테스트. Grafana 실시간 연동 |
| Prometheus + Grafana | 전략별 메트릭 동일 대시보드 비교 |
| Testcontainers | 실제 MySQL/Redis/Kafka로 통합 테스트. H2는 락 동작이 달라 사용 불가 |
| Terraform + AWS | 부하 테스트 환경 프로비저닝. 전략 간 동일 조건 보장 |

> 상세 선택 근거는 [설계문서 - 기술 스택](설계문서.md#4-기술-스택) 참고.

---

## 빠른 시작

```bash
# 로컬 인프라 실행
docker compose up -d

# 애플리케이션 실행 (전략 지정)
./gradlew bootRun --args='--coupon.strategy=pessimistic'

# 테스트
./gradlew test
```

전략 전환은 코드 변경 없이 프로퍼티만 바꾼다:

```yaml
coupon:
  strategy: pessimistic  # pessimistic | optimistic | redis-lock | redis-counter | kafka
```

---

## 프로젝트 구조

```
coupon-rush/
├── src/main/java/com/couponrush/
│   ├── domain/coupon/     # Coupon + Issuance 애그리거트, 전략 구현체, 서비스, 컨트롤러
│   ├── global/            # 전역 설정, 예외 핸들러
│   └── common/            # 공통 유틸
├── k6/                    # 부하 테스트 시나리오 + 결과
├── terraform/             # AWS 인프라 (EC2, RDS, ElastiCache, MSK)
├── docker-compose.yml     # 로컬 개발용 (MySQL, Redis, Kafka, Prometheus, Grafana)
└── docs/                  # 의사결정 기록
```

`IssuanceStrategy` 인터페이스를 5개 구현체가 구현한다. `coupon.strategy` 프로퍼티로 런타임 전환.

---

## 문서 안내

| 문서 | 내용 |
|------|------|
| [설계문서](설계문서.md) | 전략별 상세 설계: 가설 → 실측 → 대안 검토 → 깨달음 |
| [Decision Log](docs/decision-log.md) | 인프라/기술 선택 결정 기록 |
| `docs/strategy-comparison.md` | 전략별 실측 수치 비교 (전체 실측 완료 후 생성) |
