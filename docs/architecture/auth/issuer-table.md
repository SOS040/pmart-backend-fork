# 발급자 표(`pmart.security.issuers`)

- 작성: 2026-11-11(사용자 요청 정리). 근거: ADR-0054(발급자 셋, 발급자 ↔ 사용자 종류 짝), ADR-0055 결정 6(`aud=pmart-api`).
- 함께 볼 문서: `issuer-type-pairing-and-access-lists.md`(처음 설계·허용/차단 목록), `aud-and-typ.md`(`aud`·`typ` 뜻), `keycloak-structure.md`(직원 토큰), `login-structure-and-flow.md`(전체 로그인).

## 1. 무엇인가

**PMart 가 믿는 토큰 발급자 목록과, 발급자마다의 검증·해석 규칙**이다. 한 줄이 "이 `iss` 로 온 토큰은 이 키로 서명을 확인하고, 이 규칙으로 읽는다"를 뜻한다.

- 게이트웨이와 모든 서비스가 **같은 표**를 읽는다(공통 설정).
- 표에 없는 `iss` 의 토큰은 **401**.
- 발급자를 더하거나 빼는 일은 코드 수정 없이 **줄을 넣고 빼는 것**이다(Keycloak·rider-auth-service 도 이렇게 붙였다).

## 2. 어디에 있나

| 무엇 | 위치 |
|---|---|
| 설정(정본) | pmart-config `application-<프로필>.yml` 의 `pmart.security.issuers` — LAN `application-lan.yml`, 로컬 `application-local.yml` (prod 는 아직 없음) |
| 기본 JWKS | 같은 파일 `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` — `jwk-set-uri` 를 비운 줄(auth-service)이 쓴다 |
| 한 줄의 형태 | `libraries/common-jwt` `IssuerSettings` |
| 한 줄의 규칙(검증·해석) | `libraries/common-jwt` `IssuerPolicy` |
| 서비스(서블릿) 연결 | `libraries/common-security` `TrustedIssuers`·`SecurityProperties`·`PmartAuthenticationToken` |
| 게이트웨이(WebFlux) 연결 | `platform-services/api-gateway` `GatewaySecurityConfig`·`IssuedToken` |
| 시험 사본 | 각 서비스 `src/integrationTest/resources/pmart-config/` (설정을 바꾸면 같이 고친다) |

## 3. 칸

| 칸 | 기본값 | 뜻 |
|---|---|---|
| `id` | (없음) | 이름 — 로그·오류 표시용 |
| `issuer-uri` | **필수** | 토큰 `iss` 와 **정확히** 같아야 한다. 표 안에서 중복이면 기동 실패 |
| `jwk-set-uri` | 비움 | 서명 공개키(JWKS) 주소. 비우면 앱 기본 디코더(`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`) |
| `audiences` | 비움 | 비어 있지 않으면 토큰 `aud` 가 이 중 하나를 포함해야 한다 |
| `user-types` | **필수** | 이 발급자가 낼 수 있는 사용자 종류. `CUSTOMER`·`GUEST`·`RIDER`·`OPERATOR`·`SERVICE` 만 — 모르는 값이면 기동 실패 |
| `type-claim` | `typ` | 사용자 종류를 읽을 claim. **비우면** claim 을 보지 않고 발급자가 정한다(이때 `user-types` 는 하나여야 함 — 아니면 기동 실패) |
| `subject-claim` | `sub` | 주체 ID claim. 점(`.`)으로 중첩 |
| `roles-claim` | `roles` | 역할 claim. 점으로 중첩 |
| `centers-claim` | `centers` | 센터 범위 claim |

## 4. 지금 들어 있는 줄(LAN, 2026-11-11)

| `id` | 발급자 | `issuer-uri` | `jwk-set-uri` | `user-types` | 다른 칸 |
|---|---|---|---|---|---|
| `auth-service` | auth-service | `http://auth-service:8501` | (비움 → `http://auth-service:8501/api/v1/internal/jwks`) | CUSTOMER, GUEST, SERVICE, **OPERATOR** | 기본값 |
| `staff` | Keycloak Realm `pmart-staff`(PC5) | `http://192.168.0.101:8180/realms/pmart-staff` | `…/realms/pmart-staff/protocol/openid-connect/certs` | OPERATOR | `type-claim: ''`, `subject-claim: pmart_operator_id`, `roles-claim: realm_access.roles` |
| `rider` | rider-auth-service(PC5) | `http://rider-auth-service:8510` | `http://rider-auth-service:8510/api/v1/internal/jwks` | RIDER | 기본값 |

