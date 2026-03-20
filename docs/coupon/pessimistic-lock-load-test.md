# Pessimistic Lock 부하 테스트

| 항목 | 내용 |
|------|------|
| 상태 | in-progress |
| 최종 수정 | 2026-03-19 |

## 배경

선착순 쿠폰 발급의 첫 번째 전략으로 Pessimistic Lock(`SELECT ... FOR UPDATE`)을 구현했다. 로컬 테스트(p99=195ms, max VU 87)에서는 정합성이 보장됨을 확인했고, AWS 환경에서 실제 부하 수준의 성능과 병목 지점을 파악하기 위해 부하 테스트를 수행했다.

## 타임라인

### 2026-03-17: 첫 번째 AWS 부하 테스트 — 문제 발견

**문제/질문**: AWS 환경에서 Pessimistic Lock의 실제 성능은?

**테스트 환경**:
- App: EC2 t3.small, Java 21, Spring Boot 4.x
- DB: RDS db.t3.small, MySQL 8.4
- Test: EC2 t3.small, k6 v0.56.0
- 조건: 500 RPS, 쿠폰 100개, 30초

**결과**: p50=5.28s, 총 10,338 요청 중 100건 성공 / 10,236건 거절(409)

**발견한 문제 3가지**:
1. **모니터링 부재**: 느린 원인이 DB connection pool 고갈인지, lock wait인지, 네트워크 문제인지 특정 불가
2. **성능 지표 왜곡**: 99%가 409 거절 → `http_req_duration`은 사실상 거절 응답 지연만 반영. 발급 성공 성능을 알 수 없음
3. **부하 생성기 병목 가능성**: VU가 2,000까지 도달 — 앱이 느려서인지, k6 자체 리소스 부족인지 구분 불가

**결정과 이유**: 이 결과로는 Pessimistic Lock의 성능을 평가할 수 없다. 모니터링 추가 + 메트릭 분리 후 재테스트가 필요하다.

### 2026-03-19: 재테스트 — 모니터링 추가 + 메트릭 분리

**개선 사항**:
1. **Prometheus + Grafana 추가**: test EC2에 Docker Compose로 배치, 앱 서버의 Actuator 메트릭 수집
2. **k6 커스텀 메트릭**: `Trend`/`Counter`로 200(발급 성공)과 409(거절)의 응답 시간을 분리 측정
3. **배포 자동화**: `scripts/deploy-app.sh`, `scripts/deploy-monitoring.sh`, `scripts/run-k6.sh` 작성 — 반복 테스트 효율화

**테스트 환경**: 동일 (500 RPS, 쿠폰 100개, 30초)

**결과**:

| 구분 | 건수 | avg | p90 | p95 |
|------|------|-----|-----|-----|
| 발급 성공 (200) | 100 | 635ms | 943ms | 1,005ms |
| 거절 (409) | 12,968 | 3,297ms | 4,385ms | 5,030ms |

- 정합성: 100개 발급, 초과발급 없음
- 총 요청: 13,068 (목표 15,000 중 1,933 dropped)
- VU: 최대 1,802

**모니터링 데이터**:
- `hikaricp_connections_active`: 10 (pool size 전체 사용, 포화 상태)
- `hikaricp_connections_pending`: 최대 ~190 (connection 대기 요청이 190개까지 적체)
- `system_cpu_usage`: 거의 0 (CPU는 병목이 아님)

### 2026-03-19: 10,000개 재테스트 — 정합성 검증 + 성능 비교

**문제/질문**: 이전 테스트는 쿠폰 100개로 진행했다. 100개는 0.2초 만에 소진되어 거절 성능만 측정하는 것에 가까웠다. 설계문서 기준에 맞는 테스트가 필요하다.

**테스트 조건 재설계**: 테스트를 목적별로 이원화했다. 상세 논의 과정은 [부하 테스트](../infra/load-test.md) 참고.

**테스트 1 — 정합성 검증 (쿠폰 1,000개)**:

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 (200) | 1,000 | 3,631ms | 5,678ms | 7,045ms |
| 거절 (409) | 8,079 | 6,535ms | 8,937ms | 16,342ms |

- 정합성: **1,000개 전부 소진, 초과발급 0%** ✓
- 총 요청: 15,001 시도, 9,079 완료, **5,922 dropped (39.5%)**
- VU: 최대 2,000 도달 (Insufficient VUs 경고)

**테스트 2 — 성능 비교 (쿠폰 10,000개)**:

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 (200) | 8,141 | 6,863ms | 10,670ms | 15,002ms |
| 거절 (409) | 0 | - | - | - |

- **10,000개 중 8,141개만 발급, 1,859개 미소진** — 30초 + gracefulStop 9초 내에 소진 불가
- 거절 0건: 쿠폰이 남아있어 거절 자체가 발생하지 않음
- 총 요청: 15,000 시도, 8,141 완료, **6,859 dropped (45.7%)**
- 실측 처리량: **~207 TPS**

