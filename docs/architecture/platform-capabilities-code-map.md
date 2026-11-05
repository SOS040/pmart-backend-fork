# MSA 공통 기능 코드 지도 — 게이트웨이·디스커버리·설정·Resilience·모니터링·트레이싱·메시징·검색

작성 2026-11-04. 기준 커밋 `9de9198`. 2026-11-05 갱신(기준 커밋 `dc70323` — Stream 소비 ADR-0035, 추적 A·C·B, Bus 그룹 고정, 검색 products-v5·센터 취급 색인, 경보 22개). 경로는 저장소 루트 기준이다.

**설정 파일 위치 규칙 (ADR-0029)**
- 각 서비스의 `src/main/resources/application.yml` 에는 부트스트랩 설정(이름, config-server 연결)만 있다.
- 실제 설정의 정본은 GitHub `code-and-pick/pmart-config` 이다. 이 문서는 PC 1 의 로컬 사본 `deploy/lan/.config-repo/` 로 적는다. 이 사본은 pmart-backend 저장소에 커밋되지 않는다.
- 파일 이름 규칙:
  - `application.yml`: 모든 앱 공통
  - `application-<profile>.yml`: 프로필(local·lan)별 공통
  - `<app>.yml`: 그 앱 공통
  - `<app>-<profile>.yml`: 그 앱의 환경별 값
- 통합 시험은 사본 `src/integrationTest/resources/pmart-config/` 을 쓴다. 정본과 맞추는 것은 `deploy/lan/config-copies.sh` 가 한다.
- 공통 기본값 상당수는 설정 파일이 아니라 라이브러리의 `EnvironmentPostProcessor` 가 코드로 넣는다. 우선순위가 가장 낮아서 설정 파일 값이 있으면 그 값이 이긴다.

| # | 기능 | 핵심 구현 | 상태 |
|---|---|---|---|
| 1 | API 게이트웨이 | Spring Cloud Gateway(WebFlux), JWT 검증, Redis 속도 제한 | LAN 운영 |
| 2 | 서비스 디스커버리·로드밸런싱 | Eureka + Spring Cloud LoadBalancer(RestClient 인터셉터) | LAN 운영(ADR-0032) |
| 3 | config 서버 | Spring Cloud Config(git) + 웹훅 → Bus(Kafka) 실행 중 갱신 | LAN 운영(ADR-0027·0029·0033) |
| 4 | Resilience | 시간 제한 + 오류 해석(`Downstream`), 멱등 키, 사가 보상, Kafka 재시도·DLT(Stream 바인더 DLQ). **Resilience4j 미사용** | 운영 |
| 5 | 모니터링 | Prometheus·Alertmanager·Grafana, Elastic(Filebeat→Logstash→ES→Kibana), PC 1 watch | LAN 운영(ADR-0030·0031·0034) |
| 6 | 트레이싱 | Micrometer Tracing(OTel 브리지) → Zipkin, W3C traceparent(HTTP + Kafka 헤더) | 운영. Kafka 구간도 이어짐(2026-11-04 LAN 확인, `a1d514c`) |
| 7 | 메시지 분산 처리 | Outbox → Debezium → Kafka → Spring Cloud Stream → Inbox, 재시도·DLT, 사가 | 운영(ADR-0003·0009·0030·0035) |
| 8 | 상품 검색 | 도메인 이벤트 → Logstash → Elasticsearch(nori) → search-service | LAN 운영(ADR-0031·0034) |

---

## 1. API 게이트웨이

**무엇**: 모든 외부 요청의 단일 입구다(`:8080`, pc4). 하는 일은 인증(JWT), 인가(경로 규칙), CORS, 속도 제한, 경로 라우팅, 요청 ID 부여다. 경로를 바꿔 쓰는(RewritePath) 규칙과 Java 라우트 정의는 **없다**. 라우트는 모두 yml 에 있다.

### 코드
| 경로 | 역할 |
|---|---|
| `platform-services/api-gateway/build.gradle.kts` | 플러그인 `pmart.spring-gateway`, `common-observability`, Redis(reactive, 속도 제한용) |
| `build-logic/src/main/kotlin/pmart.spring-gateway.gradle.kts` | gateway starter, config client, eureka-client, bus-kafka, actuator, oauth2-resource-server. 서블릿(MVC)·`common-web` 은 없다 |
| `.../src/main/java/com/pmart/gateway/ApiGatewayApplication.java` | 시작점 |
| `.../gateway/GatewayProperties.java` | `pmart.gateway.*`(upstream·publicPaths·guestPaths·rateLimit·cors) 레코드. CORS 에 `*` 를 넣으면 거부한다 |
| `.../gateway/security/GatewaySecurityConfig.java` | WebFlux Security. JWKS 로 JWT 를 검증한다. 경로 규칙: actuator 일부는 허용, `/internal/**`·`/api/*/internal/**` 는 **전부 거부**, public-paths 는 익명 허용, guest-paths 는 사용자 또는 GUEST 토큰, 나머지는 사용자 토큰만(SERVICE·GUEST 거부). CORS 는 `/api/**` 에만 건다 |
| `.../gateway/security/ProblemResponseWriter.java` | 401·403 을 RFC 9457 Problem(`code`·`retryable`·`requestId`·`traceId`)으로 응답한다 |
| `.../gateway/filter/RequestIdWebFilter.java` | `X-Request-Id` 를 유지하거나 새로 발급하고 응답에도 싣는다 |
| `.../gateway/filter/RateLimitGlobalFilter.java` | Redis 토큰 버킷. 키는 인증 시 `user:<sub>`, 아니면 `ip:<주소>`. 초과하면 429 `RATE_LIMITED`·`Retry-After: 1`. Redis 장애 시 통과시킨다(fail-open) |
| 시험 | `src/integrationTest/.../RateLimitIT`, `TraceIdIT`, `src/test/.../GatewayRoutingSecurityTest` |

### 설정
| 파일 | 키 |
|---|---|
| `platform-services/api-gateway/src/main/resources/application.yml` | 부트스트랩: `spring.config.import: optional:configserver:`, `fail-fast: true` |
| `.config-repo/api-gateway.yml` | `spring.cloud.gateway.server.webflux.routes`(약 40개, `Path=` + `uri: ${pmart.gateway.upstream.<svc>}`). 같은 접두 경로는 음수 `order` 로 먼저 맞춘다(예: `/api/v1/me/**`). `httpclient.connect-timeout: 1000`, `response-timeout: 10s`. `default-filters: RemoveRequestHeader=Cookie`. `pmart.gateway.public-paths`·`guest-paths`. `rate-limit`(익명 10/20, 인증 20/40, `/api/v1/webhooks/**` 제외). `spring.reactor.context-propagation: auto` |
| `.config-repo/api-gateway-lan.yml` | `upstream.<svc>: lb://<svc>`(Eureka), `spring.data.redis.url`, `cors.allowed-origins`(백오피스 PC) |
| `.config-repo/api-gateway-local.yml` | upstream 을 `http://localhost:<port>` 로, CORS 를 `localhost:5173·3000` 으로 |
| `.config-repo/application-lan.yml` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://auth-service:8501/api/v1/internal/jwks` |

관련 ADR:
- 0018: 운영자 로그인
- 0019·0025: 고객 Google 로그인
- 0022: 게스트 토큰
- 0026: 서비스 간 인증 없음 — 신뢰 경계는 사설망과 게이트웨이
- 0032: `lb://`
- 0033: 라우트 실행 중 갱신

### 쓰임
- **고객 앱**: `/api/v1/me/**`, 장바구니(게스트 허용), 주문·결제, `/api/v1/search/**`(검색은 익명)
- **라이더 앱**: `/api/v1/riders/me/**` → 배송 추적·실행 서비스
- **백오피스 웹**: `/api/v1/backoffice/views|commands/**` → backoffice-bff. 운영자 로그인은 `POST /api/v1/auth/operator-logins`(public)
- **결제사 웹훅**: `POST /api/v1/webhooks/toss` — public, 속도 제한 제외

