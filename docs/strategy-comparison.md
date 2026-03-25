# 전략 비교

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-25 |

## 개요

선착순 쿠폰 발급 시스템의 동시성 전략을 3단계(6개 전략)에 걸쳐 구현하고, k6 부하 테스트로 비교했다. 각 단계는 이전 단계의 실측 병목을 근거로 진행했다.

## 전략 요약

| Step | 전략 | 동시성 제어 | DB 저장 | 핵심 메커니즘 |
|------|------|-----------|---------|-------------|
| 1 | Pessimistic Lock | DB (FOR UPDATE) | 동기 | row-level X lock |
| 1 | Single UPDATE | DB (원자 연산) | 동기 | UPDATE ... SET issued = issued + 1 WHERE issued < total |
| 2 | Redis Lock | Redis (Redisson) | 동기 | 분산 락 → DB 저장 |
| 2 | Redis Counter | Redis (INCR) | 동기 | Lua 스크립트 (SADD + INCR + total 체크) → DB 저장 |
| 3 | Kafka | Redis (INCR) + Kafka | 비동기 | Lua → Kafka produce → Consumer DB INSERT |
| 3 | Redis Streams | Redis (INCR + XADD) | 비동기 | Lua 원자 실행 → Consumer DB INSERT |

## 성능 비교

### 1,000 RPS (쿠폰 10,000개, 30초)

| 전략 | 발급 p(95) | maxVUs | dropped | 비고 |
|------|-----------|--------|---------|------|
| Pessimistic Lock | 5,468ms | 2,000 (포화) | 11,517 (38%) | 붕괴 |
| Single UPDATE | 54ms | 54 | 0 | 여유 |
| Redis Lock | 13,053ms | 2,000 (포화) | 23,404 (78%) | 붕괴 |
| Redis Counter | 3,655ms | 2,000 (포화) | 5,531 | |
| Kafka | 1,180ms | 830 | 1,182 | |
| Redis Streams | **432ms** | **424** | **540** | |

### 2,000 RPS (쿠폰 10,000개, 30초)

| 전략 | 발급 p(95) | maxVUs | dropped | 비고 |
|------|-----------|--------|---------|------|
| Redis Counter | 4,637ms | 2,000 (포화) | 30,424 | DB INSERT 병목 |
| Kafka | 9.9ms | 102 | 3 | |
| Redis Streams | **1.7ms** | **101** | **22** | |

### 3,000 RPS (쿠폰 10,000개, 30초)

| 전략 | 발급 p(95) | maxVUs | dropped | 비고 |
|------|-----------|--------|---------|------|
| Redis Counter | 4,306ms | 2,000 (포화) | 60,469 | 처리량 정체 |
| Kafka | 8.7ms | 101 | 110 | |
| Redis Streams | **1.1ms** | **102** | **95** | |

**1,000 RPS 역전 현상에 대한 참고**: Kafka(1,180ms)와 Redis Streams(432ms)가 2,000 RPS(9.9ms, 1.7ms)보다 오히려 느린 것은 측정 조건 때문이다. 1,000 RPS는 쿠폰 소진에 10초가 걸려 거절 폭주 구간이 길어지고, VU가 누적되어 latency가 상승한다. 2,000/3,000 RPS에서는 쿠폰이 빨리 소진되어 대부분의 요청이 Redis에서 즉시 거절되므로 오히려 안정적이다. 또한 1,000 RPS가 각 전략의 첫 테스트 구간이라 JVM 워밍업 효과도 있다.

### 고 RPS — 천장 탐색

| 전략 | 5,000 RPS | 7,000 RPS | 10,000 RPS |
|------|-----------|-----------|------------|
| Kafka p(95) | 13.9ms | 17.7ms | 458ms (붕괴) |
| Kafka maxVUs | 439 | 745 | 2,000 (포화) |
| Redis Streams p(95) | 1,752ms (붕괴) | - | - |
| Redis Streams maxVUs | 2,000 (포화) | - | - |

**천장**: Kafka ~7,000 RPS, Redis Streams ~4,000 RPS (단일 앱 인스턴스 + 단일 브로커/Redis 기준). 3,000 RPS에서 안정 + 5,000/10,000 RPS에서 붕괴 사이의 추정치이며, 정확한 임계점은 측정하지 않았다.

## 정합성

전 전략, 전 구간에서 정합성 100%:
- 초과발급 0건
- 중복 발급 0건
- 비동기 전략 메시지 유실 0건 (10,000건 전부 DB 반영 확인)

## 병목 변천

```
Step 1: DB lock contention → 직렬화 대기
  ↓ Redis로 동시성 제어 이동
Step 2: DB INSERT (HikariCP 포화, CPU 100%)
  ↓ DB를 API 경로에서 분리
Step 3: Redis 단일 스레드 (Streams) 또는 앱 CPU (Kafka)
  ↓ 다음: 앱 수평 확장
```

## Kafka vs Redis Streams 트레이드오프

| 기준 | Kafka | Redis Streams |
|------|-------|---------------|
| 저~중 RPS (≤3,000) | p95 ~10ms | **p95 ~1ms** |
| 고 RPS 천장 | **~7,000 RPS** | ~4,000 RPS |
| INCR-발행 원자성 | 불가 (dual-write gap) | **Lua로 원자 실행** |
| dual-write 장애 시나리오 | 타임아웃 후 실제 전송 성공, 앱 크래시 시 보상 실패 가능 | 발생하지 않음 |
| 장애 도메인 | Redis와 분리 | Redis에 집중 (단일 장애점) |
| 내구성 | **디스크 복제 (ISR)** | 메모리 기반, AOF 의존 |
| 인프라 | 별도 브로커 필요 (~$70/월) | 기존 Redis 활용 |
| 운영 복잡도 | Consumer lag, partition 관리 | 상대적 단순 |

### 선택 기준

- **예상 트래픽 ≤3,000 RPS + 원자성 중시**: Redis Streams. 인프라 추가 없이 가장 빠르고, dual-write gap이 없음.
- **예상 트래픽 >3,000 RPS 또는 내구성 중시**: Kafka. Redis 단일 스레드 한계를 넘어서는 처리량이 필요하거나, 디스크 복제 기반 내구성이 필요한 경우.
- **둘 다 필요**: Redis Streams로 시작, 트래픽 증가 시 Kafka로 전환.

## 각 단계의 전환 동기

| 전환 | 이전 단계 병목 | 실측 근거 |
|------|-------------|---------|
| Step 1 → Step 2 | DB lock contention. 1,000 RPS에서 Pessimistic Lock p95 5.4초 | HikariCP Pending 190, CPU 60~80% (I/O 대기 지배적) |
| Step 2 (Lock → Counter) | Redis Lock이 DB Lock보다 느림. 1,000 RPS에서 78% 드롭 | HikariCP Active 1~2개 (과직렬화) |
| Step 2 → Step 3 | Redis Counter에서 DB INSERT가 병목. 2,000 RPS에서 CPU 100% | HikariCP Pending 200, 처리량 정체 (29,532 → 29,577) |

## 테스트 인프라

| 구성 요소 | 사양 |
|----------|------|
| App | EC2 m6i.large (2 vCPU, 8GB) |
| DB | RDS db.m6g.large, MySQL 8.4 |
| Redis | ElastiCache cache.m6g.large |
| Kafka | EC2 m6i.large, Docker apache/kafka:3.8.0 |
| Test | EC2 m6i.large, k6 |
| 모니터링 | Prometheus + Grafana (test EC2에 공존) |
