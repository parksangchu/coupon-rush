# 0001 - Java package는 underscore 없이 소문자 도메인 규칙 사용

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-05 |
| 분류 | architecture |
| 모듈 | coupon |
| 기술 | Java |

## 배경 (Context)

프로젝트명이 `coupon-rush`인데, Java package명에 하이픈을 쓸 수 없어 변환 규칙을 정해야 했다.

## 검토한 선택지 (Options Considered)

1. `com.couponrush` — Java 표준 관례와 도구/라이브러리 생태계의 기본 가정에 부합
2. `com.coupon_rush` — 문법상 가능하지만 관례 위반으로 유지보수 시 혼란

## 결정 (Decision)

`com.couponrush`를 사용한다.

## 결과 (Consequences)

- Java 표준 관례를 따르므로 도구/라이브러리와의 호환성 보장