**모니터링 데이터**:
- `hikaricp_connections_active`: 10 (pool size 포화)
- `hikaricp_connections_pending`: ~200까지 급증 (테스트 1과 동일 패턴)
- `system_cpu_usage`: ~5% 미만 (CPU는 병목 아님)
- 메트릭 수집 빈 구간 발생: 앱 과부하로 Prometheus scrape 요청도 처리 못함 → management port 분리 필요

### 2026-03-19: 재테스트 — management port 분리 + Tomcat 메트릭 추가

**개선 사항**:
1. **management port 분리** (`management.server.port: 9090`): Actuator를 별도 스레드풀로 분리하여 앱 과부하 시에도 메트릭 수집 보장
2. **Tomcat 메트릭 활성화** (`server.tomcat.mbeanregistry.enabled: true`): Spring Boot 3.x 기본값이 false — 스레드/커넥션 메트릭 수집
3. **Grafana 대시보드 확장**: Tomcat Threads(current/busy/max), Tomcat Connections(current/keepalive) 패널 추가

**테스트 조건**: 500 RPS, 쿠폰 10,000개, 30초

**결과**:

| 구분 | 건수 | avg | p95 | max |
|------|------|-----|-----|-----|
| 발급 성공 (200) | 7,201 | 8,195ms | 11,072ms | 15,518ms |

- 10,000개 중 7,201개 발급, 2,799개 미소진
- 실측 처리량: **~182 TPS**
- dropped_iterations: 7,799 (52%)
- 정합성: 초과발급 0%

**모니터링 데이터 (메트릭 갭 없이 수집 완료)**:
- `hikaricp_connections_active`: 10/10 포화
- `hikaricp_connections_pending`: **~200까지 급증**
- `tomcat_threads_current`: 200/200 (max 도달, 전부 busy)
- `tomcat_connections_current`: **~2,000** (max 8,192 대비 여유 — Tomcat 레벨은 병목 아님)
- `system_cpu_usage`: **60~80%** (이전 대비 상승, 하지만 100%는 아님)
- `process_cpu_usage`: ~60%

**병목 체인 확인**:
```
요청 → Tomcat Connection(2K/8K, 여유) → Thread(200/200, 포화) → HikariCP(10/10, pending 200) → DB Lock(직렬화)
```

Tomcat connections가 8,192(max)까지 도달하지 않았다는 것은 Tomcat 자체가 병목이 아니라는 의미. 200개 스레드가 전부 HikariCP connection 대기로 blocking되면서 처리량이 제한된다. CPU는 60~80%로 여유가 있는데도 처리량이 ~182 TPS인 이유는 **lock contention으로 인한 I/O 대기**가 지배적이기 때문이다.

**두 테스트 비교에서 드러난 점**:

| | 테스트 1 (1,000개) | 테스트 2 (10,000개) |
|--|-------------------|-------------------|
| 소진 | 100% | 81% |
| 처리량 | ~247 req/s | ~207 req/s |
| 발급 avg | 3.6s | 6.86s |
| dropped | 39.5% | 45.7% |

테스트 2가 더 느린 이유: 테스트 1은 쿠폰이 ~2초 만에 소진된 후 거절은 DB 쓰기 없이 락만 잡고 반환. 테스트 2는 39초 내내 모든 요청이 락 획득 + DB INSERT를 수행하므로 트랜잭션 시간이 더 길고, 대기 큐가 더 쌓인다.

### 2026-03-19: 병목 분석

**문제/질문**: 거절(409)이 왜 성공(200)보다 5배 느린가?

**분석**:
Pessimistic Lock의 요청 처리 흐름은 다음과 같다:

```
요청 → [1] Connection Pool 대기 → [2] DB Lock 대기 (FOR UPDATE) → [3] 재고 확인 → 응답
```

- [1] Connection Pool 대기: pool size 10개에 500 RPS가 몰리면서 pending 190개까지 적체
- [2] DB Lock 대기: connection을 잡아도, 다른 트랜잭션이 같은 row를 잡고 있으면 대기
- [3] 재고 확인: lock을 잡으면 즉시 처리 (여기는 빠름)

핵심은 **거절할 요청도 [1]과 [2]를 전부 거쳐야 한다**는 것이다. 재고가 0이라는 걸 알려면 lock을 잡아야 하고, lock을 잡으려면 connection이 필요하다. 이미 재고가 소진된 후에도 들어오는 수천 건의 요청이 모두 이 경로를 밟으면서 응답 시간이 누적된다.

**결정과 이유**: 거절이 느린 이유는 명확하다. 하지만 이것이 Pessimistic Lock의 구조적 한계인지, 튜닝으로 해결 가능한 문제인지는 대안을 검토해야 판단할 수 있다.

### 2026-03-19: 대안 검토 + 전략 전환 결정

**문제/질문**: ~182 TPS가 Pessimistic Lock의 구조적 한계인가, 현재 전략 안에서 개선할 여지가 있는가?

**Pessimistic Lock 내에서 검토한 대안**:

1. **HikariCP pool 증가 (10 → 50)** → 기각
   - lock이 단일 row를 직렬화하므로 connection이 50개여도 49개는 lock 대기
   - 병목이 pool "크기"가 아니라 lock "직렬화"

