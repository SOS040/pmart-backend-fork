# `aud` 와 `typ` — 무엇을 구분하고 무엇을 막나

- 작성: 2026-11-11(사용자 질문 정리). 관련 결정: ADR-0055(Google 은 ID 토큰 검증만, PMart JWT `aud=pmart-api`), ADR-0054(발급자 셋, 발급자 ↔ 사용자 종류 짝).
- 함께 볼 문서: `login-structure-and-flow.md`(전체 로그인 구조), `keycloak-structure.md`(직원 토큰), `issuer-type-pairing-and-access-lists.md`(발급자 표).

## 1. 토큰이 두 종류다

| 토큰 | 누가 발급 | 언제 쓰나 |
|---|---|---|
| **Google ID 토큰** | Google | 로그인 때 **한 번** — 앱이 받아 PMart 로그인 API 본문 `providerToken` 으로 보낸다. PMart 는 검증하고 버린다(저장·로그 안 함) |
| **PMart JWT** | auth-service(고객·비회원·운영자) · rider-auth-service(라이더) · Keycloak `pmart-staff`(직원) | 그 뒤 **API 호출마다** `Authorization: Bearer` |

Google ID 토큰을 PMart JWT 안에 넣지 않는다. 남기는 것은 Google `sub`(계정 번호) 하나 — 우리 계정(`CU-…`·`RD-…`)과 잇는 열쇠로 auth-service `social_identity`·rider-auth-service `rider_identity` 표에.

## 2. `aud`(대상) — "이 토큰을 누가 받아 써도 되나"

### 2.1 Google ID 토큰의 `aud` = 웹 클라이언트 ID

| 앱 | `aud` |
|---|---|
| 고객 앱 | 고객용 웹 클라이언트 ID `293585219605-p6akml03….apps.googleusercontent.com` |
| 라이더 앱 | 라이더용 웹 클라이언트 ID `293585219605-la3cj13q19oo51p7e4d0rf4ickh3flgu.apps.googleusercontent.com` |

- **넣는 쪽은 Google**: 앱이 `google_sign_in` 의 `serverClientId` 로 준 웹 클라이언트 ID 를 Google 이 `aud` 에 넣는다.
- **확인하는 쪽**: auth-service(`pmart.auth.customer.google.client-ids` — 고객 ID 하나), rider-auth-service(라이더 ID 하나). 다르면 401 `PROVIDER_REJECTED`.
- Android 클라이언트 ID 는 `aud` 에 들어가지 않는다(Google 이 패키지·SHA-1 로 앱을 알아보는 데만 쓴다). 코드·설정에 넣지 않는다.

### 2.2 PMart JWT 의 `aud` = `pmart-api`

| 발급자 | `aud` |
|---|---|
| auth-service | `["pmart-api"]`(`pmart.auth.audience`) |
| rider-auth-service | `["pmart-api"]` |
| Keycloak `pmart-staff` | `["pmart-api", "account"]` — `pmart-api` 는 클라이언트 mapper, `account` 는 Keycloak 기본(무시) |

- **넣는 쪽은 우리 발급자**(2026-11-10~, ADR-0055 결정 6).
- **확인하는 쪽**: 게이트웨이와 모든 서비스 — 발급자 표 각 줄 `audiences: [pmart-api]`. 없으면 401. 손으로 만드는 LAN 시험 토큰에도 넣는다.

## 3. `typ` — "누구의 토큰인가"(사용자 종류)

- **PMart JWT 에만 있는 값**: `CUSTOMER`·`GUEST`·`RIDER`·`OPERATOR`·`SERVICE`.
- **어느 앱(클라이언트)에서 받았는지가 아니다.** 운영자는 백오피스 웹이든 MFC 앱이든 같은 `OPERATOR` 다.
- 발급자마다 낼 수 있는 종류가 정해져 있다(발급자 ↔ 종류 짝, 발급자 표 `user-types`): auth-service → CUSTOMER·GUEST·SERVICE(+ 옮기는 동안 OPERATOR), rider-auth-service → RIDER, Keycloak → OPERATOR. 짝이 맞지 않으면 401.
- **Keycloak 토큰의 `typ` 은 "Bearer"** — Keycloak 이 토큰 종류(access token)를 적는 값이라 사용자 종류가 아니다. 그래서 발급자 표 staff 줄은 `type-claim: ''` 로 `typ` 을 보지 않고 **발급자로 종류(OPERATOR)를 정한다**.
- 쓰는 곳: 게이트웨이(SERVICE·GUEST 제한, 경로별 허용 목록), 서비스(`PmartPrincipal.type()`). 역할·센터·소유권 판단은 각 서비스.