### 흐름
```
클라이언트 ─▶ :8080
  1 RequestIdWebFilter          X-Request-Id, (Micrometer 가 trace 시작/이어받기)
  2 Spring Security             CORS → JWT 검증(JWKS) → 경로 인가  ─ 실패: 401/403 Problem
  3 라우트 매칭(Path, order)     ─ 없음: 404
  4 RateLimitGlobalFilter       Redis 토큰 버킷 ─ 초과: 429
  5 RemoveRequestHeader=Cookie
  6 lb://<svc> → LoadBalancer → Eureka 인스턴스 ─ 없음: 503
  7 Netty 프록시(연결 1s / 응답 10s), traceparent·X-Request-Id 전달 ─▶ 서비스
```
- **실행 중 갱신**: 라우트(`spring.cloud.gateway.*`)는 Bus 갱신(3번)으로 다시 읽힌다.
- **재시작이 필요한 것**: `GatewayProperties` 가 레코드라서 public-paths·CORS·속도 제한 값은 바꿔도 재시작해야 반영된다(ADR-0033).

---

## 2. 서비스 디스커버리 / 로드밸런싱

**무엇**: LAN 에서만 켠다. 서비스가 Eureka 에 `이름:PC IP:포트` 로 등록한다. 호출하는 쪽은 `http://pricing-service:8107` 같은 **이름 주소를 그대로 쓰고**, 인터셉터가 그 이름을 실제 인스턴스 주소로 바꾼다. local·prod·시험에서는 Eureka 를 끈다. 이때는 이름 주소가 그대로 DNS 로 간다(prod = Kubernetes Service).

### 코드
| 경로 | 역할 |
|---|---|
| `platform-services/eureka-server/.../EurekaServerApplication.java` | `@EnableEurekaServer` |
| `platform-services/eureka-server/.../SecurityConfig.java` | 등록·조회·대시보드는 Basic 인증. actuator 일부만 허용 |
| `build-logic/src/main/kotlin/pmart.spring-service.gradle.kts`(L29), `pmart.spring-gateway.gradle.kts`(L27) | 모든 앱에 `spring-cloud-starter-netflix-eureka-client`(LoadBalancer 포함) |
| `libraries/common-web/src/main/java/com/pmart/common/web/LoadBalancedRestClientAutoConfiguration.java` | `eureka.client.enabled=true` 일 때만 켜진다. Boot 의 `RestClient.Builder` 에 인터셉터를 단다. **`@LoadBalanced` 를 쓰지 않는다**(ADR-0032 에서 기각) |
| `libraries/common-web/.../LoadBalancedInterceptor.java` | URI 의 호스트를 serviceId 로 보고 `choose()` 해서 주소를 바꾼다. **인스턴스가 없으면 원래 URI 로 그대로 보낸다**(2026-11-03 수정 — 이 수정 전에는 Eureka 클라이언트가 자기 등록조차 못 하는 문제가 있었다) |
| `libraries/common-web/.../Downstream.java` | 서비스 간 호출의 오류를 해석한다(4번 Resilience) |
| `libraries/common-web/.../HttpClientDefaultsPostProcessor.java` | 기본 시간 제한: 연결 1s, 응답 5s |
| `deploy/lan/render.sh` | 앱 환경변수 `PMART_HOST_IP`·`PMART_EUREKA_PASSWORD`, `extra_hosts` |
| `deploy/lan/placement.conf` | eureka-server 는 pc4 하나 |

### 설정
| 파일 | 키 |
|---|---|
| `.config-repo/application.yml` | `eureka.client.enabled: false`(기본) |
| `.config-repo/application-lan.yml` | `eureka.client.enabled: true`. `service-url.defaultZone: http://pmart:${PMART_EUREKA_PASSWORD}@eureka-server:8761/eureka/`. `registry-fetch-interval-seconds: 10`. `healthcheck.enabled: true`(actuator 가 DOWN 이면 Eureka 에도 DOWN). `refresh.enable: false`(Bus 갱신 때 등록이 잠깐 빠지는 것을 막음). `instance.prefer-ip-address`, `ip-address: ${PMART_HOST_IP}`, `instance-id`, 갱신 10s / 만료 30s. 비밀번호가 나올 수 있는 Netflix 로거 2개는 OFF |
| `.config-repo/eureka-server.yml` | `server.port: 8761`, 자기 등록 안 함, self-preservation 끔, 제거 주기 10s |
| `.config-repo/<app>-lan.yml` | 다른 서비스 주소(예: `cart-service-lan.yml` 의 `pricing-base-url: http://pricing-service:8107`) |

### 흐름
```
[등록] 앱 시작(lan) ─▶ eureka-server(pc4:8761) 에 <app>:<PC IP>:<port> 등록 ─ 10s 마다 갱신, 30s 끊기면 제거
[조회] 호출 측이 10s 마다 레지스트리 사본 갱신
[호출] RestClient(baseUrl http://pricing-service:8107)
        └ LoadBalancedInterceptor: choose("pricing-service") → http://192.168.0.34:8107 로 바꿈
          (없으면 원래 주소 → compose extra_hosts)
        └ Downstream.call 이 오류를 해석
[게이트웨이] lb://<svc> 도 같은 레지스트리로 해석한다
```

### 쓰임 (호출 예)
- **cart-service** `services/cart-service/src/main/java/com/pmart/cart/infrastructure/CatalogClient.java`:
  - pricing `/api/v1/prices`
  - product `/api/v1/internal/skus`
  - mfc-inventory `/api/v1/internal/availability`
  - 장바구니 화면에서는 이 네 조회를 동시에 한다(BR-42)
- **그 밖의 호출자**:
  - order-orchestration `Participants`(사가 참여자 호출)
  - order-service `Upstreams`·`OrchestrationClient`
  - claim-service `Upstreams`
  - customer-service `ServiceabilityClient`
  - backoffice-bff `ApprovalClient`
  - search-service `CategoryTreeClient`(`AvailabilityClient` 는 2026-11-05 삭제 — 취급 여부는 색인 `carriedMfcIds`, 8번)
- **인터셉터를 거치지 않는 것**: 외부 API(Toss·Kakao)와 Elasticsearch 는 별도의 `RestClient.builder()` 를 쓴다.
- **한계**:
  - 서비스마다 인스턴스가 하나라 장애 조치는 없다.
  - 배포 직후 10~20초 정도는 503 이 날 수 있다.
  - Kafka·DB·Redis·Zipkin·ES 는 Eureka 를 거치지 않는다.

---

## 3. config 서버

**무엇**: 모든 앱이 부팅할 때 `GET /{app}/{profile}` 로 설정을 받는다. config-server 는 GitHub `pmart-config` 를 확인하고(`force-pull`, LAN 은 fetch 를 15초에 한 번 — `refresh-rate`, 2026-11-04), `{cipher}` 값을 복호화해서 내준다. push 하면 웹훅 → Bus 를 거쳐 해당 앱이 **재시작 없이** 다시 받는다(ADR-0033).

### 코드
| 경로 | 역할 |
|---|---|
| `platform-services/config-server/.../ConfigServerApplication.java` | `@EnableConfigServer` |
| `platform-services/config-server/.../SecurityConfig.java` | 설정 조회는 Basic 인증. `POST /monitor` 는 열어 두되 서명으로 지킨다(config-monitor 의 `WebhookValidatorFilter`, X-Hub-Signature-256). `/encrypt`·`/decrypt` 와 나머지 actuator 는 거부 |
| `platform-services/config-server/.../cipher/ConfigCipherTool.java` | `{cipher}` 값을 만드는 오프라인 도구(Gradle `configCipher`). 평문이 같으면 기존 암호문을 다시 쓴다 |
| `libraries/common-observability/.../ConfigBusDefaultsPostProcessor.java` | Bus 기본값(우선순위 최저): `spring.cloud.bus.enabled=false`(LAN 설정에서 켠다), function autodetect 끔, binder 헬스 끔, binder 직렬화 ByteArray |
| `libraries/common-messaging/.../MessagingDefaultsPostProcessor.java` | Kafka 클라이언트 기본값. Bus 의 binder 기본값보다 **먼저** 적용해야 업무 Kafka 가 String 직렬화를 유지한다(2026-11-03, 시험 29개가 깨진 뒤 고침) |
| `build-logic/.../pmart.spring-service.gradle.kts`(L26-31) | 모든 앱에 config client, eureka client, bus-kafka |
| `deploy/lan/lan.sh` | `config_sync`(사본을 원격 최신으로, 사람이 고친 것은 버리지 않음), `config_check`(미커밋·미push 가 있으면 배포 중단), `config-push`(commit·push, 키 이름만 출력), deploy 는 config-server 를 먼저 올림 |
| `deploy/lan/config-copies.sh` | 시험용 사본 `check`/`sync` |
| `deploy/lan/init-secrets.sh` | `.secrets/`(암호화 키·salt, Basic 비밀번호, Deploy key, 웹훅 비밀, smee 주소 등)를 만든다. 이미 있으면 덮어쓰지 않는다 |
| `deploy/lan/render.sh` | config-server 의 configtree 비밀 폴더(`/run/secrets/config-server`)를 만든다. `config-relay`(smee 클라이언트)를 config-server PC 에 자동으로 붙인다. config-server 에 `SPRING_CLOUD_CONFIG_SERVER_GIT_REFRESH_RATE=15`(전체 재배포 때 요청마다 fetch 가 밀려 시간 초과·재시작이 반복된 뒤, `7e37718`). 앱마다 `PMART_BUS_INSTANCE: <pc>`(Bus 소비 그룹 이름) |

