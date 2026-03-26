# Coupon Rush

선착순 쿠폰 발급 시스템. 6가지 동시성 전략을 단계별로 구현하고, k6 부하 테스트로 비교한다.

## 목적

각 전략의 한계를 직접 확인하고 트레이드오프를 설명할 수 있게 되는 것. 이전 단계의 실측 병목이 다음 단계의 동기가 된다.

## 기술 스택

| 분류 | 기술 |
|------|------|
| Backend | Java 21, Spring Boot 4.x |
| DB | MySQL 8.4 (RDS) |
| Cache / Queue | Redis (ElastiCache), Kafka |
| Test | k6, Testcontainers, Prometheus + Grafana |
| Infra | Terraform, AWS (EC2, RDS, ElastiCache) |

## 전략 흐름

### Step 1: DB 직렬화

DB만으로 동시성을 제어한다.

| 전략 | 방식 | 500 RPS p(95) | 1,000 RPS p(95) | 비고 |
|------|------|---------------|-----------------|------|
| Pessimistic Lock | SELECT FOR UPDATE | 6,743ms | 14,682ms | 500 RPS에서 19% 드롭 |
| Single UPDATE | 단일 UPDATE 원자 연산 | 2,085ms | 8,709ms | 500 RPS부터 드롭 발생 |

Pessimistic Lock은 lock 안에서 중복 체크 + INSERT까지 수행하여 lock 보유 시간이 길고, 거절도 lock을 잡아야 한다. Single UPDATE는 DB 안에서 할 수 있는 최선이지만, **DB row-level lock 직렬화** 때문에 500 RPS에서 이미 한계.

→ 동시성 제어를 Redis로 이동

### Step 2: DB + Redis

Redis가 동시성 제어, DB가 데이터 저장.

| 전략 | 방식 | 1,000 RPS p(95) | 2,000 RPS p(95) | 비고 |
|------|------|-----------------|-----------------|------|
| Redis Lock | Redisson 분산 락 | 42,345ms | - | 500 RPS에서도 p95 34,614ms, 쿠폰 미소진 |
| Redis Counter | Lua (INCR 원자 연산) | **9.2ms** | 1,074ms | 1,000 RPS 드롭 0, 전량 처리 |

Redis Counter는 **락 없이** INCR 원자 연산으로 동시성 해결. 거절(전체 요청의 90% 이상)이 Redis에서만 처리되어 DB 부하가 크게 줄어든다. 하지만 발급 성공 시 **DB INSERT가 동기로 실행**되어 2,000 RPS에서 악화 시작.

→ DB를 API 경로에서 분리

### Step 3: Redis + Queue + DB (비동기)

Redis가 판단, 메시지 큐가 전달, DB 저장은 백그라운드. **API 응답 시점에 DB를 치지 않는다.**

| 전략 | 방식 | 2,000 RPS p(95) | 5,000 RPS p(95) | 비고 |
|------|------|-----------------|-----------------|------|
| Kafka | Lua + Kafka produce | **8.1ms** | **15ms** | 10,000 RPS p95 22ms |
| Redis Streams | Lua (INCR + XADD 원자) | **2.3ms** | **12ms** | 10,000 RPS p95 17ms |

Step 2는 2,000 RPS에서 이미 1,074ms인데, 비동기 전략은 10,000 RPS에서도 20ms 이하. **처리량 천장은 두 전략 모두 약 7,000 req/s로 동일** (앱 서버 CPU 2 vCPU 한계). 차이는 성능이 아닌 구조적 특성:
- **Redis Streams**: INCR-XADD가 Lua로 원자 실행 (dual-write gap 없음). 별도 인프라 불필요.
- **Kafka**: 디스크 복제 기반 내구성. Redis와 장애 도메인 분리.

**비동기 전략의 대가**: Consumer lag (10,000건 DB 반영에 약 80초). API 응답은 빠르지만 DB 조회 시 아직 안 보일 수 있다.

## 병목 변천

