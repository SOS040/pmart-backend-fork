# API Gateway 단일 구성·URL별 JWT 검증 글 확인 (2026-11-10)

원문: [`references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`](references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md). 상태: **확인만 — 결정 전**. 같은 날 Keycloak 추천 글([확인](2026-11-10-keycloak-recommendation-review.md))을 전제로 한 글이다.

## 1. 지금 게이트웨이(코드 확인)
- **게이트웨이는 이미 1개**(`platform-services/api-gateway`, Spring Cloud Gateway Server WebFlux) — 원문의 결론 "2개로 나눌 필요 없다"는 지금 구조와 같다.
- 검증(`GatewaySecurityConfig`): 발급자 하나(auth-service JWKS)로 서명·만료 확인 → `/internal/**`·`/api/*/internal/**` 는 외부에서 `denyAll` → `typ=SERVICE` 토큰은 외부 경로 거부(ADR-0011) → `typ=GUEST` 는 비회원 허용 경로에서만 → 나머지는 사용자 토큰이면 통과.
- **고객·라이더·운영자 구분은 각 서비스가 한다**: 공통 라이브러리 `common-security` 의 `PmartJwtAuthenticationConverter` 가 `typ` 클레임을 `TYPE_CUSTOMER / TYPE_RIDER / TYPE_OPERATOR / TYPE_SERVICE` 권한으로 바꾸고, 서비스가 API 마다 그 권한·역할·센터·소유권을 본다. 라이더 타입(`RIDER`)은 이미 규약에 있다.
- URL 은 `/api/customer/**`·`/api/rider/**` 처럼 사용자 종류로 나뉘지 않고 **서비스·자원 기준**(`/api/v1/...`)이다. 같은 서비스(예: delivery-execution)가 라이더·고객·운영자 API 를 함께 가진다.

## 2. 원문에서 맞는 점
- 라우팅·인증·인가는 다른 일이고, 유효한 토큰이라도 "그 API 를 써도 되는 사용자 종류인지"를 따로 봐야 한다.
- 게이트웨이가 확인했다고 서비스가 확인을 생략하면 안 된다 — 배차 소유권·라이더 승인 상태는 서비스(rider·dispatch)가 다시 본다. 지금 구조가 이미 그렇다.
- 게이트웨이 하나에서 경로별로 처리율 제한·회로 차단을 다르게 둘 수 있다.

## 3. 맞지 않거나 확인이 필요한 점
1. **문제의 원인이 발급자 여러 개다**: 원문의 경로별 `iss`·`aud` 검증, 여러 `JwtDecoder` 는 Keycloak 추천(발급자 3개)을 따를 때 필요한 것이다. 지금처럼 auth-service 하나가 발급하면 "고객 토큰으로 라이더 API" 는 `typ` 클레임(서명 확인 뒤 읽음)으로 이미 막을 수 있다 — 발급자를 나누지 않아도 된다.
2. **`/api/customer/**`·`/api/rider/**` 경로 구조는 지금과 다르다**: 바꾸려면 50개 서비스 계약(`contracts/openapi`)·게이트웨이 경로·클라이언트(pmart-clients) 호출을 모두 바꿔야 한다. 한 서비스가 여러 사용자 종류를 받는 지금 구조에서는 경로 하나로 사용자 종류를 정할 수 없다.
3. **게이트웨이에서 사용자 종류를 한 번 더 거르는 것**은 해 볼 만하다(방어 심화): 지금 게이트웨이는 `typ` 를 SERVICE·GUEST 만 본다. 서비스가 이미 막지만, 예컨대 백오피스 전용 경로에 `TYPE_OPERATOR` 만 통과시키는 규칙을 게이트웨이 설정으로 더할 수 있다. 다만 서비스 계약마다 경로 목록을 맞춰 관리해야 해서 빠뜨리면 정상 요청이 막힌다 — 하려면 계약에서 자동으로 만들 방법부터 정한다.
4. 원문 예시 코드의 `hasRole("CUSTOMER")` 는 Keycloak/`role` 클레임 전제다. 우리 변환기는 `TYPE_x` 권한을 만든다 — 그대로 옮겨 쓰면 안 된다.
5. `aud`(대상) 클레임은 지금 토큰에 쓰지 않는다. 발급자가 하나인 동안은 필요성이 낮다.

## 4. 결론
- 게이트웨이 1개: **지금 그대로**(바꿀 것 없음).
- 경로별 발급자·대상 검증: Keycloak 도입을 정할 때만 필요 — Keycloak 결정(같은 날 확인 문서 §5)에 딸린 문제.
- 할 수 있는 개선(선택, 결정 필요): 게이트웨이에서 `typ` 로 사용자 종류를 한 번 더 거르기(§3-3).
