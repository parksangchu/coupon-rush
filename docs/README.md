# 기술 문서

주제별 기술 문서. 가설, 설계 판단, 문제 해결 과정, 실측 결과를 기록한다.

- 템플릿: [TEMPLATE.md](TEMPLATE.md)

## 인덱스

| 주제 | 상태 | 최종 수정 |
|------|------|-----------|
| [부하 테스트 인프라 구성](infra/load-test.md) | resolved | 2026-03-19 |
| [Step 1: DB 직렬화 부하 테스트](coupon/pessimistic-lock-load-test.md) | resolved | 2026-03-23 |
| [Step 2: DB + Redis 부하 테스트](coupon/redis-lock-load-test.md) | resolved | 2026-03-24 |
| [Step 3: 비동기 저장 부하 테스트](coupon/kafka-async-load-test.md) | resolved | 2026-03-26 |
| [4차 부하 테스트 기록 (maxVUs 10,000 + p99)](coupon/4th-load-test-log.md) | resolved | 2026-03-26 |
| [전략 비교](coupon/strategy-comparison.md) | resolved | 2026-03-26 |
| [Redis Streams 컨슈머 아키텍처 설계](coupon/redis-streams-consumer-architecture.md) | resolved | 2026-03-27 |
