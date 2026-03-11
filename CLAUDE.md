# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

선착순 쿠폰 발급 시스템. 5가지 동시성 전략을 단계별로 구현하고 k6 부하 테스트로 비교한다.
단일 서비스 구조 (MSA 아님). 전략 간 순수 성능 차이를 측정하기 위해 변수를 통제한다.

## 기술 스택

Java 21, Spring Boot 4.x, MySQL, Redis (Redisson), Kafka
Testcontainers, k6, Prometheus + Grafana, Docker Compose
Terraform, AWS (EC2, RDS, ElastiCache, MSK)

## 빌드 및 실행

```bash
# 로컬 인프라 실행 (개발/디버깅용)
docker compose up -d

# AWS 인프라 프로비저닝 (부하 테스트용)
cd terraform && terraform init && terraform apply

# 빌드
./gradlew build

# 실행 (전략 지정)
./gradlew bootRun --args='--coupon.strategy=pessimistic'

# 테스트 (Docker 필요 — Testcontainers 사용)
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "com.couponrush.strategy.PessimisticLockStrategyTest"

# 부하 테스트
k6 run k6/scenarios/spike.js
```

## 아키텍처

### 전략 패턴

`IssuanceStrategy` 인터페이스를 5개 구현체가 구현한다.
`coupon.strategy` 프로퍼티로 런타임 전환. 코드 변경 없이 동일 조건에서 전략 비교 가능.

전략 순서 — 이전 전략의 실측 한계가 다음 전략의 동기가 된다:

| 순서 | 전략 | 발견한 한계 |
|------|------|------------|
| Step 1 | Pessimistic Lock | DB connection 고갈 |
| Step 2 | Optimistic Lock | retry storm |
| Step 3 | Redis Lock | 병목 이동 (여전히 직렬화) |
| Step 4 | Redis Counter | Redis 장애 시 정합성 |
| Step 5 | Kafka Queue | 즉시 응답 불가 |

### 패키지 구조

기능별(Feature) 구조. 도메인 기능 단위로 엔티티·서비스·컨트롤러·DTO를 함께 둔다.

```
src/main/java/com/couponrush/
├── domain/
│   └── coupon/       # Coupon(root) + Issuance 애그리거트, 전략, 서비스, 컨트롤러, DTO
├── global/           # 전역 설정, 예외 핸들러
└── common/           # 공통 유틸, 기반 클래스
```

## 작업 원칙

### 전략 진행 순서를 임의로 건너뛰지 않는다
각 Step은 반드시 이전 Step의 k6 부하 테스트 결과를 분석한 후에 시작한다.
"Step 1 결과에서 DB connection pool이 고갈됐다 → 그래서 Step 2로 넘어간다"처럼
이전 결과가 다음 전략의 동기가 되어야 한다.
테스트 결과 없이 다음 전략을 구현하지 않는다.

### 의사결정의 주체는 사용자다
Claude는 구현과 설명을 담당한다. 다음 전략으로 넘어갈지, 현재 전략을 더 파볼지는 사용자가 결정한다.
사용자가 요청하지 않은 다음 Step을 선제적으로 구현하지 않는다.

### 완성보다 이해가 우선이다
이 프로젝트의 목적은 5개 전략을 빠르게 완성하는 것이 아니라,
각 전략의 한계를 직접 확인하고 트레이드오프를 설명할 수 있게 되는 것이다.
부하 테스트 결과가 예상과 다르면, 원인을 분석하는 시간을 갖는다.

### 대안을 검토하고 기록한다
각 Step에서 한계를 발견하면, 다음 전략으로 넘어가기 전에 "현재 전략 안에서 해결할 수 있는 방법은 없는가"를 검토한다.
검토한 대안과 왜 기각했는지를 설계문서에 기록한다.
예: "connection pool을 늘리면?" → 실측 결과 → 기각 사유

## 커밋 컨벤션

기술적 결정이 있는 커밋에는 반드시 [결정]과 [근거]를 포함한다.
git log가 곧 사고 과정 기록이다. 자세한 규칙은 `~/.claude/CLAUDE.md` 참고.

## 참고 파일

- `설계문서.md`: 전체 설계, 전략별 깨달음, 기술 스택 선택 근거
