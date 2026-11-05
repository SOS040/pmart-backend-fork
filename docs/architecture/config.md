# 설정(config) — 어디에 두고 어떻게 받고 바꾸는가

- 기준: 2026-11-05. 경로는 저장소 루트 기준이다.
- 요약판은 [`platform-capabilities-code-map.md`](platform-capabilities-code-map.md) §3. 이 문서는 그것을 펼쳐 코드·파일 위치와 함께 적는다.
- 관련 결정:
  - ADR-0027: config-server, GitHub, `{cipher}`
  - ADR-0029: 설정 저장소가 정본, 서비스 yml 은 부트스트랩만
  - ADR-0033: 웹훅 → Bus 실행 중 갱신
- 비밀 규칙(CLAUDE.md): 비밀정보를 Git·`application.yml`·이미지에 넣지 않는다.

## 0. 한눈에

```
[정본]  GitHub code-and-pick/pmart-config  (PC1 사본: deploy/lan/.config-repo — pmart-backend 에 커밋하지 않음, .gitignore:39)
          application.yml · application-<프로필>.yml · <앱>.yml · <앱>-<프로필>.yml   (108개 파일)
            │ SSH Deploy key(읽기 전용), 15초마다 fetch
            ▼
[서버]  config-server (pc4 :8888)  — {cipher} 복호화, Basic 인증
            │ 앱 부팅 때 GET /{앱}/{프로필}
            ▼
[앱]    각 서비스 application.yml = 부트스트랩(이름·config-server 연결)만
        + 라이브러리 EnvironmentPostProcessor 기본값(우선순위 최저)

[갱신]  config-push → GitHub 웹훅 → smee.io → config-relay(pc4) → POST /monitor(서명)
        → Kafka springCloudBus → 대상 앱이 설정을 다시 받음(재시작 없이)
[시험]  config-server 없이 — 각 서비스 src/integrationTest/resources/pmart-config/ 사본(config-copies.sh 로 정본과 맞춤)
```

## 1. 설정 값의 위치와 우선순위

| 순위(높음 → 낮음) | 어디 | 예 |
|---|---|---|
| 1 | 환경변수·명령행(compose 가 넣음) | `SPRING_PROFILES_ACTIVE`, `SPRING_CLOUD_CONFIG_URI`, `PMART_BUS_INSTANCE` |
| 2 | config-server 가 준 값(설정 저장소) | `<앱>-<프로필>.yml` > `<앱>.yml` > `application-<프로필>.yml` > `application.yml` |
| 3 | 서비스 `src/main/resources/application.yml` | 부트스트랩만 |
| 4 | 라이브러리 기본값(`EnvironmentPostProcessor`, `addLast`) | 시간 제한, 추적, Kafka, 소비 바인딩 |

- 프로필:
  - `local`: 개발자 PC
  - `lan`: LAN 공유 개발 환경
  - `prod`: AWS, 아직 없음
  - 설정 저장소 `application.yml:1-2` 주석 참고
- 공통 설정 예(`deploy/lan/.config-repo/application.yml`):
  - Problem Details 켬
  - JPA `open-in-view: false`, `ddl-auto: validate`(스키마는 Flyway 만)
  - Eureka 기본 꺼짐
- LAN 공통(`application-lan.yml`):
  - Kafka `kafka:9092`
  - Bus 켬, Bus 소비 그룹
  - JWKS 주소
  - Hikari `maximum-pool-size: 5`
  - Zipkin 전송처
  - Eureka 켬

## 2. 서비스 쪽 — 부트스트랩 `application.yml`

예: `services/order-service/src/main/resources/application.yml`(모든 서비스가 같은 모양)

```yaml
spring:
  application:
    name: order-service
  config:
    import: "optional:configserver:"   # 주소 spring.cloud.config.uri (기본 localhost:8888, LAN 은 환경변수)
  cloud:
    config:
      fail-fast: true                  # 설정을 못 받으면 기동하지 않는다
      username: pmart
      password: pmart-local            # 로컬 config-server 기본값. LAN 은 SPRING_CLOUD_CONFIG_PASSWORD
```

- 모든 앱에 config client·eureka client·bus-kafka 의존성이 붙는다: `build-logic/src/main/kotlin/pmart.spring-service.gradle.kts:26-31`
- `@ConfigurationProperties` 처리기: 같은 파일 `:34`
- LAN compose 환경변수(`deploy/lan/render.sh:572-574`):
  - `SPRING_CLOUD_CONFIG_ENABLED=true`
  - `SPRING_CLOUD_CONFIG_URI=http://config-server:8888`
  - `SPRING_CLOUD_CONFIG_FAIL_FAST=true`
