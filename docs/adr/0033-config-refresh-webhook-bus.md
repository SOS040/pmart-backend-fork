# ADR-0033 LAN 설정 실행 중 갱신 — GitHub 웹훅(smee.io 중계) → config-server /monitor → Spring Cloud Bus(Kafka)

- 상태: 승인 (2026-11-03, 사용자 요청 "push → GitHub 웹훅 → config-server /monitor → Kafka springCloudBus → 해당 앱들이 config-server 에 다시 요청 → 설정 갱신 이렇게 해줘", 중계 방식 "smee.io 중계로 해줘", 구현은 코드·시험까지·배포는 따로)
- 대체: ADR-0027 결정 6("부팅 때만 읽는다 — `@RefreshScope`·`/actuator/refresh`·Spring Cloud Bus 는 쓰지 않는다")
- 관련: ADR-0027·0029(config-server, 설정 저장소), ADR-0032(Eureka), `deploy/lan/README.md` '설정 실행 중 갱신'

## 배경
- LAN 앱 33개는 부팅할 때만 config-server 에서 설정을 받는다. 설정 저장소(`code-and-pick/pmart-config`)에 push 해도 앱을 재시작해야 반영된다. config-server 자신은 요청마다 GitHub 를 확인하므로(`force-pull`) 언제나 최신이지만, 앱이 다시 묻지 않는다.
- 사용자는 push 하면 해당 앱이 스스로 다시 받아 가는 Spring Cloud 의 구성(웹훅 + `/monitor` + Bus)을 원한다.
- config-server 는 사설망(pc4 `192.168.0.40:8888`)에 있어 GitHub 가 웹훅을 직접 보낼 수 없다. 저장소가 private 인 것과는 상관없다(읽기는 지금처럼 Deploy key).

## 결정
1. **흐름**: 저장소 push → GitHub push 웹훅(Payload URL = smee.io 채널) → LAN 의 `config-relay`(smee 클라이언트)가 받아 `POST http://config-server:8888/monitor` → config-server 가 바뀐 파일로 대상 앱을 정해 `RefreshRemoteApplicationEvent` 를 Bus(Kafka 토픽 `springCloudBus`)에 보낸다 → 대상 앱이 config-server 에서 설정을 다시 받는다(`ContextRefresher`).
2. **GitHub → LAN 은 smee.io 중계**: `config-relay`(pc4, `node` 이미지 + `smee-client`, 포트 없음)가 채널에 **먼저 연결**해 두고 받은 웹훅을 넘긴다. 들어오는 포트·공개 주소를 열지 않는다. 채널 주소(`.secrets/smee-url`)는 비밀로 둔다 — 아는 사람은 웹훅 내용(바뀐 파일 이름·커밋 메시지·작성자)을 볼 수 있다. 비밀값은 웹훅에 실리지 않는다(설정 값은 `{cipher}`, 웹훅에는 파일 이름만).
3. **서명 검증**: `spring-cloud-config-monitor` 의 `WebhookValidatorFilter` 가 Security 보다 먼저 `X-Hub-Signature-256`(HMAC-SHA256, 비밀 `.secrets/config-webhook-secret` → configtree `spring.cloud.config.server.monitor.github.webhook-secret`)을 검사한다. 비밀이 없거나 서명이 틀리거나 `push` 가 아니면 403. `/monitor` 는 Basic 인증 대신 이 서명으로 지킨다(`SecurityConfig`). GitHub 외 저장소 형식(GitLab 등)은 끈다.
   - `/monitor` 가 할 수 있는 일은 "이 앱들은 다시 받아 가라"는 알림뿐이다. 값은 언제나 config-server 가 GitHub 에서 읽는다.
4. **대상 앱 = 바뀐 파일 이름**(config-monitor 규칙): `cart-service-lan.yml`·`cart-service.yml` → `cart-service`, `application*.yml` → 모든 앱(`*`). 한 push 의 파일 상한 100(`max-paths`).
5. **Bus = `spring-cloud-starter-bus-kafka`**, 모든 앱(convention `pmart.spring-service`·`pmart.spring-gateway`)과 config-server. 기존 Kafka(pc2)를 쓴다(토픽은 자동 생성).
   - **기본 꺼짐**: `common-observability` 의 `ConfigBusDefaultsPostProcessor` 가 `spring.cloud.bus.enabled=false` 를 가장 낮은 우선순위로 넣고, 설정 저장소 `application-lan.yml` 이 켠다(Eureka 와 같은 방식). config-server 는 저장소를 읽지 않아 compose 환경변수로 켠다.
   - 같은 후처리기가 Stream 의 함수 자동 바인딩과 binder health 를 끈다(Bus 문제로 앱 health 가 DOWN → Eureka 에서 빠지지 않게).
   - **Bus 용 Kafka 클라이언트는 바인더 수준 값으로 고정**: 바인더는 `spring.kafka.producer/consumer.*`(common-messaging 의 String 직렬화·earliest)를 자기 클라이언트에 합치므로, 바인더 수준 `producer-properties`·`consumer-properties` 에 ByteArray 직렬화와 `auto.offset.reset=latest`(뜬 뒤의 알림만)를 둔다.
   - 반대로 바인더의 `KafkaBinderEnvironmentPostProcessor` 가 `spring.kafka.*` 직렬화 기본값을 ByteArray 로 넣는다 — `MessagingDefaultsPostProcessor` 를 그보다 먼저 돌려 앱의 KafkaTemplate·리스너는 String 그대로 둔다(2026-11-03 통합 시험에서 Kafka 흐름 29건 실패로 발견).
