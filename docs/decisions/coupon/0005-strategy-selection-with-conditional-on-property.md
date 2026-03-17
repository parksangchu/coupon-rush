# 0005 - 전략 선택 방식으로 @ConditionalOnProperty 사용

| 항목 | 내용 |
|------|------|
| 상태 | accepted |
| 날짜 | 2026-03-17 |
| 분류 | architecture |
| 모듈 | coupon |
| 기술 | Spring Boot, Strategy Pattern |

## 배경 (Context)

4가지 동시성 전략(Pessimistic Lock, Redis Lock, Redis Counter, Redis+Kafka)을 동일 코드베이스에서 런타임 전환할 수 있어야 한다. 코드 변경 없이 프로퍼티만 바꿔서 전략을 교체하고, 동일 조건에서 성능을 비교하는 것이 목적이다.

## 검토한 선택지 (Options Considered)

1. **`@ConditionalOnProperty`** — 프로퍼티 값에 따라 하나의 빈만 등록
   - 장점: 가장 단순, Spring Boot 관용적, 불필요한 빈이 컨텍스트에 로드되지 않음
   - 단점: 런타임 중 동적 전환 불가 (재시작 필요)

2. **Map 주입 (`Map<String, IssuanceStrategy>`)** — 모든 전략을 빈으로 등록하고 Map에서 선택
   - 장점: 런타임 동적 전환 가능
   - 단점: 사용하지 않는 전략의 의존성(Redis, Kafka)도 모두 로드되어야 함. Step별 점진적 의존성 추가 방침과 충돌

3. **Spring Profile** — `@Profile("pessimistic")` 등으로 전략 분기
   - 장점: 환경별 설정과 통합 가능
   - 단점: Profile은 인프라 환경 구분 용도가 주목적. 비즈니스 전략 선택에 사용하면 의미가 모호해짐

## 결정 (Decision)

`@ConditionalOnProperty(name = "coupon.strategy", havingValue = "...")`를 사용한다.

- 런타임 동적 전환이 필요 없다 (전략 비교는 재시작 후 k6 테스트로 수행)
- Step별로 의존성을 점진적으로 추가하므로, 해당 Step의 전략 빈만 등록되는 것이 자연스럽다
- `application.yml`에서 `coupon.strategy: pessimistic` 한 줄로 전략이 명시되어 가독성이 좋다

## 구현 요약 (Implementation Summary)

```java
// 인터페이스
public interface IssuanceStrategy {
    Issuance issue(Long couponId, Long userId);
}

// 구현체 (전략마다 동일 패턴)
@Component
@ConditionalOnProperty(name = "coupon.strategy", havingValue = "pessimistic")
public class PessimisticLockStrategy implements IssuanceStrategy { ... }
```

```yaml
# application.yml
coupon:
  strategy: pessimistic  # pessimistic | redis-lock | redis-counter | redis-kafka
```

## 결과 (Consequences)

- 긍정: 전략 교체가 프로퍼티 한 줄 변경으로 완료, 불필요한 빈 미로드
- 부정: 전략 전환 시 애플리케이션 재시작 필요 (이 프로젝트에서는 문제 없음)

## 성과 및 지표 (Impact & Metrics)

- 전략 전환 소요: 프로퍼티 변경 + 재시작 (수 초)

## 관련 문서 (References)

- `설계문서.md` — 4개 전략 설계 상세
- `IssuanceStrategy.java` — 전략 인터페이스
- `PessimisticLockStrategy.java` — Step 1 구현체
