# Architecture Decision Records

## infra

| 순번 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| [0001](infra/0001-local-dev-environment.md) | 로컬 개발 환경을 Docker Compose + application.yml로 구성 | accepted | 2026-03-05 |
| [0002](infra/0002-mysql-configuration.md) | MySQL 설정 통합 (버전, 포트, 문자셋/시간대) | accepted | 2026-03-05 |
| [0003](infra/0003-testcontainers-mysql.md) | 테스트 DB를 H2에서 Testcontainers MySQL로 전환 | accepted | 2026-03-16 |
| [0004](infra/0004-incremental-dependency-addition.md) | Redis/Kafka 의존성을 Step별 점진적으로 추가 | accepted | 2026-03-16 |
| [0005](infra/0005-load-test-target-rps.md) | 부하 테스트를 고정 RPS가 아닌 점진적 증가 방식으로 수행 | accepted | 2026-03-16 |

## coupon

| 순번 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| [0001](coupon/0001-java-package-convention.md) | Java package는 underscore 없이 소문자 도메인 규칙 사용 | accepted | 2026-03-05 |
| [0002](coupon/0002-vertical-slice-first-implementation.md) | 첫 구현 단위는 Coupon 생성/조회의 수직 슬라이스로 시작 | accepted | 2026-03-05 |
| [0003](coupon/0003-controller-service-separation.md) | 컨트롤러는 입출력만 담당하고 비즈니스 로직은 CouponService로 분리 | accepted | 2026-03-05 |
