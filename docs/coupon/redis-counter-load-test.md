# Redis Counter 부하 테스트

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-23 |

## 배경

Step 2에서 분산 락이 lock-data 분리로 DB 락보다 오히려 느려짐을 확인했다. Step 3의 질문: **"락을 아예 없애면 처리량의 상한이 사라지는가?"** Redis INCR 원자 연산으로 락 없이 수량을 제어하며, 락과 데이터가 모두 Redis에 있는 구조.

## 타임라인

### 2026-03-23: RedisCounterStrategy 구현 — 락 제거 + DB 동기화 분리

**구현**:
- `RedisCounterStrategy`: `StringRedisTemplate` + Redis `INCR`/`DECR`
- `RedisConfig`: `@ConditionalOnProperty`로 redis-counter 전략일 때만 Lettuce 연결
- `spring-data-redis` + `lettuce-core` 직접 의존 (starter 사용 시 자동 설정이 Spring Boot 4.x에서 exclude 불가하여 non-Redis 전략 배포 실패)

**설계 결정**:

1. **DB 동기화 분리**: `coupon.issuedQuantity`를 DB에서 관리하지 않음. Redis 카운터가 발급 수량의 source of truth. DB에는 Issuance INSERT만 수행.

```
흐름:
1. existsByCouponIdAndUserId()        ← DB (중복 체크)
2. INCR coupon_counter:{couponId}     ← Redis (원자 연산, 락 없음)
3. if issued > totalQuantity:
     DECR coupon_counter:{couponId}   ← Redis
     throw CouponExhaustedException   ← 거절: Redis만으로 완료
4. findById(couponId)                 ← DB SELECT (Issuance 생성용)
5. issuanceRepository.save()          ← DB INSERT
```

2. **전략별 `getIssuedCount()` 구현**: `IssuanceStrategy` 인터페이스에 `getIssuedCount(Long couponId)` 추가. DB 전략은 `coupon.getIssuedQuantity()`, Redis Counter는 Redis 카운터에서 읽음. `getStatus()` API와 테스트가 전략에 맞는 소스에서 조회.

3. **`@Transactional` 사용 가능**: 락이 없으므로 Step 2의 커밋-락 순서 문제가 없음. DB 작업(중복 체크 + INSERT)을 하나의 트랜잭션으로 처리.

4. **totalQuantity lazy 캐시**: Redis 키 `coupon_total:{couponId}`에 첫 요청 시 DB에서 읽어 `setIfAbsent()`. 이후 요청은 Redis에서만 조회.

### 2026-03-23: Redis Counter 부하 테스트 — 락 제거의 효과

**테스트 환경**: App EC2 m6i.large, RDS db.m6g.large, ElastiCache cache.m6g.large, Test EC2 m6i.large

#### 500 RPS — 정합성 검증 (쿠폰 1,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 1,000 | 1,090ms | 2,022ms | 2,544ms |
| 거절 | 13,157 | 360ms | 1,953ms | 5,668ms |

- 정합성 OK, checks 100%, **초과발급 0%** ✓
- dropped 844, max VU 836

#### 500 RPS — 성능 비교 (쿠폰 10,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 10,000 | 10ms | 20ms | 239ms |
| 거절 | 5,000 | 3.6ms | 4ms | 32ms |

- 정합성 OK, **초과발급 0%** ✓
- **dropped 0**, max VU **32**
- `getStatus()` API: `issued=10000, remaining=0` — Redis 카운터에서 정상 조회

#### 1,000 RPS — 성능 비교 (쿠폰 10,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 10,000 | 8.5ms | 16ms | 111ms |
| 거절 | 20,001 | 3.9ms | 4.6ms | 41ms |

- 정합성 OK, **초과발급 0%** ✓
- **dropped 0**, max VU **10**
- 30,001건 전량 처리 (목표 30,000)

**모니터링 (1,000 RPS, Grafana)**:

| 지표 | Redis Counter |
|------|-------------|
| HikariCP Active | 5~9 (max 10) |
| HikariCP Pending | 0 |
| Tomcat busy threads | ~60 (max 200) |
| Tomcat Connections | ~100 |
| System CPU | ~50% |

#### 2,000 RPS — 한계 측정 (쿠폰 10,000개)

**Single UPDATE (비교 대상)**:

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 9,952 | 6,261ms | 8,706ms | 14,542ms |
| 거절 | 0 | - | - | - |

- **48개 미발급** — 쿠폰 소진 전에 테스트 종료
- **dropped 50,047 (83%)**, max VU 2,000

**Redis Counter**:

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 10,000 | 3,691ms | 6,270ms | 10,328ms |
| 거절 | 9,134 | 2,381ms | 3,502ms | 7,186ms |

- 정합성 OK, **초과발급 0%** ✓
- **dropped 40,864 (68%)**, max VU 2,000
- check 1건 실패 (네트워크 노이즈, 앱 에러 로그 없음)

