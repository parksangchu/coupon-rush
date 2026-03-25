# Coupon Rush

선착순 쿠폰 발급 시스템. 6가지 동시성 전략을 단계별로 구현하고, k6 부하 테스트로 비교한다.

## 목적

각 전략의 한계를 직접 확인하고 트레이드오프를 설명할 수 있게 되는 것. 이전 단계의 실측 병목이 다음 단계의 동기가 된다.

## 전략 비교

| Step | 전략 | 1,000 RPS p(95) | 천장 | 병목 |
|------|------|-----------------|------|------|
| 1 | Pessimistic Lock | 5,468ms (붕괴) | ~500 RPS | DB lock contention |
| 1 | Single UPDATE | 54ms | ~1,000 RPS | DB row-level lock |
| 2 | Redis Lock | 13,053ms (붕괴) | <500 RPS | Redis 과직렬화 |
| 2 | Redis Counter | 3,655ms | ~1,000 RPS | DB INSERT (HikariCP 포화) |
| 3 | Kafka | 1,180ms | **~7,000 RPS** | 앱 CPU |
| 3 | Redis Streams | **432ms** | ~4,000 RPS | Redis 단일 스레드 |

자세한 비교: [docs/strategy-comparison.md](docs/strategy-comparison.md)

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