### 설정
| 파일 | 키 |
|---|---|
| `platform-services/config-server/src/main/resources/application.yml` | git: `uri`(Deploy key SSH), `default-label: main`, `clone-on-start`, `force-pull: true`, `strict-host-key-checking`. `spring.config.import: optional:configtree:${PMART_CONFIG_SECRETS_DIR:/run/secrets/config-server}/`(비밀은 파일 이름 = 속성 이름). `server.encrypt.enabled: true`. monitor: GitHub 만 켬. `bus.refresh.enabled: false`, `bus.ack.enabled: false`(StreamBridge 동시 수정 예외 회피). 프로필 그룹 `local: native` |
| `.../config-server/src/main/resources/application-local.yml` | `native.search-locations: file:…/deploy/lan/.config-repo/`(로컬은 사본 폴더를 바로 읽음 — force-pull 이 미커밋 작업을 지운 사고 뒤에 바꿈) |
| 각 서비스 `src/main/resources/application.yml` | `spring.config.import: "optional:configserver:"`, `fail-fast: true`, `username: pmart`. LAN 은 compose 환경변수 `SPRING_CLOUD_CONFIG_URI=http://config-server:8888` |
| `.config-repo/application-lan.yml` | `spring.cloud.bus.enabled: true`, `spring.kafka.bootstrap-servers: kafka:9092`. Bus 소비 그룹 `springCloudBusInput.group: bus-${spring.application.name}-${PMART_BUS_INSTANCE:…}`, `start-offset: latest`(2026-11-04 — 전에는 재시작마다 빈 `anonymous.*` 그룹이 쌓였다. config-server 는 이 파일을 읽지 않아 anonymous 그대로) |
| `.config-repo/<app>-lan.yml` | DB 비밀번호 `'{cipher}…'` 등 |

### `@RefreshScope` 가 걸린 곳 (레코드를 클래스로 바꿈 — 레코드는 프록시를 만들 수 없다)
| 파일 | 실행 중 바뀌는 업무 값 |
|---|---|
| `services/cart-service/.../application/CartProperties.java` | `pmart.cart.retention`(장바구니 보관) |
| `services/customer-service/.../CustomerProperties.java` | 주소 개수 상한 |
| `services/delivery-capacity-service/.../CapacityProperties.java` | 배송 슬롯 용량 |
| `services/mfc-capacity-service/.../CapacityProperties.java` | 센터 시간당 처리량 |
| `services/claim-service/.../ClaimProperties.java` | 환불 재시도 간격 |
| `services/order-orchestration-service/.../OrchestrationProperties.java` | 멈춘 사가 판정 시간(`stuck-after`) |
| `services/auth-service/.../application/{CustomerProperties,GuestProperties,OperatorProperties}.java` | 토큰 수명, 로그인 실패 한도, 잠금 |

- 그 밖에 setter 형 `@ConfigurationProperties`, `logging.level`, 게이트웨이 라우트도 실행 중에 바뀐다.
- DB·Kafka·Redis 주소, `*.enabled`, 레코드 속성, `eureka.*` 는 **재시작해야** 반영된다.

### 흐름
```
[부팅] lan.sh deploy → config-server 먼저(health UP 대기) → 각 앱 GET /{app}/lan (Basic)
        → application*.yml + <app>*.yml 병합, {cipher} 복호화 → fail-fast(못 받으면 앱 종료 → compose 재시작)
[갱신] 개발자 push(lan.sh config-push) → GitHub 웹훅 → smee.io 채널 → config-relay(pc4)
        → POST config-server/monitor(서명 검사) → 바뀐 파일 이름으로 대상 결정(cart-service-lan.yml → cart-service, application*.yml → 전체)
        → RefreshRemoteApplicationEvent → Kafka springCloudBus → 대상 앱 ContextRefresher → 설정 다시 받기
```

---

## 4. Resilience

**무엇**: 회로 차단기 라이브러리 없이 아래를 조합해 장애를 다룬다.
- **명시적 시간 제한과 오류 해석**: "닿지 않았다"와 "결과를 모른다"를 구분한다.
- **멱등 키**: 같은 요청을 다시 보내도 결과가 한 번만 나게 한다.
- **원결과 조회**: 결과를 모르면 다시 조회해서 확인한다.
- **사가 보상**: 이번 시도에서 확보한 자원만 되돌린다.
- **Kafka 재시도와 DLT**

**Resilience4j 는 쓰지 않는다**: `gradle/libs.versions.toml` 에 버전(2.4.0)만 선언돼 있다. `@CircuitBreaker`·`@Retry`·`@Bulkhead` 는 코드에 없다. 속도 제한은 게이트웨이의 Redis 토큰 버킷뿐이다.

### 코드
| 경로 | 역할 |
|---|---|
| `libraries/common-web/.../Downstream.java` | `call(target, supplier)`/`run`. 연결 거부·연결 시간 초과·호스트 없음·503 → `DEPENDENCY_UNAVAILABLE`(재시도 가능). 응답 시간 초과·그 밖의 5xx → `UNKNOWN_RESULT`(재시도 불가, **보상 금지, 원결과 조회**). 4xx 는 그대로 |
| `libraries/common-web/.../HttpClientDefaultsPostProcessor.java` | `spring.http.clients.connect-timeout=1s`, `read-timeout=5s`(코드 기본값) |
| `libraries/common-web/.../ErrorCode.java` | `DEPENDENCY_UNAVAILABLE`·`UNKNOWN_RESULT`·`VERSION_CONFLICT`·`IDEMPOTENCY_CONFLICT` 등 |
| `libraries/common-web/.../RequestHeaders.java`, `RequestHeadersFilter.java` | 변경 요청 필수 헤더(`Idempotency-Key`·`X-Source-System`·`X-Expected-Version`). `checkExpectedVersion` |
| `libraries/common-web/.../OptimisticLockAdvice.java` | JPA `@Version` 충돌 → `VERSION_CONFLICT` |
| `libraries/common-messaging/.../RequestIdempotency.java` + `db/migration/common/V0_0_2__pmart_request_record.sql` | `request_record`. 같은 키·같은 본문이면 저장된 결과를 재생하고, 본문이 다르면 `IDEMPOTENCY_CONFLICT`. 업무 변경과 한 트랜잭션 |
| `libraries/common-messaging/.../StreamBindingDefaultsPostProcessor.java`, `PmartDlqDestinationResolver.java` | Spring Cloud Stream 바인딩 기본값(그룹·재시도 3회·DLQ·RECORD 커밋) → 바인더 DLQ `{topic}.DLT` + `dead_letter`(ADR-0035) |
| `libraries/common-messaging/.../EventConsumers.java`, `PmartEventConsumer.java`, `ConsumerBindingVerifier.java` | 소비 함수 공장(byte[] → 봉투 → InboxGuard), 설정 누락 시 기동 실패 |
| `libraries/common-messaging/.../DeadLetters.java`, `DeadLetterController.java`, `StreamBridgeRepublisher.java`, `V0_0_3__pmart_dead_letter.sql` | 미처리 메시지 기록·재처리(StreamBridge)·폐기. 계약은 `contracts/openapi/_internal-dead-letters.yaml` |
| `services/order-orchestration-service/.../application/CheckoutSagaService.java`, `CancellationSagaService.java`, `infrastructure/Participants.java` | 주문 사가: 단계별 멱등 키, UNKNOWN 재전송, 보상 |
| `services/payment-service/.../infrastructure/toss/TossClient.java`, `application/PaymentLedger.java`, `ReconciliationService.java`, `domain/PaymentRules.java` | Toss 시간 초과 → UNKNOWN → 주기 재조회(대사) |
| `platform-services/api-gateway/.../filter/RateLimitGlobalFilter.java` | 속도 제한(1번) |

