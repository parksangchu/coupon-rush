# Kafka 비동기 저장 부하 테스트

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-25 |

## 배경

### 전체 흐름에서의 위치

이 프로젝트의 전략은 인프라 계층 기준으로 3단계로 나뉜다:

| 계층 | Step | 역할 분담 |
|------|------|-----------|
| **DB** | Step 1 (Pessimistic Lock, Single UPDATE) | DB가 락 + 데이터 전부 처리 |
| **DB + Redis** | Step 2 (Redis Lock, Redis Counter) | Redis가 동시성 제어, DB가 데이터 저장 |
| **Redis + Queue + DB** | Step 3 (Kafka, Redis Streams) | Redis가 판단, Queue가 전달, DB가 저장 |

Redis Lock → Redis Counter는 같은 계층 안에서 "락 vs 원자 연산" 방식 차이. Step 3은 계층 자체가 바뀌는 전환점이다 — DB를 API 응답 경로에서 완전히 분리한다.

### Step 2의 한계

Step 2의 Redis Counter에서 락 병목은 제거했지만, 2,000 RPS에서 DB INSERT가 새로운 병목이 됐다. App EC2 CPU 100%, HikariCP Active 포화. pool size 50 + Lua 스크립트로 +36% 개선했지만, CPU 100%는 구조적 한계였다.

Step 3의 질문: **"DB 저장을 비동기로 분리하면 처리량이 더 올라가는가?"**

## 타임라인

### 2026-03-24: 비동기 저장 방식 선택 — 왜 Kafka인가

**문제/질문**: DB INSERT를 API 응답 경로에서 분리하려면 어떤 방식이 적합한가?

**검토한 선택지**:

#### 1. 비동기 방식 비교

| 방식 | 장점 | 한계 |
|------|------|------|
| @Async / Spring Event | 단순, 인프라 추가 없음 | 앱 크래시 시 메모리에서 유실 |
| Redis List + 스케줄러 | 기존 Redis 활용 | 배치 주기에 따른 지연, 실패 처리 복잡 |
| 메시지 큐 (Kafka, Redis Streams) | 내구성, Consumer Group, 재처리 보장 | 인프라/운영 복잡도 증가 |

쿠폰 발급 기록은 금전적 가치가 있는 데이터다. "발급 성공했는데 기록 없음"은 허용할 수 없다. @Async는 구조적으로 유실 가능 → 메시지 큐로 좁힘.

#### 2. 메시지 큐 선택: Kafka vs Redis Streams

| 기준 | Kafka | Redis Streams |
|------|-------|---------------|
| INCR-발행 원자성 | 불가 (외부 시스템) | Lua 스크립트로 INCR+XADD 원자 실행 가능 |
| 장애 도메인 | Redis와 분리 | Redis에 카운터+큐 집중 (단일 장애점) |
| 내구성 | 디스크 복제 (ISR) | 메모리 기반, AOF 의존 |
| 실무 사례 | 여기어때·배민·카카오 등 선착순 쿠폰 표준 | 선착순 쿠폰 프로덕션 사례 미발견 |
| 인프라 | MSK 추가 필요 | 기존 Redis 활용 |

**핵심 트레이드오프**: Redis Streams는 Lua로 INCR+XADD를 원자화할 수 있어 dual-write gap이 원천 제거된다. 그러나 카운터와 큐가 같은 Redis에 집중되어 단일 장애점 리스크가 커진다.

**장애 도메인 분리 분석**:

Redis 완전 장애 시 INCR 자체가 실패하므로 큐에 메시지가 들어갈 일이 없다 — 이 상황에서는 Kafka든 Redis Streams든 차이 없음. 차이가 나는 구간은 **INCR 성공 후 publish 실패 시**:
- Kafka: DECR 보상으로 처리. 앱 크래시 시 카운터 1 높게 남을 수 있음 (1개 덜 발급, 초과발급보다 안전)
- Redis Streams: Lua 원자화로 이 상황 자체가 발생하지 않음

**결정과 이유**:

Kafka를 우선 구현한다.
1. 선착순 쿠폰 도메인에서 실무 표준 (여기어때, 배민, 카카오)
2. 디스크 복제 기반 내구성 (ISR)
3. Kafka 운영 복잡도(Consumer lag, DLQ, partition)를 직접 체험하는 학습 가치

