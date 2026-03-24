# Kafka 비동기 저장 부하 테스트

| 항목 | 내용 |
|------|------|
| 상태 | open |
| 최종 수정 | 2026-03-24 |

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
| INCR↔발행 원자성 | 불가 (외부 시스템) | Lua 스크립트로 INCR+XADD 원자 실행 가능 |
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

### 구현 계획

**API 흐름 (Producer)**:
```
1. existsByCouponIdAndUserId()        ← DB (중복 체크)
2. INCR coupon_counter:{couponId}     ← Redis (원자 연산)
3. if issued > totalQuantity:
     DECR → throw CouponExhaustedException
4. Kafka produce (couponId, userId)   ← 비동기 발행
5. produce 실패 시 → DECR 보상 → 에러 반환
6. API 응답 "발급 성공"               ← DB INSERT 없이 즉시 반환
```

**Consumer 흐름**:
```
1. Kafka consume (couponId, userId)
2. 멱등성 체크 (중복 INSERT 방지)
3. DB INSERT (Issuance 레코드)
4. 실패 시 재시도, 최종 실패 → DLQ
```

**측정 항목**:
- Step 2 대비 TPS, p99 latency
- Kafka Consumer lag (DB 반영 지연)
- DB 쓰기 부하 (Step 2 대비)
- publish 실패율
- INCR↔publish gap 발생 빈도

**대안 검토 예정**: Kafka 테스트 후 Redis Streams + Lua 원자화 (INCR+XADD 단일 스크립트)

## 현재 결론

방식 선택 완료. Kafka 우선 구현 → 한계 확인 → Redis Streams 대안 검토 순서로 진행.