### 설정
| 키 | 값·위치 |
|---|---|
| `spring.http.clients.connect-timeout` / `read-timeout` | 1s / 5s(코드 기본값, 서비스 설정이 이긴다) |
| `pmart.payment.toss.connect-timeout` / `read-timeout` | 3s / 15s(`TossProperties` 기본값, `.config-repo/payment-service*.yml`) |
| `pmart.payment.reconcile-interval` | PT15S. 재조회 규칙: 1분 간격 5회, 그 뒤 15분 간격(`PaymentRules`) |
| `pmart.orchestration.sweep-interval` / `stuck-after` | PT10S / (시험 사본 PT30S, `@RefreshScope`) |
| `pmart.messaging.consumer.max-retries` / `initial-backoff-ms` / `max-backoff-ms` | 3 / 1000(×2) / 10000(`MessagingProperties`). 스키마 위반·`IllegalArgumentException` 은 재시도하지 않는다 |
| 게이트웨이 `httpclient.connect-timeout` / `response-timeout` | 1000ms / 10s(`.config-repo/api-gateway.yml`) |

### 흐름 — 업무 예
**주문 결제 사가 (S1, `CheckoutSagaService`)**
```
POST 체크아웃 → 사가 저장 후 202
 → VALIDATE_QUOTE → RESERVE_INVENTORY → HOLD_CAPACITY → HOLD_SLOT(예약 주문만) → START_PAYMENT → AWAIT_PAYMENT_RESULT
   각 단계 Idempotency-Key = sagaId:step (몇 번 다시 보내도 한 번만 반영)
   Participants → Downstream.call
     DEPENDENCY_UNAVAILABLE / UNKNOWN_RESULT → 단계 UNKNOWN(실패 아님) → sweep(10s) 이 같은 키로 재전송
     4xx 거절 → COMPENSATING: 이번 시도에서 새로 확보한 자원만 역순 해제(원예약 조회로 확인한 뒤 RELEASED)
   결제 결과는 결제 시도를 직접 조회해 판단(결제 사건은 "빨리 확인하라"는 신호일 뿐)
     승인 → 확정 / 금액 불일치 → 대사 / 모름 → UNKNOWN_TRACKING(자원 유지) / 실패 → 해제
   견적 만료 → RE_QUOTE_REQUIRED
```

**Toss 결제 승인 시간 초과 (payment-service)**
```
TossClient.confirm(POST /v1/payments/confirm, Idempotency-Key)
 → 연결 오류·5xx·409 IDEMPOTENT_REQUEST_PROCESSING → Unknown
 → PaymentLedger: CONFIRM_UNKNOWN, PaymentResultUnknown 발행, 같은 주문의 새 결제 시도 차단
 → ReconciliationService(15s) toss.get(paymentKey)
     DONE → 승인(PaymentApproved) / ABORTED → 실패 / EXPIRED → 만료 / 금액·주문 불일치 → 보류(MISMATCH)
```

**Kafka 소비 실패** → 7번 흐름의 ⑦.

---

## 5. 모니터링

**무엇**:
- **지표**: Prometheus(pc4)가 모든 Spring 앱의 `/actuator/prometheus` 와 exporter 들을 15초마다 수집한다.
- **경보**: Alertmanager 가 받는다. **전송 채널은 없다**(화면에서만 본다).
- **화면**: Grafana.
- **로그**: 앱의 JSON 로그를 Filebeat → Logstash → Elasticsearch 로 모으고 Kibana 에서 본다.
- **PC 1 watch**: LAN 전체를 점검하고, 연속으로 실패하면 자동 재시작한다.

### 코드·구성
| 경로 | 역할 |
|---|---|
| `libraries/common-observability/.../ObservabilityDefaultsPostProcessor.java` | 지표·트레이싱·로그 기본값(우선순위 최저, 아래 설정) |
| `libraries/common-observability/src/main/resources/pmart-logback.xml` | local 이 아니면 JSON 한 줄 로그(LogstashEncoder: UTC 시각, `service`, MDC traceId·spanId·correlationId·sourceSystem). 비밀번호·토큰·전화·이메일·카드번호는 가린다. local 은 텍스트 |
| `libraries/common-messaging/.../DeadLetters.java` | 업무 외 지표: `pmart.messaging.dead.letters.open`(30s 갱신), `pmart.messaging.dead.letters{topic}` |
| `gradle/libs.versions.toml`(bundle `service-observability`) | micrometer-registry-prometheus, tracing-bridge-otel, zipkin exporter, logstash-logback-encoder |
| `deploy/lan/render.sh` | `prometheus.yml` 생성. job: prometheus, `host-disk`(모든 PC :9100), `elasticsearch`(:9114), 앱마다 하나. config-server 는 actuator 를 막아 빼 둠 |
| `deploy/lan/monitoring/alert-rules.yml` | 경보 규칙(아래) |
| `deploy/lan/monitoring/alertmanager.yml` | receiver `none` |
| `deploy/lan/monitoring/grafana/{provisioning/…, dashboards/pmart-overview.json}` | 데이터 소스와 "PMart 개요" 대시보드(C: 여유, 앱 UP, 미처리 메시지, HTTP 요청·5xx·지연, Kafka 소비 지연, JVM 힙, DB 커넥션) |
| `deploy/lan/monitoring/grafana/dashboards/pmart-search.json` | "PMart 상품 검색" 대시보드(2026-11-05, `dc70323`): 색인 파이프라인(소비 지연·Logstash 큐·마지막 반영·DLT·ES 거절), 검색 API, Elasticsearch, 이력 토픽 |
| `deploy/lan/elastic/filebeat.yml` | `log-shipper`(모든 PC): 컨테이너 로그 중 앱 JSON 만, 초당 500건, 메모리 큐 → `logstash:5044` |
| `deploy/lan/elastic/logstash/{logstash.yml, pipelines.yml, logs.conf}` | `logs` 파이프라인 → `pmart-logs-YYYY.MM.dd`(같은 Logstash 에서 검색 색인 파이프라인도 돈다) |
| `deploy/lan/elastic/es-init.sh`, `log-janitor.sh` | ILM 3일, 색인 틀, Kibana 데이터 뷰 / 5분마다 크기 상한 강제(한 색인 1GB, 전체 4GB) |
| `deploy/lan/kafka/topic-metrics.sh` | 이벤트 이력 토픽 지표(ADR-0034), 검색 색인 소비 그룹 지연 |
| `deploy/lan/elastic/search-index-metrics.py` | 검색 색인 파이프라인 지표(Logstash 영구 큐·DLQ·ES 거절·마지막 반영 시각, 2026-11-04) → pc2 textfile |
| `deploy/lan/watch.sh`, `watch-install.ps1`, `ipcheck.sh` | PC 1 예약 작업 "PMart LAN Watch"(5분) |
| `docs/operations/lan-monitoring-and-backup.md` | 운영 설정 모음(watch.sh·Prometheus·경보 22개 표·Zipkin·로그 보존·Kafka 백업·메모리 상한) |

### 설정 (`ObservabilityDefaultsPostProcessor`)
- `management.endpoints.web.exposure.include=health,info,prometheus,metrics`
- `management.prometheus.metrics.export.enabled=true`
- `management.metrics.tags.application=${spring.application.name}`
- `health.probes.enabled=true`
- `show-details=never`
- `logging.config=classpath:pmart-logback.xml`
- Prometheus 보존: 7일 또는 2GB(`render.sh`, ADR-0030 — 디스크 상한)

### 경보 (`alert-rules.yml`, 22개 — 2026-11-05)
| 묶음 | 경보 |
|---|---|
| 앱 | AppDown, Http5xxRateHigh |
| 메시징 | DeadLetterOpen, ConsumerLagHigh(500 초과 10분) |
| Elasticsearch | ElasticsearchUnreachable, ElasticsearchClusterRed, ElasticsearchHeapHigh |
| 검색 색인(2026-11-04) | SearchIndexDeadLetter, SearchIndexStalled, SearchIndexQueueBacklog, SearchIndexLogstashDown, SearchIndexRejectedByElasticsearch, SearchIndexMetricsStale |
| 이벤트 이력(ADR-0034) | KafkaEventRetentionNotUnlimited, KafkaEventHistoryTruncated, KafkaEventBurst, KafkaEventTopicGrowthFast, KafkaTopicMetricsStale, KafkaTopicMetricsMissing |
| 디스크 | HostDiskLow(15%), HostDiskCritical(10GB), HostDiskFillPredicted(7일 예측) |

