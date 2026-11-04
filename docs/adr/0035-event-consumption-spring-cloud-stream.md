# ADR-0035 업무 이벤트 소비·DLT·재처리는 Spring Cloud Stream(Kafka 바인더) — 발행은 outbox → Debezium 그대로

- 상태: 승인 (2026-11-04, 사용자 요청 "@KafkaListener 쓰지 말고 spring cloud stream 써줘", "운영 코드에서는 KafkaTemplate 을 없애고, DLT 는 바인더 DLQ 로, 재처리는 StreamBridge 로 — 적용해줘")
- 보완: ADR-0009(Outbox·Inbox — 소비 구현 방식만 바뀌고 Inbox 멱등은 그대로), ADR-0030 결정 3(재시도 → `{topic}.DLT` + `dead_letter` — 의미는 그대로, 수단만 바인더 DLQ·StreamBridge 로)
- 관련: ADR-0033(Spring Cloud Bus — 같은 바인더를 쓴다), `libraries/common-messaging`, `.claude/rules/api-and-events.md`

## 배경
- 업무 이벤트 소비는 13개 서비스의 Spring Kafka `@KafkaListener` 19개였다. 실패 처리는 common-messaging 의 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`(KafkaTemplate)가, 재처리는 `DeadLetters.replay` 가 KafkaTemplate 으로 했다.
- Spring Cloud Stream(Kafka 바인더)은 이미 모든 앱에 있다. Spring Cloud Bus(ADR-0033)가 그것으로 `springCloudBus` 를 주고받는다.
- 사용자가 소비를 Spring Cloud Stream 으로 하고, 운영 코드에서 KafkaTemplate 을 없애 DLT 와 재처리도 같은 방식으로 맞추기로 했다.

## 결정
1. **소비 = Spring Cloud Stream 함수 바인딩.** 서비스는 `EventConsumers.of(handler)` 로 `PmartEventConsumer`(`Consumer<Message<byte[]>>`) 빈을 만든다. 공장이 byte[] → 봉투 → `InboxGuard.process` 까지 한다(Inbox 멱등은 그대로).
2. **바인딩 정의는 설정 저장소**(ADR-0029). `<서비스>.yml` 에 `spring.cloud.function.definition`(빈 이름, `;` 구분)과 `spring.cloud.stream.bindings.<fn>-in-0.destination`(토픽, 여러 개는 `,`)만 쓴다.
3. **공통 의미는 common-messaging 이 바인딩마다 넣는다**(`StreamBindingDefaultsPostProcessor`, 가장 낮은 우선순위 — 설정에 같은 키가 있으면 그쪽이 이긴다):
   - `group = spring.application.name`(예전 `@KafkaListener` 그룹과 같은 이름 → 커밋된 위치를 이어받는다)
   - 재시도: 1 + `pmart.messaging.consumer.max-retries`(3), 지수 백오프 1초·배수 2·최대 10초. `IllegalArgumentException` 은 재시도하지 않는다.
   - Kafka: `enable-dlq`, `ack-mode RECORD`(한 건씩, 처리 뒤 커밋), `auto.offset.reset=earliest`, `isolation.level=read_committed`, ByteArray 역직렬화.
   - **바인딩마다** 넣는 이유: 바인더 수준 소비 설정은 Bus 용(익명 그룹·latest, ADR-0033)이다. `spring.cloud.stream.default.*`·`kafka.default.consumer.*` 를 쓰면 Bus 에도 걸려 갱신 알림이 한 인스턴스에만 가거나 지난 알림을 다시 받는다. Bus 함수(`busConsumer`)는 건드리지 않는다.
4. **DLT = 바인더 DLQ.** `DlqDestinationResolver` 빈(`PmartDlqDestinationResolver`) 하나가 목적지를 **원래 토픽 + `.DLT`** 로 정한다(바인딩에 토픽이 여럿이어도 각자). 정하기 전에 `DeadLetters.record` 로 `dead_letter` 에 OPEN 을 남긴다. 파티션은 원래 파티션이다. 지표·경보(`pmart_messaging_dead_letters_open`, `DeadLetterOpen`)는 그대로다.
5. **재처리 = StreamBridge.** `DeadLetters.replay` 가 `DeadLetterRepublisher`(운영 구현 `StreamBridgeRepublisher`)로 원래 토픽에 같은 키·본문·헤더 + `pmart-replay-of` 를 보낸다. `spring.cloud.stream.kafka.default.producer.sync=true` 로 결과를 기다려, 실패하면 OPEN 으로 되돌린다. 내부 API(`/api/v1/internal/dead-letters`)는 그대로다.
6. **설정 누락은 기동 실패.** `PmartEventConsumer` 빈이 definition 에 없거나 destination 이 없으면 `ConsumerBindingVerifier` 가 기동을 멈춘다. Stream 은 정의되지 않은 함수를 조용히 바인딩하지 않으므로, 그대로 두면 이벤트를 받지 않은 채 정상처럼 떠 있게 된다.
7. **발행은 그대로 outbox → Debezium**(ADR-0009). StreamBridge 로 업무 이벤트를 바로 보내면 DB 변경과 발행이 한 트랜잭션이 아니게 된다(이중 쓰기). StreamBridge 는 재처리에만 쓴다.
8. **운영 코드에서 KafkaTemplate·`@KafkaListener` 를 쓰지 않는다.** 통합 시험은 이벤트를 넣을 때 `KafkaTemplate<String,String>` 을 계속 쓴다(Boot `spring.kafka.*` String 직렬화는 그 용도로 남긴다). 검색 색인(Logstash)은 Java 가 아니므로 이 결정 밖이다.

## 대안
- **`@KafkaListener` 유지**: 동작에 문제는 없었다. 사용자가 Stream 으로 통일하기로 했다.
- **DLT 를 우리 `DefaultErrorHandler` 로 계속 + 바인더 소비만 Stream**: byte[] 레코드를 String 직렬화 KafkaTemplate 으로 보내려면 별도 템플릿이 필요하고, 운영 코드에 KafkaTemplate 이 남는다.
- **각 서비스가 `*.DLT` 를 다시 구독해 `dead_letter` 기록**: 순수 Stream 이지만 바인딩이 19개 더 생기고, 한 DLT 토픽에 여러 서비스의 실패가 섞여 그룹 헤더로 걸러야 한다. 목적지 리졸버에서 기록하는 쪽이 단순하다.
- **바인딩 키를 전부 설정 저장소에 직접 씀**: 함수당 10여 줄 × 19개가 13개 파일에 흩어지고, Bus 와 겹치는 기본값을 실수로 넣기 쉽다. 의미가 같은 값은 코드 한곳(후처리기)에서 넣는다.

## 결과
- 소비자 추가 절차: ① `PmartEventConsumer` 빈 ② 설정 저장소 `<서비스>.yml` 의 definition·destination ③ 시험 사본(`deploy/lan/config-copies.sh sync`).
- 바인딩 설정은 실행 중 갱신(Bus)으로 다시 바인딩되지 않는다. 바꾸면 재시작한다.
- 시험: `StreamConsumerDlqIT`(실제 Kafka·PostgreSQL — 4회 시도 → `{topic}.DLT`·원래 파티션·`dead_letter`, 재시도 불가 1회, 토픽 2개 바인딩, StreamBridge 재처리 → 다시 실패하면 `replay_of`), `StreamBindingDefaultsPostProcessorTest`, `EventConsumersTest`, `DeadLettersPostgresIT`.
- 시험 지원: Kafka 바인더는 `@ServiceConnection` 연결 정보를 쓰지 않고 `spring.kafka.bootstrap-servers` 로 붙는다(시험에서 `localhost:9092` 로 가려 했다). common-test `PostgresKafkaContainerSupport` 가 이 속성도 넣는다.
- 남은 확인: `ConsumerLagHigh`(`kafka_consumer_fetch_manager_records_lag_max`)가 바인더 소비자에서도 나오는지 LAN 에서 본다.
