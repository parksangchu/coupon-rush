# Redis Distributed Lock 부하 테스트

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-23 |

## 배경

Step 1에서 DB 직렬화(Pessimistic Lock)의 한계와 DB 원자 연산(Single UPDATE)의 상한을 실측했다. Single UPDATE는 1,000 RPS를 VU 54개로 소화했지만, 여전히 DB row-level X lock으로 직렬화되는 구조적 한계가 있다.

Step 2의 질문: **"분산 락은 정말 동시성 문제의 해결책인가?"** 락 판단을 DB에서 Redis(인메모리)로 이동하면 처리량이 올라가는가, 아니면 병목만 이동하는가?

## 타임라인

### 2026-03-23: RedisLockStrategy 구현 — 트랜잭션-락 순서 문제

**구현**:
- `RedisLockStrategy`: Redisson `RLock` + `TransactionTemplate`
- `RedissonConfig`: `@ConditionalOnProperty`로 redis-lock 전략일 때만 Redis 연결
- Terraform: ElastiCache Redis (cache.m6g.large, 단일 노드)

**설계 결정**:

1. **lock 범위**: 최소 — 중복 체크는 lock 밖, 수량 차감만 lock 안 (Single UPDATE와 변수 통일)

```
흐름:
1. existsByCouponIdAndUserId()        ← lock 밖
2. lock.tryLock(waitTime, leaseTime)  ← Redis 락 획득
3. transactionTemplate.execute:
   - findById(couponId)               ← plain SELECT
   - coupon.issue()                   ← 수량 체크 + 차감
   - issuanceRepository.save()        ← INSERT
   - (execute 반환 시 커밋 완료)
4. lock.unlock()                      ← 커밋 후 락 해제
```

2. **TransactionTemplate을 쓴 이유**: `@Transactional`은 AOP 프록시라서 메서드 반환 후 커밋된다. 그런데 `lock.unlock()`은 finally 블록(메서드 반환 전)에서 실행된다. 즉 **락 해제 → 커밋** 순서가 되어, 다른 스레드가 커밋 전 데이터를 읽을 수 있다. `TransactionTemplate.execute()` 반환 시점에 커밋이 완료되므로, 그 후에 `lock.unlock()`하면 **커밋 → 락 해제** 순서가 보장된다.

   이 문제는 분산 락의 숨은 복잡도다. DB 락(Pessimistic Lock)은 트랜잭션 커밋 시 자동 해제되어 순서 문제가 없지만, Redis 락은 수동 해제이므로 개발자가 커밋 타이밍을 직접 관리해야 한다.

3. **timeout 외부화**: `application.yml`의 `coupon.redis-lock.wait-time`/`lease-time`으로 조절 가능. 정상 테스트는 넉넉하게(5s/10s), 정합성 검증 실험에서 짧게 줄이는 용도.

### 2026-03-23: Redis Lock 부하 테스트 — 분산 락의 구조적 한계

**테스트 환경**: App EC2 m6i.large, RDS db.m6g.large, ElastiCache cache.m6g.large, Test EC2 m6i.large

#### 500 RPS — 정합성 검증 (쿠폰 1,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 1,000 | 4,902ms | 7,886ms | 8,718ms |
| 거절 | 5,883 | 9,190ms | 10,547ms | 10,807ms |

- 정합성 OK, checks 100%, **초과발급 0%** ✓
- **dropped 8,117 (54%)**, max VU **2,000 (상한 도달)**
- 실제 처리 6,883/15,000

#### 500 RPS — 성능 비교 (쿠폰 10,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 6,285 | 9,998ms | 13,659ms | 14,934ms |
| 거절 | 0 | - | - | - |

- 정합성 OK, **초과발급 0%** ✓
- **dropped 8,716 (58%)**, max VU **2,000**
- **10,000개 중 6,285개만 발급** — 쿠폰이 소진되기도 전에 요청이 drop됨
- 거절 0건 — 모든 도달 요청이 발급에 성공했지만 throughput이 너무 낮아서 소진에 도달하지 못함

#### 1,000 RPS — 성능 비교 (쿠폰 10,000개)

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 | 6,596 | 10,190ms | 13,053ms | 13,127ms |
| 거절 | 0 | - | - | - |

- 정합성 OK, **초과발급 0%** ✓
- **dropped 23,404 (78%)**, max VU **2,000**
- 10,000개 중 6,596개만 발급

#### 전략 비교 (1,000 RPS, 10,000개)

| 지표 | Single UPDATE | Pessimistic Lock | Redis Lock |
|------|--------------|-----------------|------------|
| 발급 건수 | 10,000 | 10,000 | 6,596 |
| 발급 avg | 13ms | 3,690ms | 10,190ms |
| 발급 p95 | 54ms | 5,468ms | 13,053ms |
| dropped | 0 | 11,517 (38%) | 23,404 (78%) |
| max VU | 54 | 2,000 | 2,000 |

**모니터링 (Grafana)**:

| 지표 | Redis Lock |
|------|-----------|
| HikariCP Active | 1~2 (max 10) |
| HikariCP Pending | 0 |
| Tomcat busy threads | ~150~200 |
| Tomcat Connections | ~2,000 |
| System CPU | ~60% |

HikariCP Active가 1~2개: Redis 락이 완벽하게 직렬화하여 한 번에 하나만 DB에 접근. **DB 커넥션 풀이 남아도는데도 처리량이 나오지 않는 상황**.