### 흐름
```
[지표] 앱 /actuator/prometheus, host-disk(:9100, pc2 는 Kafka 토픽 textfile 포함), es-exporter(:9114)
        ─15s─▶ Prometheus(pc4:9090) ─▶ 경보 규칙 ─▶ Alertmanager(:9093, 전송 없음) / Grafana(:3000)
[로그] 앱 stdout JSON ─▶ docker json-file(20MB×3) ─▶ Filebeat(PC 마다) ─▶ Logstash(pc4:5044) ─▶ ES pmart-logs-* ─▶ Kibana(:5601)
[감시] PC 1 watch.sh(5분): 실제 프로토콜로 점검(Postgres SSLRequest, Kafka ApiVersions, Redis PING, Zipkin, ES health,
        Connect 커넥터 RUNNING, 앱 /actuator/health) → 2번 연속 실패 시 lan.sh restart(PC 별 10분 쿨다운),
        PC 전체 실패면 Docker Desktop 시작 작업 먼저, IP 변경이면 재시작 보류. 이벤트 이력 토픽 지표·검색 색인 파이프라인 지표 측정
```

### 쓰임
- 모든 Kafka 소비 서비스의 미처리 메시지 감시
- 상품·가격 이벤트 이력의 보존 감시(검색 재구축의 전제)
- 모든 PC 의 디스크 보호(10/25 pc3 디스크 고갈 사고 뒤)
- 상품 검색 ES 상태 감시
- 장애 조사 때 traceId 로 로그를 찾음(6번)
- LAN 자동 복구

---

## 6. 트레이싱

**무엇**: Micrometer Tracing(OpenTelemetry 브리지)이 W3C `traceparent` 로 HTTP 호출을 이어 붙이고, 스팬을 Zipkin(pc4:9411)으로 보낸다. 샘플링은 100% 다. 같은 traceId 가 JSON 로그에도 남아 Kibana 에서 한 요청을 모든 서비스에 걸쳐 따라갈 수 있다. 2026-11-04 부터(추적 작업 A·C·B, `a1d514c`, `docs/architecture/trace-correlation-plan.md`) Kafka 구간도 outbox `traceparent` → Debezium 헤더 → Stream 바인더 observation 으로 이어지고, 사가 실행기도 문맥을 넘기며, correlationId = sagaId(취소 사가 S3 는 claimId)다.

### 코드
| 경로 | 역할 |
|---|---|
| `gradle/libs.versions.toml`(bundle `service-observability`) | `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-zipkin`, Boot 4 tracing 자동구성 모듈 |
| 각 서비스 `build.gradle.kts` | `project(":libraries:common-observability")` + `libs.bundles.service.observability` |
| `libraries/common-observability/.../ObservabilityDefaultsPostProcessor.java` | 트레이싱 기본값(아래 설정) |
| `libraries/common-observability/.../CorrelationIdFilter.java` | 서블릿 앱 전용. `X-Correlation-Id` 를 받거나 새로 만들어 MDC 에 넣고 응답에도 싣는다(사가 단위 ID) |
| `libraries/common-observability/.../CorrelationScope.java` | correlationId 를 MDC + baggage 에 잠시 바꿔 넣고 이전 값을 복원한다(작업 C). 결제 사가 = sagaId, 취소 사가 = claimId |
| `libraries/common-observability/.../MdcContextPropagation.java` | MDC `correlationId`·`sourceSystem` 을 context-propagation 에 등록(자동 구성) — `ContextExecutorService` 가 함께 넘긴다(작업 A) |
| `libraries/common-security/.../ServiceCallHeadersCustomizer.java` | 서비스 간 RestClient: `X-Source-System`, `X-Correlation-Id`(MDC), 사용자 Bearer(`/internal/` 제외)를 붙인다. `traceparent` 는 Boot 의 관측 계측이 넣는다 |
| `platform-services/api-gateway/.../filter/RequestIdWebFilter.java`, `security/ProblemResponseWriter.java`, `security/GatewaySecurityConfig.java` | 요청 ID. 오류 응답의 traceId 는 관측 컨텍스트에서 읽는다. CORS 에서 `traceparent` 를 허용한다. 시험 `TraceIdIT`(발급·이어받기·전달) |
| `libraries/common-messaging/.../EventEnvelopeCodec.java`(create), `OutboxWriter.java` | 사건 봉투와 outbox 의 `trace_id`·`correlation_id` 를 MDC 에서 채운다. `OutboxWriter` 는 MDC traceId·spanId 로 W3C `traceparent` 도 저장한다(작업 B, 열은 공통 마이그레이션 `V0_0_4__pmart_outbox_traceparent.sql`) |
| `deploy/local/connectors/outbox-connector.template.json` | Debezium 이 `trace_id` → Kafka 헤더 `traceId`, `correlation_id` → `correlationId`, `traceparent` → `traceparent` 로 싣는다 |
| `deploy/lan/connectors-add-traceparent.sh` | 이미 등록된 커넥터의 placement 에 `traceparent` 헤더만 더해 PUT(check/apply). 2026-11-04 LAN apply — 29개 모두 RUNNING |
| `libraries/common-messaging/.../StreamBindingDefaultsPostProcessor.java` | `spring.cloud.stream.kafka.binder.enable-observation=true` — 소비 때 헤더 `traceparent` 를 부모 span 으로 잇는다 |
| `libraries/common-messaging/.../EventConsumers.java` | 처리 동안 MDC `correlationId` = 봉투 값, 끝나면 이전 값 복원 |
| `services/cart-service/.../application/CartView.java` | 가상 스레드 병렬 조회에 `ContextExecutorService.wrap(…, captureAll())` 을 쓴다 |
| `services/order-orchestration-service/.../OrchestrationConfig.java`, `claim-service/.../ClaimConfig.java`, `customer-service/.../ProfileImageService.java` | 가상 스레드 실행기를 `ContextExecutorService` 로 감싼다(작업 A, 2026-11-04 — 전에는 trace·MDC 가 끊겼다). ai-assistant `AssistantConfig` 도 같다 |
| `libraries/common-observability/src/main/resources/pmart-logback.xml` | 로그에 `traceId`·`spanId`·`correlationId` |

### 설정
| 키 | 값·위치 |
|---|---|
| `management.tracing.propagation.type` | W3C(코드 기본값) |
| `management.tracing.sampling.probability` | 1.0 |
| `management.tracing.baggage.remote-fields` / `correlation.fields` | `correlationId,X-Source-System` |
| `management.tracing.export.zipkin.enabled` | true |
| `spring.kafka.template.observation-enabled` / `listener.observation-enabled` | true |
| `spring.cloud.stream.kafka.binder.enable-observation` | true(`StreamBindingDefaultsPostProcessor`, 업무 사건 소비는 Stream 바인딩이라 이 값이 Kafka 구간을 잇는다) |
| `management.tracing.export.zipkin.endpoint` | `.config-repo/application-lan.yml`: `http://zipkin:9411/api/v2/spans` / `application-local.yml`: localhost. config-server 는 환경변수 `PMART_ZIPKIN_ENDPOINT` |
| `spring.reactor.context-propagation` | `auto`(게이트웨이, `.config-repo/api-gateway.yml`) |
| Zipkin 서버 | `deploy/local/docker-compose.yml`, `deploy/lan/render.sh`(`openzipkin/zipkin:3`, pc4:9411). 메모리 저장소 — LAN 은 힙 256m·`MEM_MAX_SPANS=50000`·`mem_limit: 512m`(2026-11-05, `9f3bfd8` — 기본 힙 160m 이 차서 OOM 재시작이 반복됐다). 재시작하면 추적이 사라진다 |