Kafka의 dual-write gap 한계를 확인한 뒤, Redis Streams + Lua 원자화를 대안으로 테스트하여 트레이드오프를 비교한다.

### 2026-03-24: 구현 결정 — Redis SET 중복 체크 + Kafka Consumer 설정

**문제/질문**:
1. Kafka 비동기 전략에서 DB 기반 중복 체크(`existsByCouponIdAndUserId`)가 동작하지 않음 — Consumer가 아직 INSERT 안 했을 수 있어 같은 유저의 두 번째 요청이 통과
2. Step 2와 Step 3의 공정한 비교를 위해 동일 조건 필요
3. Kafka Consumer의 메시지 유실 방지 설정

**검토한 선택지와 결정**:

#### 1. 중복 체크: DB → Redis SET

Redis SET(`SADD`)을 Lua 스크립트에 통합하여 중복 체크 + INCR + 수량 체크를 원자 실행:

```lua
-- Keys: counter_key, total_key, users_key / Args: userId
local added = redis.call('SADD', KEYS[3], ARGV[1])
if added == 0 then return -2 end          -- 중복
local current = redis.call('INCR', KEYS[1])
local total = tonumber(redis.call('GET', KEYS[2]) or '0')
if current > total then
    redis.call('DECR', KEYS[1])
    redis.call('SREM', KEYS[3], ARGV[1])  -- SADD 롤백
    return -1                               -- 소진
end
return current
```

Step 2 RedisCounterStrategy도 동일하게 변경. DB `existsBy` 제거 → **API 경로에서 DB 완전 분리** (initTotalQuantity 캐싱 이후).

#### 2. 카운터 복구: 구현 안 함

- 동기 전략(Step 2): Redis 재시작 시 DB에서 `countByCouponId`로 복구 가능
- 비동기 전략(Step 3): Consumer lag 때문에 DB count < 실제 발급 수 → 복구하면 초과발급 위험
  - 예: Redis 죽고 복구 → DB count 9,950 (consumer 50개 미처리) → 카운터 9,950으로 복구 → 50명 추가 통과 → 최종 10,050건 (초과)
- 공정 비교를 위해 **둘 다 구현 안 함**. 프로덕션 관심사로만 정리.

#### 3. Kafka Producer 설정

| 설정 | 값 | 이유 |
|------|-----|------|
| `acks` | `all` | ISR 전체 확인. 단일 브로커라 acks=1과 동일하지만 의도 표현 |
| `enable.idempotence` | `true` | Producer→Broker 구간 중복 방지 |
| produce 방식 | `.send().get()` (동기) | 실패 시 DECR+SREM 보상 실행 가능. 비동기면 보상 타이밍 복잡 |

#### 4. Kafka Consumer 설정

| 설정 | 값 | 이유 |
|------|-----|------|
| `AckMode` | `RECORD` | 메시지 하나 처리 완료 후 offset 커밋 → DB INSERT 성공 보장 |
| `enable.auto.commit` | `false` | AckMode.RECORD의 전제 조건. Kafka 기본값은 true이므로 명시 필수 |

초기에 ErrorHandlingDeserializer, DefaultErrorHandler + DLT, non-retryable 예외 설정도 검토했으나 제거했다. Producer와 Consumer가 같은 앱이라 poison pill이 발생할 수 없고, 테스트 프로젝트에 과한 프로덕션 설정이었다.

#### 5. 인프라: MSK → EC2 Kafka

| 옵션 | 월 비용 |
|------|---------|
| MSK Serverless | ~$551 |
| MSK Provisioned (최소 2 브로커) | ~$69 |
| **EC2 m6i.large 1대** | **~$70** |

초기 계획은 EC2 t3.small(~$16)에 직접 설치였으나, CPU 크레딧 문제로 m6i.large로 변경하면서 비용 이점이 사라졌다. Apache 미러 다운로드 실패로 Docker 방식으로 전환. 프로덕션이라면 동일 비용에 운영 부담 없는 MSK Provisioned가 적합하다.

