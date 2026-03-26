# 전략 비교

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-26 |

## 개요

선착순 쿠폰 발급 시스템의 동시성 전략을 3단계(6개 전략)에 걸쳐 구현하고, k6 부하 테스트로 비교했다. 각 단계는 이전 단계의 실측 병목을 근거로 진행했다.

모든 측정은 JVM 웜업(1,000 RPS x 2회) 후 수행. JIT 컴파일 전후로 결과가 극심하게 달라지는 것을 확인했기 때문이다 (동일 조건에서 p95가 10배 이상 차이).

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

조건: 쿠폰 10,000개, 30초, HikariCP pool size 10, k6 maxVUs 10,000, JVM 웜업 2회 후 측정.

### 발급 Latency p(95)

| RPS | Pessimistic Lock | Single UPDATE | Redis Lock | Redis Counter | Kafka | Redis Streams |
|-----|-----------------|--------------|------------|---------------|-------|---------------|
| 500 | 6,743ms | 2,085ms | 34,614ms | **8.9ms** | - | - |
| 1,000 | 14,682ms | 8,709ms | 42,345ms | **9.2ms** | **7.4ms** | **1.7ms** |
| 2,000 | 21,525ms | 12,479ms | 46,513ms | 1,074ms | **8.1ms** | **2.3ms** |
| 5,000 | - | - | - | 2,017ms | **15ms** | **12ms** |
| 7,000 | - | - | - | - | 20ms | 12ms |
| 10,000 | - | - | - | - | 22ms | 17ms |

Pessimistic Lock은 500 RPS에서 이미 p95 6.7초. Redis Lock은 분산 락 직렬화로 전 구간 최하위 (500 RPS에서 34초, 쿠폰 미소진). Single UPDATE는 500 RPS에서 붕괴. Redis Counter는 1,000 RPS까지 안정(p95 9ms), 2,000에서 악화 시작. 비동기 전략(Kafka, Redis Streams)은 10,000 RPS에서도 p95 25ms 이하.

### 발급 Latency p(99)

| RPS | Pessimistic Lock | Single UPDATE | Redis Lock | Redis Counter | Kafka | Redis Streams |
|-----|-----------------|--------------|------------|---------------|-------|---------------|
| 500 | 7,046ms | 2,213ms | 36,071ms | 23ms | - | - |
| 1,000 | 15,284ms | 9,082ms | 44,216ms | 23ms | 9.3ms | 4.4ms |
| 2,000 | 22,382ms | 12,963ms | 48,488ms | 1,332ms | 23ms | 9.7ms |
| 5,000 | - | - | - | 2,253ms | 22ms | 27ms |
| 10,000 | - | - | - | - | 33ms | 34ms |

락 전략(Pessimistic Lock, Redis Lock)은 p95와 p99 차이가 작다 — 전체가 느리기 때문. 고 RPS에서 Kafka와 Redis Streams의 p99가 수렴한다 (10,000 RPS: 33ms vs 34ms).

### maxVUs

| RPS | Pessimistic Lock | Single UPDATE | Redis Lock | Redis Counter | Kafka | Redis Streams |
|-----|-----------------|--------------|------------|---------------|-------|---------------|
| 500 | 2,756 | 1,019 | 5,513 | 100 | - | - |
| 1,000 | 8,912 | 6,126 | 10,000 (포화) | 100 | 100 | 100 |
| 2,000 | 10,000 (포화) | 10,000 (포화) | 10,000 (포화) | 1,601 | 101 | 100 |
| 5,000 | - | - | - | 10,000 (포화) | 344 | 344 |
| 7,000 | - | - | - | - | 788 | 651 |
| 10,000 | - | - | - | - | 3,806 | 4,843 |

Redis Lock은 1,000 RPS에서 이미 VU 포화. Pessimistic Lock도 2,000 RPS에서 포화. 락 전략은 응답이 느려 VU가 빠르게 소진된다. Kafka와 Redis Streams의 VU 차이는 컨디션(JVM 상태, 인프라 타이밍)에 따라 달라지며, 체계적인 차이가 아니다. 이전 측정(3차)에서는 Kafka가 더 높았으나, 이번 측정(4차)에서는 Redis Streams가 더 높아 역전됐다.

### 처리량 천장

비동기 전략의 처리량은 약 **7,000 req/s (iterations 약 210K/30초)**에서 정체. RPS를 10,000 이상으로 올려도 처리량이 늘지 않고 dropped만 증가한다. 병목은 앱 서버 CPU (m6i.large, 2 vCPU).

Kafka와 Redis Streams의 처리량 천장은 거의 동일하다.

## 정합성

전 전략, 전 구간에서 정합성 100%:
- 초과발급 0건
- 중복 발급 0건
- 비동기 전략 메시지 유실 0건 (10,000건 전부 DB 반영 확인)

## Kafka vs Redis Streams 트레이드오프

처리량 천장은 거의 동일 (약 7,000 req/s). p95, p99 모두 고 RPS에서 수렴. **성능으로는 구분할 수 없다.** 차이는 구조적 특성에 있다.

| 기준 | Kafka | Redis Streams |
|------|-------|---------------|
| INCR-발행 원자성 | 불가 (dual-write gap) | **Lua로 원자 실행** |
| dual-write 장애 시나리오 | 타임아웃 후 실제 전송 성공, 앱 크래시 시 보상 실패 가능 | 발생하지 않음 |
| 장애 도메인 | Redis와 분리 | Redis에 집중 (단일 장애점) |
| 내구성 | **디스크 복제 (ISR)** | 메모리 기반, AOF 의존 |
| 인프라 | 별도 브로커 필요 | 기존 Redis 활용 |

### 선택 기준

- **원자성 중시 + 인프라 단순화**: Redis Streams. dual-write gap이 없고 별도 인프라 불필요.
- **내구성 중시 + 장애 도메인 분리**: Kafka. 디스크 복제 기반, Redis 장애 시에도 큐 데이터 보존.

## 테스트 인프라

| 구성 요소 | 사양 |
|----------|------|
| App | EC2 m6i.large (2 vCPU, 8GB) |
| DB | RDS db.m6g.large, MySQL 8.4 |
| Redis | ElastiCache cache.m6g.large |
| Kafka | EC2 m6i.large, Docker apache/kafka:3.8.0 |
| Test | EC2 m6i.large, k6 (maxVUs 10,000) |

## 측정 이력

3차 테스트(maxVUs 2,000~5,000)에서 Single UPDATE 1,000 RPS p95 110ms로 기록했으나, 4차 테스트(maxVUs 10,000)에서 재현 불가. 동일 조건 재배포 후 재측정에서도 p95 8,617ms로 일관. 3차의 Single UPDATE 결과는 기록 오류로 판단하여 4차 결과로 교체했다. 상세 경위는 [4차 부하 테스트 기록](coupon/4th-load-test-log.md) 참고.

## JVM 워밍업에 대한 참고

JIT 컴파일 전후로 동일 테스트의 p95가 10배 이상 차이났다 (Kafka 5,000 RPS: Run 1 p95 2,281ms → Run 3 p95 33ms). 부하 테스트 시 웜업 run을 반드시 수행해야 안정적 결과를 얻을 수 있다.