### 흐름
```
[HTTP — 이어짐]
 클라이언트(traceparent 있으면 이어받음) ─▶ 게이트웨이: trace 시작 ─▶ 서비스 A(스팬 복원, MDC traceId/spanId, CorrelationIdFilter)
   ─RestClient(traceparent + X-Source-System + X-Correlation-Id)─▶ 서비스 B ─▶ … 스팬은 Zipkin, 로그는 Kibana traceId:"…"
[Kafka — 이어짐(2026-11-04~)]
 서비스 A 트랜잭션: 봉투.traceId = MDC → outbox.trace_id, outbox.traceparent(지금 span) ─▶ Debezium: 헤더 traceId·correlationId·traceparent
   ─▶ 소비 측 Stream 바인더 observation 이 traceparent 를 부모로 소비 span 시작 → 같은 trace. EventConsumers 가 MDC correlationId = 봉투 값
```
(2026-11-04 전에는 Debezium 이 `traceId` 헤더만 싣고 소비 측에 복원 코드가 없어 Kafka 를 건너면 새 trace 가 됐다.)

### 업무 예
- **장바구니 조회**: 게이트웨이 → cart-service → (content·pricing·product·mfc-inventory 동시 조회)가 **하나의 trace** 로 보인다.
- **주문 체크아웃**(2026-11-04 LAN 결제 1건으로 확인): 사용자 요청이 두 번이라 **trace 2개**다.
  1. 결제 진입(55 span): 게이트웨이 → order checkouts → orchestration 사가 시작 → 사가 실행기에서 customer·재고 예약·정원 확보·결제 준비까지 같은 trace.
  2. 결제 승인 이후(47 span): 게이트웨이 → payment-confirmations → Kafka `order.payment.v1` → orchestration·claim 소비 → 확정 단계 → Kafka `order.order.v1` → customer·cart·claim 소비. Kafka 를 두 번 건너도 trace 하나.
  - 두 trace 는 로그의 correlationId(= sagaId)로 함께 찾는다. 취소 사가(S3)는 claimId.

### 알려진 빈틈
1. ~~Kafka 를 건너면 trace 가 끊긴다.~~ 2026-11-04 해결(작업 B — outbox `traceparent`, 커넥터 헤더, 바인더 observation). Kafka 헤더 `traceparent` 원문은 직접 보지 못했고, 소비 span 이 같은 trace 에 붙은 것으로 간접 확인했다.
2. ~~문맥을 넘기지 않는 실행기가 있다(`OrchestrationConfig`·`ClaimConfig`·`ProfileImageService`).~~ 2026-11-04 해결(작업 A — `ContextExecutorService`).
3. 게이트웨이는 WebFlux 라서 `CorrelationIdFilter`(서블릿)가 돌지 않는다. correlationId 는 첫 서블릿 서비스가 만들고, 사가가 시작되면 sagaId 로 바뀐다. 필터 값이 로그 MDC 에서 사라지던 문제는 2026-11-05 수정(추적 라이브러리의 baggage→MDC 연동 필드에서 correlationId 를 뺌 — `ObservabilityDefaultsPostProcessor`, 시험 `CorrelationIdTracingTest`, LAN 미배포. 자세히는 `trace-gateway-checkout-payment.md` 2절). 결제 승인 요청은 결제 시도를 찾은 직후부터 sagaId(`payment-service` `CheckoutService.correlate`, 2026-11-05, LAN 미배포).
4. `@Scheduled` 작업(사가 sweep 등)은 각자 새 trace 다. 사가 단계는 correlationId 로 따라간다.
5. Zipkin 은 메모리 저장소라 재시작하거나 span 상한(5만)을 넘으면 옛 추적이 사라진다.

---

## 7. 메시지 분산 처리 (Kafka)

**무엇**: 확정된 업무 사실만 사건으로 낸다. 업무 변경과 outbox 기록을 한 트랜잭션에 묶고, **Debezium 이 PostgreSQL WAL 에서 읽어** Kafka 로 낸다. 애플리케이션 릴레이는 없다(ADR-0009). 소비 측은 Inbox 로 멱등 처리한다. 실패하면 재시도 → DLT → `dead_letter` → 재처리 순서로 다룬다. 주문·취소처럼 여러 서비스에 걸친 거래는 오케스트레이션 사가가 조정하고, 확정된 사실의 전파는 이벤트(코레오그래피)로 한다(ADR-0003).

### 코드
| 경로 | 역할 |
|---|---|
| `libraries/common-messaging/.../EventPublisher.java` | 봉투를 만들어 `OutboxWriter` 로 넘긴다(`sourceSystem` = 앱 이름) |
| `libraries/common-messaging/.../OutboxWriter.java` | 활성 트랜잭션 밖이면 거부한다. 스키마 검사 → INSERT → (기본) 같은 트랜잭션에서 DELETE. 토픽 = `{domain}.{aggregate}.v{n}`(`Topics.of`) |
| `libraries/common-messaging/.../EventEnvelope.java`, `EventEnvelopeCodec.java` | 봉투(`contracts/events/_envelope.v1.json`: eventId·aggregateId·aggregateVersion·schemaVersion·occurredAt·correlationId 등) |
| `libraries/common-messaging/.../EventSchemaValidator.java` | `contracts/events/{EventType}.v{n}.json`(빌드 때 jar 에 복사)으로 ENFORCE 검사. 위반하면 업무 트랜잭션까지 롤백 |
| `libraries/common-messaging/.../InboxGuard.java` | `inbox_event(source_system, event_id)` INSERT + 처리기를 한 트랜잭션에서 실행. 이미 있으면 건너뜀 |
| `libraries/common-messaging/.../Topics.java` | 토픽 이름, `.DLT` |
| `libraries/common-messaging/.../MessagingDefaultsPostProcessor.java` | Kafka 기본값(아래) |
| `libraries/common-messaging/.../CommonMessagingAutoConfiguration.java`, `DeadLetters.java`, `DeadLetterController.java` | 재시도·DLT·`dead_letter`·재처리 API |
| `libraries/common-messaging/src/main/resources/db/migration/common/V0_0_1__pmart_outbox_inbox.sql` | `outbox_event`, `inbox_event`, `CREATE PUBLICATION pmart_outbox` |
| `.../db/migration/common/V0_0_4__pmart_outbox_traceparent.sql` | `outbox_event.traceparent`(2026-11-04, 6번 작업 B). 봉투 계약은 그대로, 헤더 전용 |
| `libraries/common-messaging/.../EventConsumers.java`, `PmartEventConsumer.java` | 소비 함수 공장(ADR-0035, 2026-11-04 `@KafkaListener` 에서 옮김). 서비스는 `consumers.of(처리기)` 로 `PmartEventConsumer` 빈을 만든다(예: `ClaimEventConsumers`) |
| `deploy/local/connectors/outbox-connector.template.json` | Debezium 커넥터 정의 |
| `deploy/local/connector-registrar.sh`, `register-connector.sh` | publication 이 있는 DB 를 찾아 커넥터를 자동 등록 / 수동 등록 |
| `deploy/lan/render.sh` | `connect`(`quay.io/debezium/connect:3.7`, pc3), `connector-registrar`(모든 PC 의 Postgres) |
| `deploy/lan/connectors-add-traceparent.sh` | 기존 커넥터에 `traceparent` 헤더 추가(check/apply) |
| `docs/architecture/events.md`, `contracts/events/`(180개) | 이벤트 카탈로그(발행자 → 구독자)와 스키마 |
| `docs/business/flows/order-checkout-saga.md`, `cancel-vs-handover.md` | 사가 흐름 |

### 설정
| 키 | 값·위치 |
|---|---|
| 커넥터 | `plugin.name=pgoutput`, `publication.name=pmart_outbox`, `slot.name=pmart_outbox_<svc>`, `snapshot.mode=no_data`, `table.include.list=public.outbox_event`. SMT `EventRouter`: `route.by.field=topic`, 키 = `aggregateid`, 헤더 = `eventType`·`aggregateType`·`traceId`·`correlationId`·`traceparent`(2026-11-04), `tombstones.on.delete=false`. LAN 29개 RUNNING |
| 생산자 기본값 | `acks=all`, `enable.idempotence=true`, String. 이 생산자는 DLT·재처리용이고, 업무 사건은 Debezium 이 낸다 |
| 소비자 기본값 | `group-id=${spring.application.name}`, `enable-auto-commit=false`, `auto-offset-reset=earliest`, `isolation.level=read_committed`, `ack-mode=RECORD` |
| `pmart.messaging.domain` | 필수(예: pricing `commerce`, payment `order`). 각 `.config-repo/<app>.yml` |
| `pmart.messaging.outbox.delete-after-insert` / `schema-validation` | true / ENFORCE |
| `pmart.messaging.consumer.*` | 재시도 3회, 1s·2s·4s(최대 10s) — Stream 바인딩 `max-attempts`·`back-off-*` 로 들어간다 |
| `spring.cloud.function.definition`, `spring.cloud.stream.bindings.<fn>-in-0.destination` | 서비스별 소비 함수·토픽(설정 저장소 `<서비스>.yml`, ADR-0035) |
| 토픽 보관 | `commerce.product.v1`·`commerce.pricing.v1`·`mfc.sku-handling.v1`(2026-11-05) 무제한, `search-index.DLT` 30일, 그 외 7일(ADR-0034, `deploy/lan/kafka/topics-init.sh`) |