**2,000 RPS 비교**:

| 지표 | Single UPDATE | Redis Counter |
|------|--------------|---------------|
| 발급 건수 | 9,952 (48 미발급) | **10,000** |
| 발급 avg | 6,261ms | **3,691ms** (-41%) |
| 발급 p95 | 8,706ms | **6,270ms** (-28%) |
| dropped | 50,047 (83%) | **40,864 (68%)** |

**모니터링 (2,000 RPS, Grafana)**:

| 지표 | Single UPDATE | Redis Counter |
|------|--------------|---------------|
| HikariCP Pending | ~200 | ~200 |
| System CPU | ~100% | ~100% |
| Tomcat Connections | ~2,000 | ~2,000 |

두 전략 모두 **HikariCP Pending ~200, CPU ~100%**. 병목 원인이 다름:
- **Single UPDATE**: 모든 요청(발급+거절)이 DB를 거침 → DB 커넥션 경합
- **Redis Counter**: 거절은 Redis에서 끝나지만, 발급 10,000건의 **DB INSERT가 동시에 몰림** → DB 커넥션 경합

#### 전략 비교 종합 (1,000 RPS, 10,000개)

| 지표 | Single UPDATE | Pessimistic Lock | Redis Lock | Redis Counter |
|------|--------------|-----------------|------------|---------------|
| 발급 건수 | 10,000 | 10,000 | 6,596 | **10,000** |
| 발급 avg | 13ms | 3,690ms | 10,190ms | **8.5ms** |
| 발급 p95 | 54ms | 5,468ms | 13,053ms | **16ms** |
| 거절 p95 | 2.8ms | 3,594ms | - | **4.6ms** |
| dropped | 0 | 11,517 (38%) | 23,404 (78%) | **0** |
| max VU | 54 | 2,000 | 2,000 | **10** |

**분석**:

1. **락 제거의 효과**: Redis Counter는 1,000 RPS에서 VU 10개로 처리. Single UPDATE(VU 54)보다 5배 적은 VU. 락이 없으니 요청이 대기하지 않고 즉시 처리됨.

2. **거절 경로의 차이**: Single UPDATE는 거절도 DB UPDATE(0 rows)를 거치지만, Redis Counter는 거절이 Redis INCR + DECR로 끝남. DB 접근 없이 거절 가능.

3. **2,000 RPS에서 DB INSERT 병목 발견**: 락은 제거했지만, 발급 성공 시 DB INSERT가 동시에 몰리면서 HikariCP Pending ~200. 설계문서의 예상대로 **"DB 동기 저장이 새로운 병목"**.

4. **한계점 — INCR/DECR 비원자성**: 현재 INCR 후 totalQuantity 초과 시 DECR로 롤백하는 3단계 구조. INCR 후 DECR 전에 크래시나면 카운터가 1 높은 상태로 남을 수 있음 (초과발급은 아니고 1개 덜 발급). Lua 스크립트로 체크+증가를 원자적으로 처리하면 해결 가능.

**결정**: Redis Counter는 1,000 RPS에서 모든 전략 중 최고 성능. 2,000 RPS에서 DB INSERT 병목이 발견됐으며, 이것이 Step 4(Kafka 비동기 저장)의 동기가 된다.

전략 전환 흐름 업데이트:

```
Step 1: Pessimistic Lock (직렬화, 디스크) → 500 RPS 소화, 1,000 RPS 붕괴
Step 1 서브: Single UPDATE (원자 연산, 디스크) → 1,000 RPS 여유, 2,000 RPS에서 한계
Step 2: Redis Lock (직렬화, 인메모리) → 500 RPS에서 이미 붕괴. DB 락보다 악화
Step 3: Redis Counter (원자 연산, 인메모리) → 1,000 RPS 여유, 2,000 RPS에서 DB INSERT 병목
  ↓ "DB INSERT를 비동기로 분리하면?"
Step 4: Redis Counter + Kafka (원자 연산 + 비동기 저장) → ? TPS
```

## 현재 결론

**Redis Counter는 락 제거로 1,000 RPS에서 최고 성능을 달성했다.**

- 1,000 RPS: VU 10개, 발급 p95 16ms, dropped 0 — 모든 전략 중 최고
- Single UPDATE 대비: 발급 avg 35% 빠르고, VU 5배 적음
- 거절 경로가 Redis에서만 처리되어 DB 부하를 크게 줄임

**2,000 RPS에서 새로운 병목 발견**: DB INSERT가 동시에 몰리면서 HikariCP Pending ~200. 락은 제거했지만, 발급 성공 건의 DB 동기 저장이 병목.

**다음 단계**: Step 4(Redis Counter + Kafka)에서 DB INSERT를 Kafka로 비동기화하여 API 응답에서 DB 의존성을 제거한다. API는 Redis INCR + Kafka produce만 수행하고, Consumer가 DB INSERT를 자체 속도로 처리.
