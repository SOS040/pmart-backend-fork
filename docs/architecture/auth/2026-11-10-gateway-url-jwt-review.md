# API Gateway 단일 구성·URL별 JWT 검증 글 확인 (2026-11-10)

원문: [`references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`](references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md). 상태: 처음 확인은 결정 전 기준. **2026-11-10 Keycloak 구성 결정(ADR-0054) 뒤 다시 본 내용은 §5**. 같은 날 Keycloak 추천 글([확인](2026-11-10-keycloak-recommendation-review.md))을 전제로 한 글이다.

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

## 5. Keycloak 구성 결정(ADR-0054) 뒤 다시 본 것
발급자가 3개(auth-service, Keycloak `pmart-staff`, `pmart-rider`)가 되므로 원문의 핵심 — **"신뢰하는 발급자의 유효한 토큰"만 보면 다른 종류 토큰이 통과한다** — 이 이제 실제 문제다.
1. **게이트웨이 1개 유지**: 원문 결론 그대로. 바꿀 것 없음.
2. **발급자를 고르는 기준은 URL 이 아니라 토큰의 `iss`**: 우리 URL 은 사용자 종류로 나뉘지 않는다(§1). 그래서 원문처럼 `/api/rider/**` 체인을 나누는 대신, 허용 발급자 3개 목록으로 `iss` 를 보고 그 발급자의 공개키로 검증한다(Spring Security 의 발급자별 인증 관리자 선택 — `JwtIssuerReactiveAuthenticationManagerResolver`, 게이트웨이는 WebFlux). 목록에 없는 `iss` 는 거부.
3. **발급자 ↔ 사용자 종류 짝 강제**: auth-service → CUSTOMER·GUEST·SERVICE, `pmart-staff` → OPERATOR, `pmart-rider` → RIDER. 사용자 종류(`TYPE_x`)는 **발급자에서 정한다**(토큰 안 `typ` 문자열이 다르면 거부). 원문 §2 의 "고객 토큰으로 라이더 API" 는 이걸로 막힌다.
4. **`aud` 검증 추가**: Keycloak 은 Realm 마다 PMart 용 클라이언트를 두고 대상(audience) 매퍼로 `aud` 를 넣어, 게이트웨이·서비스가 확인한다(원문 §3). auth-service 토큰에는 지금 `aud` 가 없다 — 넣을지는 구현 때.
5. **서비스도 같은 검증**: 지금 서비스들은 auth-service JWKS 하나로 검증한다. 같은 다중 발급자·짝 규칙을 `common-security` 에 넣어 전 서비스가 쓴다(원문 §5 "개별 MSA 도 검증" — 라이브러리 변경이라 전 서비스 영향). 업무 인가(배차 소유권, 라이더 승인, 센터 권한)는 지금처럼 서비스가 본다.
6. **경로별 사용자 종류 거르기(§3-3)**: 이제 더 쓸모 있다. 다만 경로 목록을 계약과 맞춰야 하므로, 1차는 3·5(발급자·종류 짝)로 막고, 경로 규칙은 계약에서 자동 생성하는 방법을 정한 뒤 더한다.
7. 원문 예시 코드(`hasRole`, 경로별 `JwtDecoder`)는 그대로 쓰지 않는다 — Keycloak 역할(`realm_access.roles`)·센터를 우리 형태(`TYPE_x`·역할·센터)로 바꾸는 변환과 직원 주체 ID 유지(ADR-0054 '정할 것')가 먼저다.
