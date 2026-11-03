# api-gateway

책임의 정본은 `docs/architecture/service-map.md` 의 `api-gateway` 절: 라우팅, JWT 검증(auth-service JWKS), traceId 시작(외부 요청의 첫 span), rate limit. **업무 로직 없음.**

## 스택
Spring Cloud Gateway Server WebFlux + Reactor Netty (convention plugin `pmart.spring-gateway`). Spring MVC·`common-web`·`common-security`(서블릿 전용)는 쓰지 않는다. 오류 응답은 게이트웨이 안에서 RFC 9457 로 직접 쓴다(`security/ProblemResponseWriter`).

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
- 서비스 간 호출은 게이트웨이를 거치지 않고 인증하지 않는다(ADR-0026 — private subnet 이 신뢰 경계). 게이트웨이는 `/internal/**` 를 라우팅하지 않는다(denyAll).

## 처리율 제한 (`filter/RateLimitGlobalFilter`, `pmart.gateway.rate-limit`)
- Redis 토큰 버킷(`RedisRateLimiter`). 유효한 JWT → subject 단위, 그 외 → 클라이언트 IP 단위(`trusted-proxies` 만큼 `X-Forwarded-For` 신뢰). 초과 시 429 Problem `RATE_LIMITED`(retryable) + `Retry-After`.
- Redis 장애 시 허용(fail-open). 그래서 Redis health 는 게이트웨이 health 에서 제외.
- LAN·local 은 `trusted-proxies: 0`(앞에 Ingress 가 없어 클라이언트가 보낸 `X-Forwarded-For` 를 믿으면 우회된다). 운영 기본 1.
- 수치는 잠정값. 웹훅(`/api/v1/webhooks/**`)은 제외. Redis 는 `PMART_REDIS_URL`.

## 필터 순서 (층이 다르다)
1. WebFilter: `RequestIdWebFilter`(HIGHEST+10, `X-Request-Id`) → Spring Security `WebFilterChainProxy`(-100: CORS·JWT·인가, 401/403 Problem)
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
3단계 스캐폴딩(2026-10-23), 처리율 제한 적용(2026-10-23), Eureka `lb://`(LAN, 2026-11-03), 오류 응답 traceId 수정(2026-11-03). 통합 테스트 `RateLimitIT`(Redis 컨테이너)·`TraceIdIT`(실제 Netty).