**차이의 원인 분석**:

```
DB 락 (Pessimistic Lock):
  락과 데이터가 같은 시스템 (DB)
  → 네트워크 왕복 0회
  → lock 보유 시간 = DB 내부 처리 시간

Redis 락 (Redis Lock):
  락(Redis)과 데이터(DB)가 다른 시스템
  → lock 보유 시간 = Redis RTT + DB 트랜잭션(SELECT + UPDATE + INSERT + COMMIT) + Redis RTT
  → 네트워크 왕복이 lock 보유 시간에 추가됨
```

설계문서의 가설 "병목이 DB에서 Redis로 이동"보다 결과가 나쁘다. 이동이 아니라 **악화**다. 이유:
1. **lock-data 분리**: 락(Redis)과 데이터(DB) 사이를 매 요청마다 네트워크로 왕복
2. **거절 경로도 lock 필요**: Single UPDATE는 UPDATE 0 rows로 즉시 반환하지만, Redis Lock은 거절도 lock을 잡고 → DB 조회 → 소진 확인 → lock 해제를 거쳐야 함
3. **트랜잭션-락 순서 문제**: 커밋 전에 락을 풀면 정합성이 깨지므로, 커밋 완료까지 락을 보유해야 함 → 보유 시간 추가 증가

**분산 락이 유효한 경우**:

Redis 분산 락 자체가 나쁜 것은 아니다. 우리 시나리오가 최악의 조합인 것:
- **락(Redis) ≠ 데이터(DB)** — lock-data 분리로 네트워크 왕복 발생
- **초당 수백~수천 건이 같은 row에 집중** — 완전 직렬화 + 대기열 폭발
- **락 안에서 트랜잭션 커밋 필요** — 보유 시간 극대화

분산 락이 유효한 경우:
- **락과 데이터가 같은 시스템**: Redis 락 + Redis 데이터 (Step 3가 이 구조)
- **간헐적 충돌 + 낙관적 락이 부적합한 경우**: 결제 중복 방지, 스케줄러 중복 실행 등
- **경합 대상이 분산**: 서로 다른 리소스에 접근하여 lock contention이 낮은 경우
- **여러 시스템에 걸친 작업**: DB 트랜잭션 경계 밖의 작업(외부 API + DB 저장 등)을 묶어야 할 때. DB 락으로는 커버 불가

단, 분산 락의 유효 구간은 생각보다 좁다:

| 경합 수준 | 적합한 전략 |
|----------|-----------|
| 낮음 (간헐적) | 낙관적 락 — 대부분 성공, 드물게 retry |
| 중간 (가끔 충돌, 다중 시스템) | 분산 락 — 충돌 시 대기, 대기열이 짧아서 OK |
| 높음 (거의 모든 요청 충돌) | 락 자체가 부적합 → 원자 연산 |

경합이 낮으면 낙관적 락이 비용 0으로 더 효율적이고, 경합이 높으면 직렬화 자체가 병목이다. 분산 락은 "경합이 가끔 있고, 낙관적 락의 retry가 부담되며, DB 트랜잭션 경계를 넘는" 상황에서만 의미가 있다. 그 경우에도 정합성은 best-effort 수준이므로, 진짜 정합성은 멱등성 키나 Saga 패턴으로 보장해야 한다.

**결정**: Redis 분산 락은 "lock을 인메모리로 옮기면 빨라진다"는 직관과 달리, lock-data 분리로 인해 DB 자체 락보다 오히려 느리다. lock timeout 시 정합성 깨짐 위험까지 고려하면 이 시나리오에서 쓸 이유가 없다. 별도 정합성 검증 실험은 생략한다 — 정상 조건에서 이미 성능이 불합격이므로.

전략 전환 흐름 업데이트:

```
Step 1: Pessimistic Lock (직렬화, 디스크) → 500 RPS 소화, 1,000 RPS 붕괴
Step 1 서브: Single UPDATE (원자 연산, 디스크) → 1,000 RPS 여유, DB 원자 연산의 상한
Step 2: Redis Lock (직렬화, 인메모리) → 500 RPS에서 이미 붕괴. DB 락보다 악화
  ↓ "직렬화 자체가 문제다. 락을 없애면?"
Step 3: Redis Counter (원자 연산, 인메모리) → ? TPS
```

## 현재 결론

**Redis Distributed Lock은 이 시나리오에서 최악의 전략이다.**

- 정합성은 보장(초과발급 0%)하지만, 500 RPS에서 이미 붕괴 (54% dropped, VU 2,000)
- DB 자체 락(Pessimistic Lock)보다 느리다 — lock-data 분리로 네트워크 왕복이 추가되어 병목이 이동이 아니라 악화
- lock timeout 시 정합성 깨짐 위험이라는 추가 복잡도까지 존재
- HikariCP Active 1~2개: DB 커넥션이 남아돌지만 Redis 락이 직렬화 병목

**핵심 깨달음**: "인메모리 = 빠르다"는 락과 데이터가 같은 곳에 있을 때만 성립한다. 락과 데이터가 분리되면 네트워크 왕복이 lock 보유 시간에 추가되어 오히려 느려진다.

**다음 단계**: Step 3(Redis Atomic Counter)에서 락 자체를 제거한다. `INCR` 원자 연산으로 락 없이 수량을 제어하며, 락과 데이터가 모두 Redis에 있으므로 lock-data 분리 문제가 사라진다.