### 흐름
```
① 서비스 트랜잭션: 업무 변경 + EventPublisher.publish → OutboxWriter(스키마 ENFORCE) → outbox_event INSERT·DELETE
② 커밋 → WAL 에 INSERT 가 남음
③ Debezium(서비스 DB 마다 커넥터·슬롯 하나): EventRouter → 토픽(topic 열), 키 = aggregateId(같은 집합은 같은 파티션 → 순서 보장),
   값 = 봉투 JSON, 헤더 eventType·aggregateType·traceId·correlationId·traceparent
④ 소비: Spring Cloud Stream 바인딩(PmartEventConsumer 빈, 그룹 = 서비스 이름, 바인더 observation 이 trace 를 이음) → byte[] → codec.decode
   → MDC correlationId = 봉투 값 → InboxGuard.process(봉투, 처리기)
   — inbox INSERT 와 업무 반영이 한 트랜잭션, 중복은 건너뜀
⑤ 정상 반환 → 오프셋 커밋(RECORD)
⑥ 예외 → 롤백 → 재시도 3회(1·2·4s)
⑦ 그래도 실패 → 바인더 DLQ: dead_letter(OPEN) + 지표 → {topic}.DLT(같은 파티션, 원 바이트) → 오프셋 커밋
   → 원인 수정 후 POST /api/v1/internal/dead-letters/{id}/replays(StreamBridge 로 원 토픽에 다시, 헤더 pmart-replay-of) 또는 …/discards(사유)
```

### 업무 예
| 흐름 | 발행 → 토픽 → 구독 |
|---|---|
| 상품 등록 승인 | approval-service `ApprovalDecided` → `platform.approval.v1` → product-service·product-content·center-master·delivery-serviceability·mfc-inbound 의 `approvalEvents` 소비 함수(`ApprovalEventConsumers`) |
| 상품 게시 | product-service `SkuIssued`·`ProductVersionPublished`·`ProductSaleStateChanged` → `commerce.product.v1` → pricing `SkuProjector`, storefront `DisplayProjector`, mfc/cdc-inventory, product-content, 검색 Logstash |
| 센터 취급(2026-11-05) | mfc-inventory `SkuHandlingChanged`(`SkuHandlingService`) → `mfc.sku-handling.v1` → 검색 Logstash(`sku-handlings` → 상품 `carriedMfcIds`) |
| 가격 발효 | pricing-service `PriceVersionEffective`(`PriceActivator`, 발효 시점) → `commerce.pricing.v1` → storefront, cart, 검색 Logstash |
| 결제 | payment-service `PaymentApproved`·`PaymentFailed`·`PaymentResultUnknown`·`PaymentMismatch` → `order.payment.v1` → order-orchestration `paymentEvents`(`PaymentEventConsumers`) → `CheckoutSagaService.onPaymentEvent` |
| 사가 상태 | order-orchestration `CheckoutSagaStateChanged`·`CompensationExecuted` → `order.saga.v1` |
| 취소 사가(S3) | `CancellationRequested`(`order.claim.v1`) → orchestration `claimEvents`(`ClaimEventConsumers`) → `CancellationSagaService.onClaimEvent` → STOP_WORK → RELEASE_RESOURCES → APPLY_ORDER_CANCEL(correlationId = claimId) |

- 사가 단계 자체는 **동기 명령**이다(REST + Idempotency-Key, `Participants`).
- 확정 사실은 **이벤트**로 퍼진다.

### 주의
- `docs/architecture/events.md` 는 v0.1(10/23) 문구가 남아 있다. 실제 스키마는 `contracts/events/` 가 정본이다.
- ADR-0009 의 "DLQ + case-service 상신"은 자동으로 구현되지 않았다. 지금은 `dead_letter` + DeadLetterOpen 경보 + 사람의 재처리다.

---

## 8. 상품 검색

**무엇**: 고객 앱의 상품 검색, 자동완성, 필터(가격 범위, 승인 속성 `filters` — 2026-11-04), 센터 취급 여부를 맡는다. 색인 데이터는 **도메인 이벤트만으로** 만든다. 업무 테이블 CDC 는 쓰지 않는다. Logstash 가 Kafka 를 읽어 Elasticsearch 에 쓰고, search-service 는 **조회만** 한다(ADR-0031). 색인을 다시 만드는 방법은 Kafka 전체 재생이다(ADR-0034).

### 코드
| 경로 | 역할 |
|---|---|
| `services/search-service/src/main/java/com/pmart/search/api/SearchController.java` | `GET /api/v1/search/products`(q·categoryId·mfcId·includeNotCarried·minPrice·maxPrice·filters·sort·size·cursor, 응답 `appliedFilters`), `/search/suggestions`, `/search/filters`(색인 집계 건수 + product-service 필터 패널 이름, 2026-11-04 — 전에는 빈 목록) |
| `.../search/api/InternalController.java` | `GET /api/v1/internal/index-status`(색인 신선도). 재색인 요청은 501 |
| `.../search/api/RecentSearchesController.java` | `/api/v1/me/recent-searches`(미구현) |
| `.../search/application/ProductSearch.java` | Query DSL `bool`: must(형태소 **또는** n-gram 조각 **또는** 공백 제거 조각 `name.compact` 모두 일치), should(형태소 > 앞부분 > 조각 가중, 구문 `match_phrase` slop 1 가산), filter(카테고리 범위·판매중지 제외·`basePrice` 범위·`filters`·`includeNotCarried=false` 면 `carriedMfcIds`). 한글 자음만 2자 이상이면 초성 `nameChosung`(앞부분 가산 `nameChosung.edge`). 자동완성. (띄어쓰기·초성·구문 2026-11-05) |
| `.../search/application/IndexStatus.java` | 시각 필드 최댓값으로 원천별 마지막 반영 시각과 지연을 계산 |
| `.../search/domain/SearchRules.java` | 카테고리 조상 범위, 판매상태 변환, 정렬, 커서, 검색어 정규화 |
| `.../search/infrastructure/SearchIndex.java` | ES REST(RestClient, 사용자 토큰 전파 없음). 별칭 `products`→`products-v5`(2026-11-05), `sku-prices`→`sku-prices-v1`, `sku-handlings`→`sku-handlings-v1`. 색인이 없으면 생성(`ensureIndices`) |
| `.../search/infrastructure/CategoryTreeClient.java` | product-service 공개 카테고리 트리·필터 패널 5분 사본 → 결과의 `categoryPath`, `/search/filters` 이름(실패 시 이전 사본) |
| ~~`.../search/infrastructure/AvailabilityClient.java`~~ | 2026-11-05 삭제. 취급 여부(`carried`)는 색인 `carriedMfcIds` 로 판정한다(mfc-inventory 호출 없음) |
| `services/search-service/src/main/resources/elasticsearch/{products-v5,sku-prices-v1,sku-handlings-v1}.json` | 색인 정의(nori, `name.edge`, `name.partial`·`name.compact` n-gram, `nameChosung`(.edge), 판매 상태 행 버전, `filters`, `carriedMfcIds`). 별칭은 정의에 넣지 않는다 |
| `services/search-service/src/main/logstash/search-index.conf` | **색인 파이프라인**(이 서비스 소유, 배포가 Logstash 에 복사) |
| `contracts/openapi/search-service.yaml` | REST 계약(openApiGenerate → `api.generated`) |
| `deploy/lan/elastic/es-init.sh` | LAN 에서 같은 정의 파일로 색인과 별칭을 만든다(없을 때만) |
| `deploy/lan/elastic/search-index.env` | Logstash 대상 색인·소비 그룹(`PMART_SEARCH_GROUP_ID=search-index-logstash-v5`)·DLT 토픽 |
| `deploy/lan/elastic/REINDEX.md`, `reindex.sh` | 재색인 절차와 실행 스크립트(check·start·wait·switch·finish, 2026-11-05) |
| `deploy/lan/elastic/logstash/pipelines.yml` | `search-index` 파이프라인: 작업자 1, 배치 1 |
| `deploy/lan/kafka/` | 이벤트 이력 무제한 보관·백업·복원(ADR-0034) |
| 시험 | `services/search-service/src/integrationTest/java/com/pmart/search/SearchFlowIT.java`(실제 Kafka·ES(nori)·Logstash) |

