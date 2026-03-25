# Coupon Rush

선착순 쿠폰 발급 시스템. 6가지 동시성 전략을 단계별로 구현하고, k6 부하 테스트로 비교한다.

## 목적

각 전략의 한계를 직접 확인하고 트레이드오프를 설명할 수 있게 되는 것. 이전 단계의 실측 병목이 다음 단계의 동기가 된다.

## 전략 흐름

### Step 1: DB 직렬화

DB만으로 동시성을 제어한다.

| 전략 | 방식 | 1,000 RPS p(95) | 2,000 RPS p(95) | 결과 |
|------|------|-----------------|-----------------|------|
| Pessimistic Lock | SELECT FOR UPDATE | 5,468ms | - | 1,000 RPS에서 붕괴 |
| Single UPDATE | 단일 UPDATE 원자 연산 | **54ms** | 6,270ms (붕괴) | 1,000 RPS 소화, 2,000에서 한계 |

Single UPDATE가 DB 안에서 할 수 있는 최선. 1,000 RPS에서는 가장 빠르지만, **DB row-level lock 직렬화** 때문에 2,000 RPS에서 붕괴.

→ 동시성 제어를 Redis로 이동

### Step 2: DB + Redis

Redis가 동시성 제어, DB가 데이터 저장.

| 전략 | 방식 | 1,000 RPS p(95) | 2,000 RPS | 결과 |
|------|------|-----------------|-----------|------|
| Redis Lock | Redisson 분산 락 | 13,053ms | - | 붕괴. lock-data 분리로 DB 락보다 느림 |
| Redis Counter | Lua (INCR 원자 연산) | 3,655ms | 4,637ms (붕괴) | 2,000 RPS에서 한계 |

Redis Counter는 **락 없이** INCR 원자 연산으로 동시성 해결. 거절(전체 요청의 90% 이상)이 Redis에서만 처리되어 DB 부하가 크게 줄어든다. 하지만 발급 성공 시 **DB INSERT가 동기로 실행**되어 여전히 병목. 2,000 RPS에서 HikariCP Pending 200, CPU 100%.

→ DB를 API 경로에서 분리

### Step 3: Redis + Queue + DB (비동기)

Redis가 판단, 메시지 큐가 전달, DB 저장은 백그라운드. **API 응답 시점에 DB를 치지 않는다.**

| 전략 | 방식 | 2,000 RPS p(95) | 3,000 RPS p(95) | 안정 한계 |
|------|------|-----------------|-----------------|---------|
| Kafka | Lua + Kafka produce | **9.9ms** | **8.7ms** | 약 7,000 RPS |
| Redis Streams | Lua (INCR + XADD 원자) | **1.7ms** | **1.1ms** | 약 4,000 RPS |

둘 다 2,000-3,000 RPS를 VU 100개로 완벽하게 소화한다 (Step 2는 VU 2,000 포화).

**Redis Streams**는 3,000 RPS까지 가장 빠르고 (p95 1ms), INCR-XADD가 Lua로 원자 실행되어 dual-write gap이 없다. 단, Redis 단일 스레드 한계로 5,000 RPS에서 붕괴.

**Kafka**는 latency가 약간 높지만 (p95 약 10ms), 7,000 RPS까지 안정적이다. INCR-produce가 원자적이지 않아 크래시 시 1-2건 불일치 가능하나 초과발급 방향은 아님.

**비동기 전략의 대가**: Consumer lag (10,000건 DB 반영에 약 80초). API 응답은 빠르지만 DB 조회 시 아직 안 보일 수 있다.

## 병목 변천

```
Step 1: DB lock contention (직렬화 대기, CPU 유휴)
  ↓ Redis로 동시성 제어 이동
Step 2: DB INSERT 병목 (HikariCP 포화, CPU 100%)
  ↓ DB를 API 경로에서 분리
Step 3: Redis 단일 스레드 (Streams) 또는 앱 CPU (Kafka)
  ↓ 다음: 앱 수평 확장
```

## 기술 스택

- Java 21, Spring Boot 4.x, MySQL, Redis, Kafka
- Testcontainers, k6, Prometheus + Grafana
- Terraform, AWS (EC2, RDS, ElastiCache)

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

## 기술 문서

각 단계의 설계 판단, 문제 해결, 실측 결과는 [docs/](docs/README.md)에 기록되어 있다.

자세한 전략 비교: [docs/strategy-comparison.md](docs/strategy-comparison.md)