6. **config-server 는 보내기만**: 받은 갱신으로 자신을 갱신하지 않고(`spring.cloud.bus.refresh.enabled=false`), ack 를 보내지 않는다(`ack.enabled=false` — 자기 `*` 알림을 Kafka 에서 다시 받아 ack 를 보내는 동안 `/monitor` 스레드의 StreamBridge 가 채널을 닫으며 ConcurrentModificationException → 500, 시험에서 재현).
7. **다시 읽히는 것과 재시작이 필요한 것**:
   - 다시 읽힌다: setter 형 `@ConfigurationProperties`(게이트웨이 라우트 `spring.cloud.gateway.*` 포함), `logging.level.*`, `@RefreshScope` 빈.
   - `@RefreshScope` 설정(2026-11-03): 정책·운영값이 든 `pmart.*` 설정 8개 — `pmart.cart`(retention), `pmart.customer`(addressLimit), 배송 `pmart.capacity`(slotCapacity), MFC `pmart.capacity`(unitsPerHour), `pmart.claim`(refundRetryAfter), `pmart.orchestration`(stuckAfter), `pmart.auth.customer`·`guest`·`operator`(토큰 수명·로그인 실패 제한·잠금). record 는 final 이라 refresh 프록시를 못 만들어 같은 생성자·접근자의 클래스로 바꿨고, 쓰는 쪽이 호출마다 읽어 그대로 적용된다. 같은 클래스 안의 주소(*BaseUrl)·Google 검증 설정·첫 관리자는 시작할 때 한 번 쓰여 재시작 대상이다.
   - 갱신 뒤 값이 잘못되면(형식 오류) 시작 때가 아니라 그 설정을 처음 쓰는 요청에서 실패한다 — 바꾼 뒤 해당 기능을 한 번 확인한다.
   - 재시작: DB·Kafka·Redis 주소, 빈 생성 조건(`*.enabled`), record(생성자 바인딩) 형 설정, `eureka.*`(갱신 때 Eureka 클라이언트를 다시 만들지 않는다 — `eureka.client.refresh.enable=false`, 다시 만들면 등록 해제~재등록 사이 게이트웨이 503). DataSource(Hikari)는 Spring Cloud 기본값으로 다시 만들지 않는다.
   - 설정을 바꿀 때 어느 쪽인지 모르면 재시작한다(`lan.sh restart`).
8. **local·운영**: local 은 Bus 꺼짐(설정 저장소 `application-local.yml` 이 켜지 않는다) — 지금처럼 재시작. 운영(Kubernetes)은 범위 밖(ConfigMap).

## 대안
- **웹훅 없이 LAN 안에서 주기 확인**(`git ls-remote` → `/monitor`): 외부 서비스·노출이 전혀 없다. 사용자 결정은 실제 GitHub 웹훅.
- **Cloudflare Tunnel 등 터널**: 공개 HTTPS 주소로 웹훅을 바로 받는다. 고정 주소에 계정·도메인이 필요하다.
- **공유기 포트 포워딩**: config-server 전체가 인터넷에 열린다.
- **Bus 없이 `/actuator/refresh` 를 앱마다 호출**: 앱 목록·주소를 따로 알아야 한다. Bus 는 Kafka 에 한 번 보내면 된다.
- **Bus AMQP(RabbitMQ)**: 인프라가 하나 는다. Kafka 는 이미 있다.

## 결과
- 영향: 모든 Spring 앱 35개(convention 의존성 bus-kafka — Spring Cloud Stream·Spring Integration 이 클래스패스에 들어온다), `libraries/common-observability`(새 후처리기), `libraries/common-messaging`(후처리기 순서), config-server(`config-monitor`, `/monitor`, 보안), 설정 저장소 `application-lan.yml`, LAN 스크립트(`init-secrets.sh`·`render.sh`·`placement.conf` 주석).
- 웹훅 서명은 config-server 가 **smee 가 다시 직렬화한 본문**으로 검사한다. smee.io 서버는 JSON 을 파싱해 전달하고 클라이언트가 `JSON.stringify` 로 다시 만든다 — GitHub 원본 바이트와 다르면(이스케이프 차이) 403 이 된다. LAN 배포 때 실제 push 로 확인한다. 403 이면 재시작으로 반영되는 지금 방식이 그대로 남는다(앱은 영향 없음).
- GitHub 의 Recent Deliveries 는 언제나 200 이다(받는 곳이 smee.io). 서명 검사 결과는 `config-relay` 로그(`POST …/monitor - 200|403`)로만 보인다. `ping` 은 `push` 가 아니라 403 — 정상이다.
- smee.io 는 외부 무료 서비스다. 내려가면 실행 중 갱신만 멈추고, 재시작으로는 언제나 반영된다.
- `config-relay` 는 컨테이너가 뜰 때 npm 에서 `smee-client` 를 받는다(인터넷 필요, 고정 버전).
- 웹훅 등록·smee 채널 생성은 사용자가 한다(`deploy/lan/README.md`).
