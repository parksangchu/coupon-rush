# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

선착순 쿠폰 발급 시스템. 6가지 동시성 전략을 단계별로 구현하고 k6 부하 테스트로 비교한다.
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

`IssuanceStrategy` 인터페이스를 6개 구현체가 구현한다.
`coupon.strategy` 프로퍼티로 런타임 전환. 코드 변경 없이 동일 조건에서 전략 비교 가능.
전략 비교는 `docs/strategy-comparison.md`, 기술 문서는 `docs/` 참고.

### 패키지 구조

기능별(Feature) 구조. 도메인 기능 단위로 묶고, 내부는 역할별 하위 패키지로 분리한다.

```
src/main/java/com/couponrush/
├── domain/
│   └── coupon/
│       ├── controller/    # REST 컨트롤러
│       ├── dto/           # 요청/응답 DTO
│       ├── entity/        # JPA 엔티티
│       ├── exception/     # 도메인 예외
│       ├── repository/    # JPA 리포지토리
│       ├── service/       # 비즈니스 서비스
│       └── strategy/      # 동시성 전략 (IssuanceStrategy 구현체)
├── global/                # 전역 설정, 예외 핸들러
└── common/                # 공통 유틸, 기반 클래스
```

## 작업 원칙

### 전략 진행은 실측 결과 기반으로 결정한다
각 Step은 반드시 이전 Step의 k6 부하 테스트 결과를 분석한 후에 시작한다.
"Step 1 결과에서 DB connection pool이 고갈됐다 → 그래서 Step 2로 넘어간다"처럼
이전 결과가 다음 전략의 동기가 되어야 한다.
다음 전략으로 넘어갈지, 현재 전략을 더 파볼지는 실측 데이터를 보고 결정한다.
테스트 결과 없이 다음 전략을 선제적으로 구현하지 않는다.

### 완성보다 이해가 우선이다
이 프로젝트의 목적은 6개 전략을 빠르게 완성하는 것이 아니라,
각 전략의 한계를 직접 확인하고 트레이드오프를 설명할 수 있게 되는 것이다.
부하 테스트 결과가 예상과 다르면, 원인을 분석하는 시간을 갖는다.

### 대안을 검토하고 기록한다
각 Step에서 한계를 발견하면, 다음 전략으로 넘어가기 전에 "현재 전략 안에서 해결할 수 있는 방법은 없는가"를 검토한다.
각 Step의 실측 결과, 대안 검토, 깨달음은 기술 문서(docs/)에 기록한다.
예: "connection pool을 늘리면?" → 실측 결과 → 기각 사유

### TODO.md를 최신 상태로 유지한다
작업이 완료되면 TODO.md의 해당 항목을 체크하고, 새로운 작업이 생기면 추가한다.
커밋 전에 TODO.md 업데이트가 필요한지 확인한다.

