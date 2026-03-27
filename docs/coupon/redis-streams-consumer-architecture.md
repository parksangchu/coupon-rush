# Redis Streams 컨슈머 아키텍처 설계

| 항목 | 내용 |
|------|------|
| 상태 | resolved |
| 최종 수정 | 2026-03-27 |

## 배경

Redis Streams 전략(`RedisStreamsConsumer`)의 코드 리뷰에서 구조적 문제가 드러났다.

- `@Configuration` 클래스에 컨테이너 설정과 비즈니스 로직이 혼재
- Consumer Group 미적용 (오프셋 추적, 메시지 재처리 불가)
- Kafka 컨슈머(`IssuanceConsumer`)와 네이밍/구조 불일치
- `@KafkaListener` 같은 선언적 어노테이션이 Spring Data Redis에 없음 ([Issue #2528](https://github.com/spring-projects/spring-data-redis/issues/2528), 2023~ on-hold)

이 프로젝트의 컨슈머 패턴을 trail 프로젝트(트랙 생성 → 프리뷰/ES sync 등 다수 Consumer Group 독립 소비)에도 그대로 가져갈 예정이라, 프로덕션 수준으로 설계해야 했다.

## 타임라인

### 2026-03-27: 컨슈머 아키텍처 설계 및 구현

**문제/질문**:

1. Spring Data Redis에 `@KafkaListener`가 없는 상황에서, 컨슈머가 자기 구독 정보를 선언하면서도 Config 수정 없이 확장 가능한 구조를 어떻게 만들 것인가?
2. ACK, 에러 핸들링 같은 메시지 lifecycle 관심사를 비즈니스 로직과 어떻게 분리할 것인가?

**검토한 선택지**:

| 선택지 | 평가 |
|--------|------|
| Config에서 리스너별 수동 등록 | 컨슈머 추가마다 Config 수정 필요 → 확장성 부족 |
| 컨슈머가 자기 컨테이너를 직접 관리 (`InitializingBean`) | 인프라 설정이 컨슈머마다 중복, 역할 경계 붕괴 |
| **인터페이스 기반 자동 등록 + wrapper 패턴** | 컨슈머는 구독 선언 + 비즈니스 로직만, Config가 자동 수집 및 lifecycle 관리 |

**결정과 이유**:

인터페이스 기반 자동 등록 + wrapper 패턴을 선택했다.

`RedisStreamSubscriber` 인터페이스:
```java
public interface RedisStreamSubscriber {
    String streamKey();    // 어떤 스트림을
    String groupName();    // 어떤 그룹으로 구독할지
    void handle(MapRecord<String, String, String> message);  // 비즈니스 로직
}
```

Config가 `List<RedisStreamSubscriber>`를 자동 수집해서 등록:
```java
for (var subscriber : subscribers) {
    initConsumerGroup(redisTemplate, subscriber.streamKey(), subscriber.groupName());
    container.receive(
        Consumer.from(subscriber.groupName(), consumerName),
        StreamOffset.create(subscriber.streamKey(), ReadOffset.lastConsumed()),
        message -> {
            try {
                subscriber.handle(message);  // 비즈니스 로직
                redisTemplate.opsForStream().acknowledge(
                    subscriber.streamKey(), subscriber.groupName(), message.getId());
            } catch (Exception e) {
                log.error("메시지 처리 실패: ...", e);  // ACK 안 함 → PEL 잔류
            }
        }
    );
}
```

핵심 설계 판단:

- **인터페이스가 `StreamListener`를 상속하지 않는다**: `StreamListener.onMessage()`에 ACK를 넣으면 비즈니스 로직과 인프라 관심사가 섞인다. 대신 `handle()` 메서드를 별도로 정의하고, Config의 wrapper 람다가 `handle()` 호출 → 성공 시 ACK → 실패 시 PEL 잔류를 처리한다.
- **수동 ACK**: `receiveAutoAck()` 대신 `receive()` + 명시적 `acknowledge()`. AutoAck는 메시지 전달 즉시 ACK하므로 처리 실패 시 메시지가 유실된다.
- **consumer-name 외부 설정**: 수평 확장 시 인스턴스별 고유 식별자를 환경변수(`REDIS_CONSUMER_NAME`)로 주입. UUID는 재시작마다 바뀌어 PEL이 고아가 되므로, 호스트명/pod 이름 같은 고정값을 사용한다.

**결과**:

최종 역할 분리:

| 계층 | 역할 | Redis 의존성 |
|------|------|-------------|
| `RedisStreamSubscriber` | 구독 선언 (`streamKey`, `groupName`) + 비즈니스 로직 (`handle`) | 없음 |
| `RedisStreamsConfig` | 컨테이너 생성, Consumer Group 초기화, wrapper(ACK/에러 핸들링) | 있음 |

컨슈머 추가 시 `RedisStreamSubscriber` 구현체만 만들면 되고, Config 수정 불필요.

### 2026-03-27: Redis Streams vs Kafka 트레이드오프 분석

**문제/질문**:

trail 프로젝트(트랙 생성 → 프리뷰/ES sync/알림 등 다수 Consumer Group)에서 Redis Streams를 쓸 것인가, Kafka로 갈 것인가?

**검토한 선택지**:

| 기준 | Redis Streams | Kafka |
|------|---------------|-------|
| 인프라 | Redis가 이미 있음, 추가 비용 없음 | 별도 클러스터 운영 필요 |
| 내구성 | 메모리 기반, AOF로 보완 | 디스크 기반 |
| 어노테이션 지원 | 없음 (수동 등록) | `@KafkaListener` |
| 수평 확장 | 단일 노드에 종속 | 파티션 기반 |
| 메시지 리플레이 | 메모리 제약 | offset reset으로 자유로움 |

**결정과 이유**:

trail에서는 Redis Streams + 아웃박스 패턴을 선택한다.

- 아웃박스가 "DB → 브로커" 원자성을 보장하므로, Kafka든 Redis Streams든 아웃박스는 필요
- 아웃박스가 재발행을 보장하므로, Redis의 메모리 기반 내구성 약점이 상쇄됨
- Redis 인프라가 이미 있어 추가 운영 비용 없음
- 아웃박스 릴레이의 `XADD`를 Kafka `produce`로 바꾸면 브로커 교체 가능 — Kafka 전환 시점은 실측 신호(처리량 한계, PEL 관리 부담, 메모리 압박)가 나올 때

AOF `always`(매 명령 fsync)는 처리량이 1/5~1/10로 떨어지므로 비실용적. `everysec`(기본값)의 최대 1초 유실 가능성은 아웃박스 재발행 + 컨슈머 멱등 처리로 커버한다.

## 현재 결론

- `RedisStreamSubscriber` 인터페이스 + Config wrapper 패턴으로 `@KafkaListener` 부재를 해결
- 비즈니스 로직(handle)과 메시지 lifecycle(ACK, 에러 핸들링)의 관심사 분리 확보
- Consumer Group + 수동 ACK + 외부 설정 consumer-name으로 프로덕션 수준 구성
- trail 프로젝트에서도 동일 패턴 재사용 예정