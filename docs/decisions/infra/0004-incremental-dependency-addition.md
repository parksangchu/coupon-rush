# 0004 - Redis/Kafka 의존성을 Step별 점진적으로 추가

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-16 |
| 분류 | infra |
| 모듈 | 전체 |
| 기술 | Redis, Kafka, Terraform |

## 배경 (Context)

4개 전략을 순서대로 구현하면서 Redis(Step 2~)와 Kafka(Step 4) 의존성을 언제 추가할지 결정이 필요했다. AWS Terraform도 Step 1에서는 RDS만, Step 2에서 ElastiCache를 추가하는 점진적 구조다.

## 검토한 선택지 (Options Considered)

1. Step별 점진적 추가 — Terraform 구조와 일치하고, 각 Step에서 필요한 것만 존재
2. 한번에 추가 + autoconfigure exclude — yml 관리가 번거롭고 설정 실수 가능성이 높다
3. 한번에 추가 + Docker로 전부 띄우기 — AWS Step 1에서 Redis 없이 앱 실행 시 실패 확인

## 결정 (Decision)

Redis/Kafka 의존성을 Step별로 점진적으로 추가한다.

## 구현 요약 (Implementation Summary)

- Step 1: MySQL + JPA + Actuator만 의존성에 포함
- Step 2: Redis(Redisson) 의존성 추가
- Step 4: Kafka 의존성 추가

## 결과 (Consequences)

- 각 Step에서 불필요한 의존성이 없어 앱 실행이 단순
- AWS 환경과 로컬 환경의 의존성이 일치