- 업무 설정은 서비스마다 `@ConfigurationProperties` 클래스로 받는다(28개). 예: `OrchestrationProperties`, `TossProperties`, `CartProperties`.

## 3. 라이브러리 기본값 — 코드로 넣는 설정

설정 파일에 없어도 모든 앱에 들어가는 값이다. 우선순위가 가장 낮아서 설정 저장소 값이 있으면 그쪽이 이긴다.

| 클래스 | 등록 | 주요 값 |
|---|---|---|
| `libraries/common-web/.../HttpClientDefaultsPostProcessor.java:24-25` | `common-web/.../META-INF/spring.factories` | `spring.http.clients.connect-timeout=1s`, `read-timeout=5s` |
| `libraries/common-observability/.../ObservabilityDefaultsPostProcessor.java` | `common-observability/.../spring.factories` | 로그 JSON(`pmart-logback.xml`), 추적 W3C·샘플링 1.0·baggage(`correlationId`, `X-Source-System`)·Zipkin 켬, Prometheus, actuator 노출 `health,info,prometheus,metrics`, 헬스 프로브, Kafka observation |
| `libraries/common-observability/.../ConfigBusDefaultsPostProcessor.java:46-54` | 같음 | Bus 기본 꺼짐(`spring.cloud.bus.enabled=false`, LAN 설정이 켬), function autodetect 끔, binder 헬스 끔, Bus binder 직렬화 ByteArray·`latest` |
| `libraries/common-messaging/.../MessagingDefaultsPostProcessor.java:34-54` | `common-messaging/.../spring.factories` | 프로듀서 `acks=all`·멱등, 소비자 그룹 = 앱 이름·자동 커밋 끔·`earliest`·`read_committed`·`ack-mode RECORD` |
| `libraries/common-messaging/.../StreamBindingDefaultsPostProcessor.java:73-84` | 같음 | 소비 바인딩마다 그룹·재시도(1+3)·백오프·`enable-dlq`·`ack-mode RECORD` |

- **순서 주의**: `MessagingDefaultsPostProcessor` 는 Bus binder 기본값보다 먼저 적용돼야 한다. 그래야 업무 Kafka 가 String 직렬화를 유지한다(2026-11-03, 시험 29개가 깨진 뒤 고침).
- **점검 과제**: common-web 의 `spring.factories` 만 키 이름(`org.springframework.boot.EnvironmentPostProcessor`)과 형식이 다른 두 파일과 다르다. 값이 `=\n…` 한 줄로 들어 있다. Spring 이 값을 trim 하므로 로딩은 될 것으로 보이지만, 등록을 확인하는 시험은 없다(`resilience.md` 1-1·9장).

## 4. config-server

| 파일 | 내용 |
|---|---|
| `platform-services/config-server/src/main/java/com/pmart/config/ConfigServerApplication.java` | `@EnableConfigServer` |
| `platform-services/config-server/src/main/java/com/pmart/config/SecurityConfig.java:29-36` | 설정 조회는 Basic 인증. `POST /monitor` 는 열어 두되 웹훅 서명으로 지킨다(config-monitor `WebhookValidatorFilter`, `X-Hub-Signature-256`). `/encrypt`·`/decrypt` 는 `denyAll` — 인증 하나로 모든 비밀을 복호화할 수 있게 되기 때문 |
| `platform-services/config-server/src/main/resources/application.yml` | 아래 표 |
| `platform-services/config-server/src/main/resources/application-local.yml` | `native.search-locations: file:…/deploy/lan/.config-repo/` — 로컬은 사본 폴더를 바로 읽는다(`force-pull` 이 미커밋 작업을 지운 사고 뒤에 바꿈) |
| `platform-services/config-server/src/main/java/com/pmart/config/cipher/ConfigCipherTool.java:27-50` | `{cipher}` 값을 만드는 오프라인 도구(Gradle `configCipher`). 평문이 같으면 기존 암호문을 다시 쓴다(불필요한 diff 방지) |

`application.yml` 주요 키