### 설정
| 파일 | 키 |
|---|---|
| `.config-repo/search-service.yml` | `server.port: 8105`, `pmart.messaging.domain: commerce`, 익명 허용 경로(`/api/v1/search/products|suggestions|filters`), DataSource 자동구성 제외(DB 없음) |
| `.config-repo/search-service-lan.yml` | `pmart.search.elasticsearch-url: http://elasticsearch:9200`, `product-base-url: http://product-service:8101`(`inventory-base-url` 은 2026-11-05 삭제) |
| `.config-repo/search-service-local.yml` | 같은 키의 localhost 값 |
| `.config-repo/api-gateway.yml` | 라우트 `search-service`(`Path=/api/v1/search/**`), `search-service-me`(`/api/v1/me/recent-searches/**`, order -100), public-paths 에 `/api/v1/search/products` |
| Logstash 환경 | `PMART_KAFKA_BOOTSTRAP`, `PMART_ES_URL`, `PMART_CONTENT_BASE_URL`, `search-index.env` 의 `PMART_SEARCH_*`(render.sh) |
| 감시 | `es-exporter`(:9114) + Elasticsearch 경보 3종(`docs/architecture/elasticsearch-metrics.md`), 색인 파이프라인 지표 + `SearchIndex*` 경보 6종(2026-11-04), Grafana "PMart 상품 검색" |

### 흐름
```
[색인 — Logstash 하나만 쓴다]
 product-service  ProductVersionPublished·ProductSaleStateChanged ┐
 product-content  ProductContentPublished                        ├─ commerce.product.v1 ┐
 pricing-service  PriceVersionEffective ─────────────────────────── commerce.pricing.v1 ┤
 mfc-inventory    SkuHandlingChanged(2026-11-05) ────────────────── mfc.sku-handling.v1 ┴─▶ Logstash search-index
   (소비 그룹 search-index-logstash-v5 — search-index.env, read_committed, 작업자 1·배치 1)
   ProductVersionPublished → 카테고리 조상 범위·대표 SKU·판매상태·filters, 가격·SKU 취급이 먼저 와 있으면 _mget 으로 붙임
   ProductContentPublished → product-content 공개 카드 API 로 이름·이미지 보강(게시본만). 2번 더 실패하면 search-index.DLT + 경보
   ProductSaleStateChanged → TEMP_OUT→TEMPORARILY_SOLD_OUT, DISCONTINUED→SALE_STOPPED(행 버전이 큰 것만)
   PriceVersionEffective   → sku-prices 문서 + 그 SKU 를 대표로 둔 상품(update_by_query)
   SkuHandlingChanged      → sku-handlings 문서(센터별 버전 비교) + 그 SKU 를 대표로 둔 상품의 carriedMfcIds
   모든 갱신은 Painless 스크립트가 버전·시각을 비교 → 재전달·늦게 온 옛 사건은 무시(멱등·순서 안전)
 ─▶ Elasticsearch(pc4) products-v5 / sku-prices-v1 / sku-handlings-v1

[조회]
 고객 앱 ─▶ 게이트웨이(/api/v1/search/**, 익명) ─▶ search-service
   ProductSearch(Query DSL) ─▶ ES ─▶ categoryPath(트리 5분 사본) + carried(mfcId 가 carriedMfcIds 에 있는지 — 색인만) ─▶ 응답
```

### 운영
- **신선도**: `GET /api/v1/internal/index-status` 가 원천별 마지막 반영 시각과 지연을 보여 준다.
- **재처리**: 소비 그룹 오프셋을 되돌린다.
- **재구축**: 새 소비 그룹으로 Kafka 를 처음부터 읽어 새 색인을 만든 뒤 별칭을 원자 교체한다 — 절차 `deploy/lan/elastic/REINDEX.md`, 실행 `deploy/lan/elastic/reindex.sh`. LAN 에서 v2 → v3(11-04) → v4 → v5(11-05) 로 실행했다. 매핑만 바뀐 경우는 Logstash 를 멈추고 ES `_reindex` 를 한 뒤 별칭을 옮길 수도 있다.
- **재구축 전 반드시 고칠 것 (ADR-0034)** — 둘 다 2026-11-04 해결(`0013eb7`):
  1. ~~판매상태 비교가 `effectiveAt` 시각 기준이다.~~ `ProductVersionPublished` 에 `rowVersion` 을 더해 Logstash·storefront 모두 행 버전이 큰 사건만 반영한다(행 버전 없는 옛 사건만 시각 비교).
  2. ~~콘텐츠 API 가 실패하면 그 상품이 이름 없이 남는다.~~ 2초 간격 2번 더 시도한 뒤 색인하지 않고 `search-index.DLT`(원래 토픽·파티션·오프셋) + 경보 `SearchIndexDeadLetter`.
- **아직 없음**: 인기·평점 정렬(관련도로 대체), 최근 검색어·재색인 API(501), 동의어.

---

## 함께 보면 좋은 문서

**ADR**
- 0003: 사가
- 0009: Outbox·Debezium
- 0018·0019·0022·0025·0026: 인증·게이트웨이
- 0027·0029·0033: 설정
- 0030: 관측·미처리 메시지
- 0031: Elastic
- 0032: Eureka
- 0034: 이벤트 이력 보관
- 0035: 업무 사건 소비를 Spring Cloud Stream 으로

**아키텍처 문서**
- `docs/architecture/service-map.md`: 서비스 49개의 책임
- `docs/architecture/events.md`: 이벤트 카탈로그
- `docs/architecture/trace-correlation-plan.md`: 추적 작업 A·C·B
- `docs/architecture/product-search.md`, `docs/architecture/elk-in-pmart.md`
- `docs/architecture/search-comparison.md`
- `docs/architecture/elasticsearch-metrics.md`
- `docs/architecture/debezium-usage-vs-eum.md`
- `docs/architecture/order-full-flow-analysis.md`

**LAN 운영**
- `deploy/lan/README.md`
- `deploy/lan/kafka/README.md`
- `docs/operations/lan-monitoring-and-backup.md`, `docs/operations/2026-11-04-lan-deploy-incidents.md`

## 이 정리에서 드러난 개선 과제
| 영역 | 내용 |
|---|---|
| 트레이싱 | ~~Kafka 구간 trace 연결, orchestration·claim·customer 실행기 문맥~~ — 2026-11-04 완료·LAN 확인(`a1d514c`). 남은 것: 게이트웨이 단계 correlationId, `@Scheduled` 작업의 trace, Zipkin 메모리 저장소(재시작 시 유실) |
| Resilience | 규칙(`.claude/rules/api-and-events.md`)은 Resilience4j 를 언급하지만 구현되지 않았다. 지금은 시간 제한 + 멱등 재전송 + 원결과 조회로 대신한다. 회로 차단이 필요한 호출(외부 PG 등)을 정할지 결정이 필요하다 |
| 게이트웨이 | 공개 경로·CORS·속도 제한(레코드)은 실행 중에 바뀌지 않는다(재시작 필요) |
| 모니터링 | 경보 전송 채널 없음, watch.sh 는 render 가 자동으로 붙인 구성요소(log-shipper·host-disk 등)를 점검하지 않음. ~~Grafana 에 ES·Kafka 이력 패널 없음~~ — 2026-11-05 "PMart 상품 검색" 대시보드로 추가 |
| 메시징 | `events.md` 갱신, ADR-0009 의 case-service 상신 미구현 |
| 검색 | ~~판매상태 rowVersion, 콘텐츠 보강 실패 처리~~ — 2026-11-04 완료(`0013eb7`). 남은 것: 인기·평점 정렬, 최근 검색어, 동의어 |
