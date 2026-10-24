# api-gateway

책임의 정본은 `docs/architecture/service-map.md` 의 `api-gateway` 절: 라우팅, JWT 검증(auth-service JWKS), traceId 발급, rate limit. **업무 로직 없음.**

## 스택
Spring Cloud Gateway Server WebFlux + Reactor Netty (convention plugin `pmart.spring-gateway`). Spring MVC·`common-web`·`common-security`(서블릿 전용)는 쓰지 않는다. 오류 응답은 게이트웨이 안에서 RFC 9457 로 직접 쓴다(`security/ProblemResponseWriter`).

## 라우팅 표 (`src/main/resources/application.yml`)
- `contracts/openapi/*.yaml` 의 `servers.url` + 첫 경로 세그먼트에서 도출. 계약에 리소스를 추가하면 여기도 갱신한다.
- `me`·`riders`·`deliveries` 는 여러 서비스가 공유 → 구체 경로를 음수 `order` 로 먼저 매칭. 새 공유 세그먼트가 생기면 같은 방식.
- `/api/v1/internal/**` 은 라우팅하지 않고 보안에서도 `denyAll`. 서비스 간 호출은 Service DNS 직접 호출.
- 업스트림 주소는 `pmart.gateway.upstream.<service>`; 환경변수 `PMART_GATEWAY_UPSTREAM_<NAME>` 으로 덮어쓴다. local 프로파일은 localhost 포트.

## 보안
- 공개 작업(`pmart.gateway.public-paths`)은 계약의 `security: []` 인 작업과 1:1. 임의 추가 금지.
- 그 외 `/api/**` 는 유효한 JWT 필수(401/403 Problem). 역할·센터·행위 시점 검증은 각 서비스 책임.
- 서비스 신원 토큰(`typ=SERVICE`, ADR-0011)은 외부 경로에서 403. 서비스 간 호출은 게이트웨이를 거치지 않는다.

## 처리율 제한 (`filter/RateLimitGlobalFilter`, `pmart.gateway.rate-limit`)
- Redis 토큰 버킷(`RedisRateLimiter`). 유효한 JWT → subject 단위, 그 외 → 클라이언트 IP 단위(`trusted-proxies` 만큼 `X-Forwarded-For` 신뢰). 초과 시 429 Problem `RATE_LIMITED`(retryable) + `Retry-After`.
- Redis 장애 시 허용(fail-open). 그래서 Redis health 는 게이트웨이 health 에서 제외.
- 수치는 잠정값. 웹훅(`/api/v1/webhooks/**`)은 제외. Redis 는 `PMART_REDIS_URL`.

## 실행
```bash
./gradlew :platform-services:api-gateway:check
./gradlew :platform-services:api-gateway:bootRun --args='--spring.profiles.active=local'
```
로컬 포트 8080.

## 상태
3단계 스캐폴딩(2026-10-23), 처리율 제한 적용(2026-10-23). 통합 테스트 `RateLimitIT` 는 Redis 컨테이너 사용.