| 줄 | 키 | 값·이유 |
|---|---|---|
| 11-12 | `profiles.group.local: native` | 개발자 PC 는 파일 저장소 |
| 14 | `spring.config.import` | `optional:configtree:/run/secrets/config-server/` — 서버 자신의 비밀(암호화 키, Basic 비밀번호, Deploy key, 웹훅 비밀)은 파일 이름 = 속성 이름 |
| 19-25 | `bus.refresh.enabled: false`, `bus.ack.enabled: false` | 서버는 갱신 이벤트를 보내기만 한다(StreamBridge 동시 수정 예외 회피) |
| 27 | `cloud.config.enabled: false` | 서버 자신은 config client 가 아니다 |
| 29-41 | `monitor` | GitHub 만 켬, `max-paths: 100`(한 push 의 파일 수 상한 — 기본 20 이면 뒤 파일의 앱이 빠진다) |
| 43-44 | `encrypt.enabled: true` | `{cipher}` 를 서버가 복호화해서 내준다 |
| 45-53 | `git` | `uri` GitHub SSH, `default-label: main`, `clone-on-start: true`(닿지 못하면 기동 실패), `force-pull: true`, `timeout: 10`, `strict-host-key-checking: true` |
| 59-61 | `eureka.client.enabled: false` | 서버는 Eureka 보다 먼저 뜬다 |

- LAN 배치(`deploy/lan/render.sh:558-567`): configtree 비밀 폴더를 `./config-server-secrets:/run/secrets/config-server:ro` 로 마운트한다.
- `SPRING_CLOUD_CONFIG_SERVER_GIT_REFRESH_RATE=15`(`:565`): 15초 안의 요청은 다시 fetch 하지 않는다. 전체 재배포 때 요청마다 fetch 하다 시간 초과·재시작이 반복된 뒤 넣었다.

## 5. 비밀 — `{cipher}` 와 `.secrets/`

- 설정 저장소에는 비밀을 **암호문**으로만 둔다.
  - 형식: `password: '{cipher}…'`
  - 29개 파일에 37개: `password` 29(DB 등), `secret-key` 3, `access-key` 2, `rest-api-key`·`private-key`·`password-hash` 각 1
  - 키 이름만 세었고, 값은 보지 않았다.
- 암호화 키와 서버 비밀은 PC1 `deploy/lan/.secrets/` 에만 있다(Git 제외). `deploy/lan/init-secrets.sh` 가 없을 때만 만든다.

| 파일 | 용도 | 위치 |
|---|---|---|
| `config-encrypt-key`, `config-encrypt-salt` | `{cipher}` 키(바꾸면 render 가 저장소 값을 모두 다시 암호화) | `init-secrets.sh:8,39-40` |
| `config-password` | 서비스 → config-server Basic 비밀번호(사용자 `pmart`) | `:10` |
| `config-deploy-key(.pub)` | config-server 가 GitHub 를 읽는 SSH 키(ed25519, Deploy key 읽기 전용) | `:11,43` |
| `config-webhook-secret` | GitHub 웹훅 서명 비밀 | `:12,46` |
| `smee-url` | 웹훅 중계 채널 주소(아는 사람은 웹훅 내용을 볼 수 있어 비밀) | `:13,47-48` |

- 로컬 프로필은 `${PMART_…:}` 환경변수 자리표시자를 쓰고, 개발용 고정 값(예: `pmart-local`)만 둔다.
- 시크릿 입력은 사용자가 Docker Desktop GUI·별도 터미널에서 한다. 채팅의 `!` 명령으로 넣지 않는다.

## 6. 배포할 때 — `lan.sh`

`deploy/lan/lan.sh`

| 함수·명령 | 줄 | 동작 |
|---|---|---|
| `config_sync` | 55 | 사본을 원격 최신으로 맞춘다. 사람이 고친 내용(커밋·미커밋)은 버리지 않는다 |
| `config_check` | 70-76 | 배포 전: 사본에 미커밋·미push 가 있으면 멈춘다(config-server 는 GitHub 를 읽으므로 로컬 수정은 반영되지 않는다) |
| `config-push` | 146 | 사본을 commit·push(키 이름만 출력, 값 출력 안 함) |
| deploy | 97-107 | `config_sync` → render(기존 암호문 재사용) → `config_check` → **config-server 먼저** 올리고 health UP 대기(`:80-87`) → 나머지 앱 |

- 앱은 fail-fast 라 config-server 없이는 뜨지 않는다. 그래서 순서가 중요하다.

## 7. 실행 중 갱신 — 웹훅 → Bus (ADR-0033)

```
개발자: lan.sh config-push → GitHub pmart-config push
  → GitHub 웹훅(서명 X-Hub-Signature-256) → smee.io 채널
  → config-relay 컨테이너(pc4, smee 클라이언트) → POST config-server/monitor
  → 서명 검사 → 바뀐 파일 이름으로 대상 결정
       cart-service-lan.yml → cart-service / application*.yml → 전체
  → RefreshRemoteApplicationEvent → Kafka springCloudBus
  → 대상 앱의 Bus 소비자 → ContextRefresher → config-server 에서 다시 받기
```

