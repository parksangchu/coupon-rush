# Phase 1 로드맵

## 인프라 구성 (구현 순서 1번)
- [x] Docker Compose 확장 (Redis, Kafka, Prometheus, Grafana)
- [x] build.gradle 의존성 추가 (MySQL, JPA, Actuator — Redis/Kafka는 Step별 추가)
- [x] application.yml 확장 (Actuator, Prometheus)
- [x] 테스트 설정 전환 (H2 → Testcontainers MySQL)
- [x] k6 시나리오 골격
- [x] Terraform 기본 골격 (Step 1용)
- [x] k6 EC2 → test 서버 전환 (k6 + Prometheus + Grafana 통합, t3.small)
- [x] 검증: docker compose up, gradlew build, actuator 확인

## 기본 도메인 구현 (구현 순서 2번)
- [x] Coupon 엔티티 보강 (issue(), remainingQuantity(), 생성자 검증)
- [x] Issuance 엔티티 (FK 미사용, 유니크 제약)
- [x] IssuanceStrategy 인터페이스
- [x] 발급 API (POST /api/v1/coupons/{couponId}/issue)
- [x] 상태 API (GET /api/v1/coupons/{couponId}/status)
- [x] 전략 선택 설정 (coupon.strategy 프로퍼티, @ConditionalOnProperty)
- [x] 패키지 재구성 (controller, dto, entity, exception, repository, service, strategy)

## Step 1: Pessimistic Lock
- [x] PessimisticLockStrategy 구현 (FOR UPDATE + dirty checking)
- [x] 통합 테스트 (Testcontainers, 동시성/중복/소진 검증)
- [x] k6 부하 테스트 (로컬) — 정합성 OK (100/100), p(99)=195ms, max VU 87
- [x] AWS 배포 + k6 부하 테스트
- [x] 결과 기록 + 대안 검토
- [x] 인프라 변경: t3(버스터블) → m6i/m6g(비버스터블) — CPU 크레딧 문제 해소
- [x] 서브 퀘스트: SingleUpdateStrategy 구현 + 테스트 공통화
- [x] 서브 퀘스트: Single UPDATE vs Pessimistic Lock AWS 부하 테스트 (1,000 RPS)
- [x] 서브 퀘스트: 결과 기록

## Step 2: Redis Distributed Lock
- [x] RedisLockStrategy 구현 (Redisson + TransactionTemplate)
- [x] Terraform: ElastiCache 추가
- [x] 통합 테스트
- [ ] k6 부하 테스트 (AWS)
- [ ] 결과 기록 + 대안 검토

## Step 3: Redis Atomic Counter
- [ ] RedisCounterStrategy 구현
- [ ] 통합 테스트
- [ ] k6 부하 테스트 (AWS)
- [ ] 결과 기록 + 대안 검토

## Step 4: Redis Counter + Kafka 비동기 저장
- [ ] RedisKafkaStrategy 구현
- [ ] Kafka Consumer 구현
- [ ] Terraform: EC2(Kafka) 추가
- [ ] 통합 테스트
- [ ] k6 부하 테스트 (AWS)
- [ ] 결과 기록 + 대안 검토

## 마무리
- [ ] docs/strategy-comparison.md 작성
- [ ] README.md 작성