```
Step 1: DB lock contention (직렬화 대기, CPU 유휴)
  ↓ Redis로 동시성 제어 이동
Step 2: DB INSERT 병목 (HikariCP 포화, CPU 100%)
  ↓ DB를 API 경로에서 분리
Step 3: 앱 서버 CPU (2 vCPU) — 약 7,000 req/s에서 천장
  ↓ 다음: 앱 수평 확장
```

## 트러블슈팅

### @Transactional + 분산 락 커밋-언락 순서

**문제**: `@Transactional` 메서드 안에서 Redis 분산 락을 사용하면 **커밋 전에 락이 풀린다.** `@Transactional`은 AOP 프록시라서 메서드 반환 후 커밋하지만, `lock.unlock()`은 finally 블록에서 메서드 반환 전에 실행된다. 락 해제 → 커밋 순서가 되어 다른 스레드가 커밋 전 데이터를 읽을 수 있다.

**해결**: `TransactionTemplate`으로 트랜잭션 경계를 직접 제어. `execute()` 반환 시점에 커밋이 완료되므로, 그 후 `lock.unlock()`을 호출하면 커밋 → 락 해제 순서가 보장된다.

### 비동기인데 왜 느리지? — DB SELECT 1회의 대가

**문제**: Kafka 비동기 전략에서 DB INSERT는 Consumer로 분리했는데, API 응답용 Coupon 조회(`findById`)가 매 요청마다 남아있었다. 2,000 RPS에서 p95 **3,950ms**.

**해결**: `IssuanceStrategy.issue()` 반환 타입을 `void`로 변경하여 Coupon 조회를 제거. API 경로에서 DB를 **완전히** 분리.

**결과**: 동일 조건 p95 3,950ms → **8ms**. DB SELECT 1회가 2,000 RPS에서 이 정도 차이를 만든다. "불완전한 분리"만으로도 비동기 효과가 사라진다.

### JVM 웜업 미적용으로 잘못된 결론

**문제**: 동일 테스트를 3회 연속 실행했더니 p95가 **2,281ms → 339ms → 33ms**로 극심하게 달라졌다. 이 사실을 모르고 "Redis Streams는 5,000 RPS에서 붕괴한다"는 잘못된 결론을 냈다.

**원인**: JIT 컴파일. JVM은 초기에 바이트코드를 인터프리터로 실행하다가, 반복 실행되는 코드를 네이티브 코드로 컴파일한다. 첫 번째 run은 인터프리터 상태.

**해결**: 모든 전략을 1,000 RPS x 2회 웜업 후 측정하는 것으로 방법론 변경. 기존 결과를 폐기하고 전 전략 재측정.

## 기술 문서

각 단계의 설계 판단, 문제 해결, 실측 결과는 [docs/](docs/README.md)에 기록되어 있다.

자세한 전략 비교: [docs/strategy-comparison.md](docs/strategy-comparison.md)

## 아키텍처

`IssuanceStrategy` 인터페이스를 6개 전략이 구현한다. `coupon.strategy` 프로퍼티로 런타임 전환.

```
src/main/java/com/couponrush/
├── domain/coupon/
│   ├── controller/    # REST API
│   ├── dto/           # 요청/응답
│   ├── entity/        # Coupon, Issuance
│   ├── exception/     # 도메인 예외
│   ├── repository/    # JPA
│   ├── service/       # CouponService
│   └── strategy/      # 6개 전략 구현체 + Consumer
├── global/            # Config, 예외 핸들러
└── common/
```

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 실행 (전략 지정)
./gradlew bootRun --args='--coupon.strategy=pessimistic'
# 가능한 값: pessimistic, single-update, redis-lock, redis-counter, kafka, redis-streams

# 테스트 (Docker 필요 — Testcontainers)
./gradlew test

# AWS 인프라
cd terraform && terraform apply

# 배포
bash scripts/deploy-app.sh kafka

# 부하 테스트
k6 run -e BASE_URL=http://<app-ip>:8080 -e RATE=2000 k6/scenarios/spike.js
```