| 구성 | 위치 |
|---|---|
| config-relay 자동 배치(config-server PC) | `deploy/lan/render.sh:52-54`, `:511-515`, smee 주소 없으면 render 중단 `:84` |
| Bus 켜기·소비 그룹 | 설정 `application-lan.yml` `spring.cloud.bus.enabled: true`, `springCloudBusInput.group: bus-${spring.application.name}-${PMART_BUS_INSTANCE}`, `start-offset: latest` |
| Bus 기본값 | `ConfigBusDefaultsPostProcessor.java:46-54` |
| 그룹 이름 고정(2026-11-04) | 예전에는 재시작마다 빈 `anonymous.*` 그룹이 쌓였다. 지금은 `bus-<앱>-<PC>` 34개. config-server 는 이 파일을 읽지 않아 anonymous 그대로 |

**재시작 없이 바뀌는 것**

| 종류 | 예 |
|---|---|
| `@RefreshScope` 클래스 9개(레코드는 프록시가 안 돼 클래스로 바꿈) | `services/auth-service/.../application/{CustomerProperties,GuestProperties,OperatorProperties}.java`(토큰 수명·로그인 실패 한도), `cart-service/.../CartProperties.java`(보관 기간), `claim-service/.../ClaimProperties.java`(환불 재시도), `customer-service/.../CustomerProperties.java`(주소 개수), `delivery-capacity-service/.../CapacityProperties.java`(슬롯 용량), `mfc-capacity-service/.../CapacityProperties.java`(시간당 처리량), `order-orchestration-service/.../OrchestrationProperties.java:15`(`stuck-after`) |
| setter 형 `@ConfigurationProperties` | 다시 바인딩됨 |
| `logging.level`, 게이트웨이 라우트 | 바로 반영 |

**재시작해야 바뀌는 것**
- DB·Kafka·Redis 주소, `*.enabled`, 레코드 속성, `eureka.*`
- 소비 바인딩(함수 ↔ 토픽): 예 `order-orchestration-service.yml:12-13` 주석 "바꾸면 재시작한다"

## 8. 시험 — config-server 없이

- 통합 시험은 config-server 를 띄우지 않는다. 각 서비스의 사본을 classpath 로 읽는다.
  - 예: `services/order-service/src/integrationTest/resources/config/application.yml:7-8`
    ```
    spring.config.import: "classpath:pmart-config/application.yml,classpath:pmart-config/order-service.yml,classpath:pmart-config/order-service-it-env.yml"
    ```
  - 사본 위치: `services/<앱>/src/integrationTest/resources/pmart-config/{application.yml, <앱>.yml}`. api-gateway 는 `src/test/resources` 에도 있다.
  - `<앱>-it-env.yml`: 시험 전용 자리표시자(DB·Kafka 는 Testcontainers 가 넣음). 비교·복사 대상이 아니다.
- 정본과 맞추기: `deploy/lan/config-copies.sh`
  - `check`: 다르면 목록을 보이고 1 로 끝난다
  - `sync`: 정본을 사본으로 복사
  - 대상 폴더 순회 `:14-16`
- 규칙(CLAUDE.md): 설정을 바꾸면 설정 저장소와 각 서비스 시험 사본을 같이 고친다.
- 로컬 실행: `./gradlew :platform-services:config-server:bootRun --args='--spring.profiles.active=local'` 를 먼저 띄운 뒤 서비스 `bootRun`(local). 로컬 config-server 는 `.config-repo` 폴더를 직접 읽으므로 커밋하지 않은 수정도 바로 보인다.

## 9. 알려진 주의점

| 항목 | 내용 |
|---|---|
| 사본 커밋 위치 | `.config-repo` 는 pmart-backend 가 아니라 pmart-config 저장소다. 백엔드 커밋에 설정 변경이 들어가지 않으므로 `config-push` 를 따로 해야 한다 |
| 미push 설정으로 배포 | `config_check` 가 막는다(6장) |
| `force-pull` | LAN config-server 의 clone 은 원격 기준으로 덮인다. 서버 안에서 직접 고치지 않는다 |
| common-web `spring.factories` 형식 | 3장 점검 과제 |
| 운영(prod) 프로필 | 아직 없다. Kubernetes 로 가면 Eureka 대신 Service DNS, 비밀은 별도 저장소로 다시 정한다(ADR-0027·0032) |