**결과**: 구현 완료, 통합 테스트 16개 전체 통과.

### 구현된 API 흐름

**Producer (KafkaStrategy)**:
```
1. initTotalQuantity(couponId)              ← Redis setIfAbsent (캐싱)
2. Lua: SADD(중복) + INCR(카운팅) + total 체크 ← Redis 원자 연산
3. kafkaTemplate.send().get()               ← Kafka produce (동기)
4. 실패 시 DECR + SREM 보상              ← Redis 보상
5. Coupon 로드 → transient Issuance 반환    ← id=null (DB INSERT 전)
```

**Consumer (IssuanceConsumer)**:
```
1. Kafka consume (IssuanceMessage)
2. Coupon 로드 → Issuance 생성 + save      ← DB INSERT
3. unique constraint 위반 → Spring 기본 에러 핸들러가 처리
```

**측정 항목** (부하 테스트 시):
- Step 2 대비 TPS, p99 latency
- Kafka Consumer lag (DB 반영 지연)
- DB 쓰기 부하 (Step 2 대비)
- produce 실패율
- INCR-produce gap 발생 빈도

**대안 검토 예정**: Kafka 테스트 후 Redis Streams + Lua 원자화 (INCR+XADD 단일 스크립트)

### 2026-03-25: 부하 테스트 1차 — Kafka vs Redis Counter (Coupon 조회 포함)

**문제/질문**: DB 저장을 비동기로 분리하면 처리량이 실제로 올라가는가?

**테스트 조건**:
- 인프라: App EC2 m6i.large, Kafka EC2 m6i.large (Docker), RDS db.m6g.large, ElastiCache
- 쿠폰 10,000장, 30초간 부하
- Redis Counter도 Redis SET 중복 체크로 변경하여 공정 비교 (DB existsBy 제거)
- 비동기 전략에서 API 응답용 Coupon 조회가 남아 있었음 (매 요청마다 DB SELECT 1회)

**결과**:

#### 발급 Latency (p95)

| RPS | Redis Counter | Kafka | 개선율 |
|-----|--------------|-------|--------|
| 1,000 | 3,655ms | 2,955ms | -19% |
| 2,000 | 4,637ms | 3,950ms | -15% |
| 3,000 | 4,306ms | 3,029ms | -30% |

#### 처리량 (iterations)

| RPS | Redis Counter | Kafka | 개선율 |
|-----|--------------|-------|--------|
| 1,000 | 24,470 | 24,797 | +1% |
| 2,000 | 29,577 | 34,372 | +16% |
| 3,000 | 29,532 | 40,796 | +38% |

#### Grafana 관측

| 항목 | Redis Counter | Kafka |
|------|--------------|-------|
| CPU | 100% | 100% |
| HikariCP Active | 10 포화 | 10 미만 |
| HikariCP Pending | **200** | **80** |
| Tomcat Threads | 200 포화 | 200 포화 |

**분석**: DB를 비동기로 분리한 효과는 있지만 (latency 15~30% 개선, 처리량 +38%), 비동기 전략에서 API 응답용 Coupon 조회가 남아 있어 DB를 완전히 분리하지 못한 상태였다.

### 2026-03-25: 리팩토링 — IssuanceStrategy.issue() void 반환

**문제/질문**: 비동기 전략에서 API 응답용 Coupon 조회가 매 요청마다 DB를 치고 있었음. DB를 API 경로에서 완전히 분리해야 정확한 비교가 가능.

**결정**: `IssuanceStrategy.issue()` 반환 타입을 `Issuance` → `void`로 변경. 비동기 전략에서 Coupon 조회 + transient Issuance 생성 제거. 실무에서는 동기/비동기 전략의 API 응답이 달라야 하지만, 전략 간 성능 비교 목적의 프로젝트이므로 전 전략 공통으로 void 반환.

### 2026-03-25: 부하 테스트 2차 — Kafka vs Redis Streams (DB 완전 분리)

**테스트 조건**:
- 인프라: App EC2 m6i.large, Kafka EC2 m6i.large (Docker), RDS db.m6g.large, ElastiCache
- 쿠폰 10,000장, 30초간 부하
- Coupon 조회 제거 → 비동기 전략의 API 경로에서 DB 완전 분리
- Redis Counter 결과는 1차와 동일 (동기 전략이라 void 변경의 성능 영향 없음)