2. **DB 스펙 업 (t3.small → t3.medium)** → 기각
   - lock 보유 중 하는 일(SELECT, UPDATE, COMMIT)이 빨라지면 lock 보유 시간 단축 → 소폭 상승 가능
   - 하지만 직렬화 구조 불변. 182 → 220 정도는 가능해도 1,000 TPS는 불가
   - 비용 대비 효과 나쁘고 구조적 한계를 바꾸지 못함

3. **Tomcat threads 증가 (200 → 500)** → 기각
   - 200개가 busy인 이유가 CPU 작업이 아니라 HikariCP connection 대기(I/O blocking)
   - 500으로 늘려도 500개가 connection 대기로 blocking될 뿐

4. **앱 레벨 재고 소진 플래그** → 기각 (단, 발상은 Step 3로 이어짐)
   - 거절 성능은 개선되지만, 발급 구간 ~182 TPS는 해결 안 됨
   - 멀티 인스턴스면 플래그 동기화 필요 → 결국 외부 저장소(Redis) 필요
   - 이 발상을 확장하면 Step 3(Redis Atomic Counter)

5. **Single UPDATE로 전환** (`UPDATE ... SET quantity = quantity - 1 WHERE quantity > 0`) → 기각 (단, 개선은 됨)
   - SELECT FOR UPDATE는 lock을 잡고 → 로직 처리 → UPDATE → COMMIT까지 lock을 보유. Single UPDATE는 한 문장에서 lock 획득과 수정이 끝나므로 lock 보유 시간이 훨씬 짧다
   - 개념적으로 Redis INCR과 동일 — "한 번의 원자 연산으로 판정". 차이는 매체(디스크 vs 메모리)
   - 182 TPS보다 확실히 높아지지만, 구조적 한계는 남음:
     - InnoDB는 같은 row에 대한 concurrent UPDATE를 여전히 X lock으로 직렬화
     - 매 요청마다 DB 왕복 (네트워크 + redo log write)
     - connection pool 병목 불변
   - **Step 1 안에서 가장 실질적인 개선이지만, "DB 원자 연산"의 상한을 확인하는 것이지 구조를 바꾸는 것은 아님**

**왜 Redis인가**:

외부 저장소 조건 3가지:
- 인메모리 — DB보다 빨라야 의미 있음
- 원자적 연산 지원 — lock(SETNX) / 카운팅(INCR) 가능
- 실무 검증 — 운영 레퍼런스 충분

대안 비교:
- Memcached: INCR은 되지만 분산 락 구현이 어려움
- ZooKeeper: 분산 락은 강력하지만 처리량 목적 도구가 아님 (합의 기반, 수천 ops/s)
- Redis: 세 조건 모두 만족

**전략 전환 흐름**:

```
Step 1: DB Lock (직렬화, 디스크) → ~182 TPS
  ↓ "lock이 느린 건가, 직렬화 자체가 문제인가?"
Step 2: Redis Lock (직렬화, 인메모리) → ? TPS
  ↓ "직렬화 자체가 문제라면?"
Step 3: Redis Counter (직렬화 제거, 원자 연산) → ? TPS
```

**결정과 이유**: 5가지 대안을 검토한 결과, 1~4는 직렬화 구조를 바꾸지 못한다. 5번(Single UPDATE)은 lock 보유 시간을 줄여 실질적 개선이 가능하지만, DB 직렬화 + connection pool 한계는 남는다. 같은 "원자 연산" 개념을 인메모리로 옮기면 이 한계를 넘을 수 있다 → Redis로 전환한다.

## 현재 결론

Pessimistic Lock은 정합성은 완벽하지만(1,000개 테스트에서 초과발급 0%), 고부하에서 구조적 한계가 확인됐다:

1. **처리량 상한 ~182 TPS**: `FOR UPDATE`가 단일 row를 직렬화하므로 RPS를 올려도 처리량이 늘지 않는다. 10,000개 쿠폰을 소진하려면 ~55초가 필요하지만, 테스트 39초 내에 7,201개만 발급.
2. **거절도 느리다**: 재고가 0이라는 것을 확인하려면 lock을 잡아야 하고, lock을 잡으려면 connection이 필요하다. 거절 요청도 발급과 동일한 경로를 거치면서 p95=8.9s.
3. **병목은 DB lock + connection pool**: CPU 60~80%로 여유가 있지만, Tomcat threads 200개가 전부 HikariCP connection 대기로 blocking. Tomcat connections(2K/8K)에는 여유가 있어 Tomcat 자체는 병목이 아님.
4. **대안 검토 완료**: 5가지 대안 검토. 1~4(pool 증가, DB 스펙 업, threads 증가, 앱 플래그)는 직렬화 구조를 바꾸지 못해 기각. 5번(Single UPDATE)은 lock 보유 시간 단축으로 개선 가능하지만 DB 직렬화 + connection pool 한계는 남음. 같은 원자 연산 개념을 인메모리(Redis)로 옮겨야 구조적 돌파가 가능하다.

**전략 전환**: Step 2(Redis Distributed Lock)에서 lock을 인메모리로 옮겨 "직렬화가 느린 건 DB라서인가, 직렬화 자체가 문제인가"를 검증한다.