- 세 줄 모두 `audiences: [pmart-api]`.
- 로컬(`application-local.yml`)은 `auth-service`(`http://localhost:8501`)·`rider`(`http://localhost:8510`) 두 줄 — Keycloak 줄 없음.
- **auth-service 줄의 OPERATOR**: 기존 백오피스(auth-service 운영자 로그인)를 없애지 않고 Keycloak 과 따로 운영하기로 해서(사용자, 2026-11-11) 지금은 남아 있다. ADR-0054 결정 D 의 "옮긴 뒤 뺀다"는 이 방향에 맞춰 다시 정해야 한다.
- RIDER 는 2026-11-11 auth-service 줄에서 뺐다(rider-auth-service LAN 배치·확인 뒤).

### 발급자마다 claim 이 다른 이유

| | auth-service·rider-auth-service | Keycloak |
|---|---|---|
| 주체 | `sub` = `CU-…`·`RD-…`·`OP-…` | `sub` 는 Keycloak 내부 UUID → 사용자 속성 `pmart_operator_id`(기존 운영자 ID 와 같음) |
| 사용자 종류 | `typ` = `CUSTOMER` 등 | `typ` = `"Bearer"`(토큰 종류) → 보지 않고 발급자로 OPERATOR |
| 역할 | `roles` | `realm_access.roles` |

표가 이 차이를 줄마다 흡수하므로 서비스 코드는 발급자와 상관없이 같은 `PmartPrincipal`(종류·ID·역할·센터)을 받는다.

## 5. 처리 순서(요청마다)

```
Bearer 토큰
  │ 1. iss 로 줄 고르기 ── 표에 없음 → 401
  │ 2. 그 줄의 JWKS 로 서명 확인(RS256) ── 실패 → 401
  │ 3. 그 줄의 규칙(IssuerPolicy.validator)
  │     · 만료(exp·nbf)
  │     · iss 가 issuer-uri 와 같은가
  │     · aud 에 audiences 중 하나가 있는가
  │     · 사용자 종류가 user-types 안인가(type-claim 이 비면 발급자 종류) ── 아니면 401 user_type_not_allowed_for_issuer
  │     · 주체 claim(subject-claim)이 있는가
  │ 4. 줄의 claim 설정대로 읽기 → PmartPrincipal(종류·ID·역할·센터·src)
  ▼
게이트웨이: 주체 차단(⑤, SubjectBlockFilter — 키 = iss + 주체) → 경로별 사용자 종류 허용 목록 → 서비스로
서비스:     같은 1~4 를 다시 하고, 역할·센터·소유권은 서비스가 판단
```

- JWKS 는 요청마다 받지 않는다. 디코더가 캐시하고, 처음 보는 `kid`(키 교체)가 오면 다시 받는다.
- 주체 차단 키의 주체도 이 표의 `subject-claim` 으로 읽은 값이다(Keycloak 은 UUID 가 아니라 `pmart_operator_id`, 2026-11-11 수정 `ad6e2bc`).

## 6. 기동 때 막는 설정 실수

| 실수 | 결과 |
|---|---|
| `issuer-uri` 비움 | 기동 실패 |
| `user-types` 비움·모르는 종류 | 기동 실패 |
| `type-claim` 을 비웠는데 `user-types` 가 둘 이상 | 기동 실패(종류를 정할 수 없음) |
| 같은 `issuer-uri` 두 줄 | 기동 실패 |

## 7. 줄을 더하거나 바꿀 때

1. pmart-config `application-<프로필>.yml` 의 `pmart.security.issuers` 를 고친다.
2. 각 서비스 시험 사본(`src/integrationTest/resources/pmart-config/`)도 같이 고친다.
3. 새 발급자가 `aud=pmart-api` 를 넣는지, 주체·종류·역할이 어느 claim 에 있는지 **실제 토큰으로** 확인하고 칸을 정한다(Keycloak 은 2026-11-10 실제 토큰으로 `typ="Bearer"` 를 확인했다).
4. 게이트웨이와 서비스가 다시 읽어야 적용된다(재시작).
