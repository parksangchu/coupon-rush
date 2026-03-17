# 0004 - Issuance → Coupon 간 FK 제약 조건 미사용

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-17 |
| 분류 | architecture |
| 모듈 | coupon |
| 기술 | JPA, MySQL |

## 배경 (Context)

Issuance 엔티티는 Coupon과 `@ManyToOne` 관계를 갖는다. JPA 기본 동작은 FK 제약 조건을 자동 생성하는데, `ddl-auto: create-drop` 환경에서 시작 시 FK drop → 테이블이 아직 없어서 에러가 발생했다. 테스트 동작에는 영향이 없지만, 불필요한 에러 로그가 남는 문제가 있었다.

## 검토한 선택지 (Options Considered)

1. **FK 유지 (기본 동작)** — JPA가 자동 생성하는 FK를 그대로 사용
   - 장점: 참조 무결성을 DB 레벨에서 보장
   - 단점: `create-drop` 환경에서 에러 로그 발생. 향후 대량 데이터 삭제/마이그레이션 시 FK가 제약이 될 수 있음

2. **FK 제거 (`ConstraintMode.NO_CONSTRAINT`)** — JPA 연관관계는 유지하되 DB FK만 제거
   - 장점: DDL 에러 제거, 스키마 유연성 확보
   - 단점: DB 레벨 참조 무결성 없음

## 결정 (Decision)

FK 제약 조건을 제거한다. `@JoinColumn`에 `foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)`를 적용한다.

- 이 프로젝트는 단일 애플리케이션이 데이터를 관리하므로 참조 무결성은 애플리케이션 레벨에서 보장한다
- 중복 발급 방지는 유니크 제약(`coupon_id, user_id`)이 담당한다
- 실무에서도 MSA나 대규모 시스템에서는 FK를 의도적으로 걸지 않는 경우가 많다

## 구현 요약 (Implementation Summary)

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "coupon_id", nullable = false,
    foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
private Coupon coupon;
```

JPA 연관관계(`@ManyToOne`)는 그대로 유지되어 객체 탐색이 가능하고, DB 레벨 FK만 생성하지 않는다.

## 결과 (Consequences)

- 긍정: DDL 에러 로그 제거, 스키마 관리 단순화
- 부정: 잘못된 coupon_id가 삽입되어도 DB가 거부하지 않음 (애플리케이션에서 방어)

## 성과 및 지표 (Impact & Metrics)

- Testcontainers 기반 통합 테스트에서 FK 관련 에러 로그 0건

## 관련 문서 (References)

- `Issuance.java` — FK 제거 적용
