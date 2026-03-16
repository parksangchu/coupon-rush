# 0001 - 로컬 개발 환경을 Docker Compose + application.yml로 구성

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-05 |
| 분류 | infra |
| 모듈 | 전체 |
| 기술 | Docker Compose, Spring Boot |

## 배경 (Context)

MySQL, Redis, Kafka 등 로컬 의존 서비스를 어떻게 관리할지, 설정 파일 형식을 무엇으로 할지 결정이 필요했다.

## 검토한 선택지 (Options Considered)

**의존 서비스 관리:**
1. Docker Compose — 동일 명령으로 올리고 내릴 수 있어 온보딩과 재현성이 좋다
2. 수동 설치 — 팀원/환경마다 편차가 커서 재현성이 떨어진다
3. Testcontainers only — 테스트에는 유효하지만 개발 중 상시 서비스 확인 용도로는 compose가 더 직관적이다

**설정 파일 형식:**
1. `application.yml` — 계층형 설정(`spring.datasource`, `spring.jpa`, `management`, `coupon.*`)을 읽기 쉽게 유지할 수 있다
2. `application.properties` — 단순 설정에는 충분하나 설정이 늘어날수록 가독성이 떨어질 수 있다

## 결정 (Decision)

- 로컬 의존 서비스는 Docker Compose로 관리한다.
- 설정 파일 기본 형식은 `application.yml`을 사용한다.

## 구현 요약 (Implementation Summary)

- `docker-compose.yml`에 MySQL, Redis, Kafka, Prometheus, Grafana 정의
- `application.yml`에 datasource, jpa, management 등 계층형 설정 구성

## 결과 (Consequences)

- `docker compose up -d` 한 줄로 전체 로컬 환경 구동 가능
- 설정 추가 시 yml 계층 구조로 가독성 유지