### 클라이언트를 구분하는 값은 따로 있다

| 값 | 어디 | 뜻 |
|---|---|---|
| `aud` | PMart JWT | 어느 **API** 용 토큰인가(`pmart-api`) |
| `azp` | Keycloak 토큰 | 어느 **클라이언트**가 받았나(예 `pmart-backoffice`·`pmart-dev-cli`) — 지금은 검사하지 않음(참고용) |

## 4. Google `aud` 확인과 `typ` 의 관계

둘은 **다른 단계**에서 동작하고, 앞 단계가 뒤 단계의 전제다.

```
[로그인 때 한 번]                                        [API 호출마다]
Google ID 토큰 ─► aud 확인 ─► 어느 인증 서버가 받나 ─► PMart JWT 발급(typ 결정) ─► 게이트웨이: iss ↔ typ 짝 확인
                  (고객 웹 ID?   auth-service      → CUSTOMER
                   라이더 웹 ID?) rider-auth-service → RIDER
```

- Google ID 토큰에는 사용자 종류가 **없다**. 고객용인지 라이더용인지 알려 주는 것은 Google `aud`(어느 앱의 `serverClientId` 로 받았나)뿐이다.
- 그래서 **`typ` 이 맞게 정해지려면 그 앞에서 Google `aud` 확인이 지켜져야 한다.**
  - Google `aud` 를 확인하지 않으면: 고객 앱에서 받은 Google 토큰을 rider-auth-service 에 보내도 rider-auth-service 는 정상 절차로 **`typ=RIDER` 토큰을 발급**한다.
  - 게이트웨이의 발급자 ↔ 종류 짝 검사는 이것을 막지 못한다 — rider-auth-service 가 RIDER 를 내는 것 자체는 맞기 때문이다.
  - 즉 **`typ`·짝 검사는 "발급자가 정한 종류가 맞게 쓰이는가"**, **Google `aud` 는 "그 발급자가 애초에 받아도 되는 로그인이었나"** 를 지킨다.

## 5. Google `aud` 를 확인하는 더 중요한 이유 — PMart 가 아닌 곳의 Google 토큰

- Google ID 토큰은 **Google 로그인을 쓰는 다른 모든 앱·웹사이트**도 받는다.
- 어떤 사용자가 다른 사이트에 Google 로 로그인하면, 그 사이트 운영자는 그 사람의 Google ID 토큰을 갖게 된다. `aud` 를 확인하지 않으면 그 토큰을 PMart 로그인 API 에 보내 **그 사람 행세로 PMart 에 로그인**할 수 있다.
- 그 토큰의 서명·`iss`(Google)·만료는 모두 정상이다. PMart 용이 아니라는 것은 **`aud` 에서만** 드러난다.

## 6. 정리

| 값 | 어느 토큰 | 뜻 | 막는 것 |
|---|---|---|---|
| Google `aud` | Google ID 토큰 | 어느 앱(웹 클라이언트 ID)용 Google 로그인인가 | 다른 사이트·앱의 Google 토큰으로 로그인하기, 고객 ↔ 라이더 로그인 바꿔치기 |
| PMart `aud`(`pmart-api`) | PMart JWT | 어느 API 용 토큰인가 | 같은 발급자가 낸 다른 용도의 토큰을 PMart API 에 쓰기(Keycloak 은 한 Realm 의 여러 클라이언트 토큰이 같은 `iss`) |
| `typ` | PMart JWT(Keycloak 은 발급자로 대신) | 누구의 토큰인가(사용자 종류) | 고객 토큰으로 운영자 API 쓰기 같은 종류 섞임 — 발급자 ↔ 종류 짝과 함께 |
| `azp` | Keycloak 토큰 | 어느 클라이언트가 받았나 | 지금은 검사하지 않음 |

## 7. 설정 위치

| 값 | 설정 |
|---|---|
| 허용할 Google `aud`(고객) | pmart-config `auth-service-<프로필>.yml` `pmart.auth.customer.google.client-ids` |
| 허용할 Google `aud`(라이더) | pmart-config `rider-auth-service.yml` `pmart.rider-auth.google.client-ids` |
| PMart JWT 에 넣는 `aud` | `pmart.auth.audience`(auth-service), `pmart.rider-auth.audience`(rider-auth-service, 기본 `pmart-api`), Keycloak 클라이언트 mapper `pmart-api-audience` |
| 확인하는 `aud`·종류·주체 | pmart-config `application-<프로필>.yml` 발급자 표 `pmart.security.issuers`(`audiences`·`user-types`·`type-claim`·`subject-claim`) |
