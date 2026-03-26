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

## 성능 비교 (웜업 보정 후)

조건: 쿠폰 10,000개, 30초, HikariCP pool size 10, JVM 웜업 2회 후 측정.

### 발급 Latency p(95)

| RPS | Single UPDATE | Redis Counter | Kafka | Redis Streams |
|-----|--------------|---------------|-------|---------------|
| 1,000 | 110ms | **25ms** | **7.8ms** | **1.9ms** |
| 2,000 | 1,464ms | 432ms | **9.7ms** | **2.6ms** |
| 3,000 | 1,687ms | 860ms | **10ms** | **8.1ms** |
| 5,000 | 1,691ms | 2,215ms | **18ms** | **9.4ms** |
| 7,000 | - | - | 31ms | 12ms |
| 10,000 | - | - | 110ms | 109ms |
| 20,000 | - | - | 88ms | 57ms |

5,000 RPS에서 Redis Counter(2,215ms)가 Single UPDATE(1,691ms)보다 느리다. Single UPDATE는 모든 요청이 DB를 치지만 row lock 직렬화로 2,000 RPS 부근에서 이미 포화되어 이후 latency가 정체된다. 반면 Redis Counter는 거절을 Redis에서 빠르게 처리하는 대신, 성공한 10,000건의 DB INSERT가 짧은 시간에 집중되어 latency가 계속 상승한다. 두 전략 모두 DB가 병목이지만 포화 패턴이 다르다. 이것이 Step 3에서 DB를 API 경로에서 완전히 분리한 이유다.

### maxVUs

| RPS | Single UPDATE | Redis Counter | Kafka | Redis Streams |
|-----|--------------|---------------|-------|---------------|
| 1,000 | 124 | 100 | 100 | 100 |
| 2,000 | 2,000 (포화) | 500 | 101 | 100 |
| 3,000 | 2,000 (포화) | 1,833 | 123 | 126 |
| 5,000 | 2,000 (포화) | 2,000 (포화) | 618 | 428 |
| 7,000 | - | - | 1,677 | 1,039 |
| 10,000 | - | - | 5,000 (포화) | 3,877 |

10,000 RPS에서 Redis Streams의 VU(3,877)가 Kafka(5,000 포화)보다 낮은 이유: p95는 비슷하지만 Kafka는 Redis Lua + Kafka produce 2단계 네트워크 왕복으로 tail latency(p99, max)가 더 높아 평균 응답시간이 길다. Little's Law(L = λ × W) 역산 시 Kafka 평균 약 714ms, Redis Streams 약 554ms. VU 소비량은 p95가 아닌 평균에 의해 결정된다.

VU에 여유가 있다고 서버에 여유가 있는 것은 아니다. maxVUs는 k6 클라이언트 측 동시 요청 수이고, 처리량 천장은 서버 측 한계다.

### 처리량 천장

비동기 전략의 처리량은 약 **7,000 req/s (iterations 약 210K/30초)**에서 정체. RPS를 10,000 이상으로 올려도 처리량이 늘지 않고 dropped만 증가한다. 병목은 앱 서버 CPU (m6i.large, 2 vCPU).

Kafka와 Redis Streams의 처리량 천장은 거의 동일하다.

## 정합성

전 전략, 전 구간에서 정합성 100%:
- 초과발급 0건
- 중복 발급 0건
- 비동기 전략 메시지 유실 0건 (10,000건 전부 DB 반영 확인)

## 병목 변천

```
Step 1: DB lock contention (직렬화 대기, CPU 유휴)
  ↓ Redis로 동시성 제어 이동
Step 2: DB INSERT 병목 (HikariCP 포화, CPU 100%)
  ↓ DB를 API 경로에서 분리
Step 3: 앱 서버 CPU (2 vCPU) — 약 7,000 req/s에서 천장
  ↓ 다음: 앱 수평 확장
```

## Kafka vs Redis Streams 트레이드오프

처리량 천장은 거의 동일 (약 7,000 req/s). 차이는 성능이 아닌 구조적 특성에 있다.

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

## 각 단계의 전환 동기

| 전환 | 이전 단계 병목 | 실측 근거 |
|------|-------------|---------|
| Step 1 → Step 2 | DB lock contention. 1,000 RPS에서 Pessimistic Lock p95 5.4초 | HikariCP Pending 190, CPU 60~80% (I/O 대기 지배적) |
| Step 2 (Lock → Counter) | Redis Lock이 DB Lock보다 느림. 1,000 RPS에서 78% 드롭 | lock-data 분리: HikariCP Active 1~2개 (과직렬화) |
| Step 2 → Step 3 | Redis Counter에서 DB INSERT가 병목. 2,000 RPS에서 CPU 100% | HikariCP Pending 200, 처리량 정체 |

## 테스트 인프라

| 구성 요소 | 사양 |
|----------|------|
| App | EC2 m6i.large (2 vCPU, 8GB) |
| DB | RDS db.m6g.large, MySQL 8.4 |
| Redis | ElastiCache cache.m6g.large |
| Kafka | EC2 m6i.large, Docker apache/kafka:3.8.0 |
| Test | EC2 m6i.large, k6 |

## JVM 워밍업에 대한 참고

JIT 컴파일 전후로 동일 테스트의 p95가 10배 이상 차이났다 (Kafka 5,000 RPS: Run 1 p95 2,281ms → Run 3 p95 33ms). 부하 테스트 시 웜업 run을 반드시 수행해야 안정적 결과를 얻을 수 있다.
