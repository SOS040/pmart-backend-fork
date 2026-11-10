# api-gateway

책임의 정본은 `docs/architecture/service-map.md` 의 `api-gateway` 절: 라우팅, JWT 검증(auth-service JWKS), traceId 시작(외부 요청의 첫 span), rate limit. **업무 로직 없음.**

## 스택
Spring Cloud Gateway Server WebFlux + Reactor Netty (convention plugin `pmart.spring-gateway`). Spring MVC·`common-web`·`common-security`(서블릿 전용)는 쓰지 않는다. 발급자 표 규칙만은 서블릿에 묶이지 않은 `common-jwt` 를 서비스와 함께 쓴다(ADR-0054). 오류 응답은 게이트웨이 안에서 RFC 9457 로 직접 쓴다(`security/ProblemResponseWriter`).

## 라우팅 표 (설정 저장소 `pmart-config` 의 `api-gateway.yml`, ADR-0029)
- `contracts/openapi/*.yaml` 의 `servers.url` + 첫 경로 세그먼트에서 도출. 계약에 리소스를 추가하면 여기도 갱신한다.
- `me`·`riders`·`deliveries` 는 여러 서비스가 공유 → 구체 경로를 음수 `order` 로 먼저 매칭. 새 공유 세그먼트가 생기면 같은 방식.
- 정본은 GitHub `code-and-pick/pmart-config`(로컬 사본 `deploy/lan/.config-repo/api-gateway.yml`). 고친 뒤 `deploy/lan/config-copies.sh sync` 로 `src/test`·`src/integrationTest` 의 `pmart-config/` 사본을 맞춘다.
- 라우트 `uri` 는 `${pmart.gateway.upstream.<service>}` 자리표시자. 라우트별 `filters:` 는 없다(공통: `default-filters: RemoveRequestHeader=Cookie`).
- `/api/v1/internal/**` 은 라우팅하지 않고 보안에서도 `denyAll`. 서비스 간 호출은 게이트웨이를 거치지 않는다(운영 Service DNS, LAN 은 Eureka — ADR-0032).
- 업스트림 주소는 `pmart.gateway.upstream.<service>`: 기본(`api-gateway.yml`)은 `http://<service>:<port>`(운영 Service DNS), `api-gateway-lan.yml` 은 `lb://<service>`(Eureka, 등록 없으면 503), `api-gateway-local.yml` 은 localhost 포트.

## 보안
- 공개 작업(`pmart.gateway.public-paths`)은 계약의 `security: []` 인 작업과 1:1. 임의 추가 금지.
- 그 외 `/api/**` 는 유효한 JWT 필수(401/403 Problem). 역할·센터·행위 시점 검증은 각 서비스 책임.
- **발급자 표**(`pmart.security.issuers`, ADR-0054, `security/IssuedToken`): 있으면 토큰 `iss` 로 발급자를 골라(표에 없으면 401) 그 키로 서명, 그 발급자 규칙(`common-jwt` `IssuerPolicy` — 만료·iss·aud·발급자 ↔ 사용자 종류 짝·주체 클레임)으로 확인한다. SERVICE·GUEST 제한은 발급자 규칙이 정한 종류로 본다. 비어 있으면 예전처럼 `jwk-set-uri` 하나 + `typ` 클레임. `jwk-set-uri` 를 비운 발급자는 Boot 기본 디코더를 쓴다. 서비스(common-security)도 같은 표·규칙으로 다시 검증한다.
- **경로별 사용자 종류 허용 목록**(ADR-0054 §6.1, `security/UserTypeAllowlistFilter`, 보안 체인 뒤 order 0): 인증된 `/api/**` 요청의 (메서드, 경로) → 허용 종류를 `src/main/resources/access/user-type-allowlist.txt` 에서 찾는다(가장 구체적인 패턴). 이 파일은 **생성 파일** — 정본은 계약의 작업별 `x-pmart-user-types`(없으면 UNLABELED, `security: []` 는 PUBLIC). 계약을 고치면 `PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'` 로 다시 만든다(시험이 계약과 다르면 실패). 모드 `pmart.gateway.user-type-allowlist.mode`: RECORD(기본, 경고 로그·지표만) / ENFORCE(403 `USER_TYPE_NOT_ALLOWED`, 목록에 없으면 거부) / OFF. 로그에는 경로 원문 대신 맞은 패턴(없으면 앞 세 마디 + `/**`).
- **주체 차단 목록**(ADR-0054 ⑤·결정 K, `security/SubjectBlockFilter`, 보안 체인 뒤 order -50): Redis `pmart:gw:subject-block:<iss>|<sub>` = 차단 시각(epoch 초). 토큰 `iat <= 그 값`(iat 없으면 항목만 있어도)이면 401 `SUBJECT_BLOCKED`. Redis 장애·`lookup-timeout`(200ms) 초과는 통과(결정 H) + 지표 `pmart_gateway_subject_block_total{result}`. 주체·토큰은 로그에 남기지 않는다.
  - 채우는 쪽은 사건 `SubjectBlocked`(토픽 `platform.subject-block.v1`) 뿐 — Stream 함수 `subjectBlocks`(`security/SubjectBlockEvents`, 그룹 `api-gateway`). 사람·서비스가 Redis 에 직접 쓰지 않는다. 게이트웨이는 DB 가 없어 **common-messaging 을 쓰지 않는다**: 그룹·재시도(3회)·DLT(`platform.subject-block.v1.DLT`)·역직렬화는 pmart-config `api-gateway.yml` 에 직접 적었고, inbox 대신 쓰기가 멱등이다(Lua — 늦은 차단 시각만 남김). 남길 기간 = 차단 시각 + `max-token-lifetime`(2시간) − 지금 → 토픽을 다시 읽어도 지난 차단은 안 생긴다. 발급자 access token 수명을 2시간 넘게 늘리면 이 값도 늘린다.
  - Kafka 는 Bus 와 같은 클러스터(`spring.kafka.bootstrap-servers`). Bus(lan)는 definition 뒤에 busConsumer 를 붙인다. 단위 시험은 `application-test.yml`, 통합 시험은 `api-gateway-it-env.yml` 에서 definition 을 비우고 `subject-blocklist.enabled=false` — `SubjectBlocklistIT`(Redis·Kafka 컨테이너)만 다시 켠다.
