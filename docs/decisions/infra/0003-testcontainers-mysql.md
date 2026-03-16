# 0003 - 테스트 DB를 H2에서 Testcontainers MySQL로 전환

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-16 |
| 분류 | infra |
| 모듈 | 전체 |
| 기술 | Testcontainers, MySQL |

## 배경 (Context)

통합 테스트에서 H2 MODE=MySQL을 사용하고 있었으나, `SELECT ... FOR UPDATE` 등 락 동작이 MySQL과 달라 동시성 버그 재현이 불가능했다.

## 검토한 선택지 (Options Considered)

1. Testcontainers MySQL — 실제 MySQL 컨테이너로 테스트하여 락 동작이 운영과 동일
2. H2 MODE=MySQL 유지 — 락 동작 차이로 인해 동시성 테스트 결과를 신뢰할 수 없다

## 결정 (Decision)

Testcontainers MySQL로 전환한다.

## 구현 요약 (Implementation Summary)

- `build.gradle`에 Testcontainers MySQL 의존성 추가
- `application-test.yml`에서 H2 설정 제거, Testcontainers 설정으로 교체

## 결과 (Consequences)

- 테스트에서 `FOR UPDATE`, 데드락 등 실제 MySQL 락 동작 재현 가능
- 테스트 실행 시 Docker 필요 (CI 환경 포함)