**결과**:

#### 발급 Latency (p95)

| RPS | Redis Counter | Kafka | Redis Streams |
|-----|--------------|-------|---------------|
| 1,000 | 3,655ms | 1,180ms | **432ms** |
| 2,000 | 4,637ms | 9.9ms | **1.7ms** |
| 3,000 | 4,306ms | 8.7ms | **1.1ms** |
| 5,000 | - | 13.9ms | 1,752ms (붕괴) |
| 7,000 | - | 17.7ms | - |
| 10,000 | - | 458ms (붕괴) | - |

#### 처리량 (iterations)

| RPS | Redis Counter | Kafka | Redis Streams |
|-----|--------------|-------|---------------|
| 1,000 | 24,470 | 28,819 | 29,461 |
| 2,000 | 29,577 | 59,998 | **59,979** |
| 3,000 | 29,532 | 89,891 | **89,906** |
| 5,000 | - | 147,270 | 57,330 (붕괴) |
| 7,000 | - | 201,156 | - |
| 10,000 | - | 205,595 (포화) | - |

#### VU 사용량

| RPS | Redis Counter | Kafka | Redis Streams |
|-----|--------------|-------|---------------|
| 2,000 | 2,000 (포화) | 102 | 101 |
| 3,000 | 2,000 (포화) | 101 | 102 |
| 5,000 | - | 439 | 2,000 (포화) |
| 7,000 | - | 745 | - |
| 10,000 | - | 2,000 (포화) | - |

#### 정합성

- 전 구간, 전 전략 정합성 100%: 초과발급 0, 중복 0
- Kafka/Redis Streams 메시지 유실 0건: 10,000건 전부 DB 반영 확인

#### Consumer Lag

Kafka, Redis Streams 모두 Consumer 처리 속도 약 초당 120건. 10,000건 완료까지 약 80~90초 소요.

#### Grafana 관측 (2차)

| 항목 | Kafka 3,000 RPS | Kafka 10,000 RPS | Redis Streams 3,000 RPS | Redis Streams 5,000 RPS |
|------|----------------|-----------------|------------------------|------------------------|
| CPU | ~90% | 100% | ~80% | 100% |
| HikariCP Pending | 0 | 0 | 0 | 0 |
| Tomcat Threads busy | ~50 | 200 (포화) | ~10 | 200 (포화) |

**분석**:

1. **Coupon 조회 제거 효과가 극적이다**: Kafka 발급 p(95)가 3,950ms → 9.9ms로 급감 (2,000 RPS 기준). 매 요청마다 DB SELECT 1회가 2,000 RPS에서는 치명적 병목이었음.
2. **Redis Streams는 저~중 RPS에서 압도적이다**: 3,000 RPS까지 p(95) 1ms대. Lua 스크립트 내에서 XADD까지 원자 실행하므로 네트워크 왕복이 없음.
3. **하지만 Redis Streams는 5,000 RPS에서 급격히 붕괴한다**: Redis 단일 스레드가 병목. Lua 스크립트(SADD + INCR + GET + XADD)가 길어서 다른 요청이 대기.
4. **Kafka는 7,000 RPS까지 안정적이다**: Kafka produce는 네트워크 I/O 기반이라 앱 CPU를 덜 먹음. 10,000 RPS에서야 붕괴.
5. **Kafka의 실질적 천장은 ~7,000 RPS, Redis Streams는 ~4,000 RPS**: 단일 앱 인스턴스 + 단일 Redis/Kafka 브로커 기준.

## 현재 결론

DB를 API 경로에서 완전히 분리하면 비동기 전략 모두 극적인 성능 향상을 보인다. Redis Streams는 3,000 RPS까지 가장 빠르고 (p95 1ms) 원자성도 보장하지만, Redis 단일 스레드 한계로 5,000 RPS에서 붕괴한다. Kafka는 latency는 약간 높지만 (p95 ~10ms) 7,000 RPS까지 안정적이다. 선택 기준은 예상 트래픽 규모와 원자성 요구사항에 따라 달라진다.