- 서비스 간 호출은 게이트웨이를 거치지 않고 인증하지 않는다(ADR-0026 — private subnet 이 신뢰 경계). 게이트웨이는 `/internal/**` 를 라우팅하지 않는다(denyAll).

## 처리율 제한 (`filter/RateLimitGlobalFilter`, `pmart.gateway.rate-limit`)
- Redis 토큰 버킷(`RedisRateLimiter`). 유효한 JWT → subject 단위, 그 외 → 클라이언트 IP 단위(`trusted-proxies` 만큼 `X-Forwarded-For` 신뢰). 초과 시 429 Problem `RATE_LIMITED`(retryable) + `Retry-After`.
- Redis 장애 시 허용(fail-open). 그래서 Redis health 는 게이트웨이 health 에서 제외.
- LAN·local 은 `trusted-proxies: 0`(앞에 Ingress 가 없어 클라이언트가 보낸 `X-Forwarded-For` 를 믿으면 우회된다). 운영 기본 1.
- 수치는 잠정값. 웹훅(`/api/v1/webhooks/**`)은 제외. Redis 는 `PMART_REDIS_URL`.

## 필터 순서 (층이 다르다)
1. WebFilter: `RequestIdWebFilter`(HIGHEST+10, `X-Request-Id`) → Spring Security `WebFilterChainProxy`(-100: CORS·JWT·인가, 401/403 Problem) → `SubjectBlockFilter`(-50, 401 `SUBJECT_BLOCKED`) → `UserTypeAllowlistFilter`(0)
2. 라우트 선택(없으면 404) → Gateway 필터: `RateLimitGlobalFilter`(HIGHEST+100) → `RemoveRequestHeader=Cookie` → `lb://` 해석 → `NettyRoutingFilter`
- Security 가 거부한 요청은 Gateway 필터(처리율 제한)까지 오지 않는다.

## 추적(traceId)
- Micrometer Tracing(OTel, W3C `traceparent`, 샘플링 1.0). 서버 관측이 요청 도착 때 traceId 를 시작하거나 이어 받고, 업스트림에 자식 span 으로 전파한다.
- 오류 응답의 traceId 는 `Tracer.currentSpan()` 이 아니라 exchange 의 서버 관측에서 읽는다(`ProblemResponseWriter` — WebFlux 의 ThreadLocal 은 비거나 다른 요청 값). 로그 MDC 는 `spring.reactor.context-propagation: auto`.
- 시험에서 `management.tracing.export.enabled=false` 로 끄면 Boot 가 전파기를 Noop 으로 둔다 — 추적 시험은 켜고 Zipkin 주소만 막는다(`TraceIdIT`).

## 실행
```bash
./gradlew :platform-services:api-gateway:check
./gradlew :platform-services:api-gateway:bootRun --args='--spring.profiles.active=local'
```
로컬 포트 8080.

## 상태
3단계 스캐폴딩(2026-10-23), 처리율 제한 적용(2026-10-23), Eureka `lb://`(LAN, 2026-11-03), 오류 응답 traceId 수정(2026-11-03). 통합 테스트 `RateLimitIT`(Redis 컨테이너)·`TraceIdIT`(실제 Netty)·`SubjectBlocklistIT`(Redis·Kafka). 주체 차단(2026-11-11, LAN 미배포).
