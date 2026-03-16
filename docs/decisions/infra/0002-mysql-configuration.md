# 0002 - MySQL 설정 통합 (버전, 포트, 문자셋/시간대)

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-05 |
| 분류 | infra |
| 모듈 | 전체 |
| 기술 | MySQL 8.4, Docker Compose |

## 배경 (Context)

로컬 MySQL의 버전, 호스트 포트, 문자셋/콜레이션/시간대 관리 방식을 결정해야 했다.

## 검토한 선택지 (Options Considered)

**버전:**
1. MySQL 8.4 (LTS) — 안정성과 장기 유지보수 관점에서 유리
2. MySQL 9.x — 최신 기능은 있으나 초기 프로젝트 기준으로는 안정성보다 이점이 작다
3. MySQL 8.0 latest patch — 사용 가능하지만 장기 지원 명확성에서 8.4 LTS가 우선

**포트:**
1. `13306` — 로컬 환경의 기존 MySQL 충돌을 방지
2. `3306` — 환경에 따라 이미 사용 중일 수 있고, 충돌 시 원인 추적 비용이 크다

**문자셋/시간대:**
1. 서버(docker-compose)에서 관리 — 설정 책임을 한 곳으로 모아 중복/불일치 위험을 줄인다
2. 앱 URL 파라미터도 함께 유지 — 서버 설정과 중복되어 관리 포인트가 늘어난다

## 결정 (Decision)

- MySQL 8.4 (LTS) 사용
- 호스트 포트 `13306` 고정
- 문자셋/콜레이션/시간대는 docker-compose 서버 설정에서 관리, 앱 JDBC 옵션은 최소화

## 구현 요약 (Implementation Summary)

- `docker-compose.yml`에 MySQL 8.4 이미지, 포트 `13306:3306`, 문자셋/시간대 환경변수 설정
- `application.yml`에서 JDBC URL 파라미터 최소화

## 결과 (Consequences)

- 로컬 MySQL 충돌 방지
- 문자셋/시간대 설정이 서버 한 곳에서 관리되어 불일치 위험 감소
