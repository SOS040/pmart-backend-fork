# [pmart-backend → pmart-clients] 요청: 앱 사용 중 세션(refresh token) 만료 처리 (2026-11-02)

- 성격: **고객앱 쪽 보완 요청.** 백엔드는 고객 세션을 **마지막 사용 기준 연장**으로 바꿨다(§1, 2026-11-02 사용자 결정).
- 확인한 앱 코드(2026-11-02, 읽기만): `packages/pmart_core/lib/src/interceptors.dart` `PmartAuthInterceptor`, `auth_tokens.dart` `TokenStore`, `apps/customer_app/lib/shared/providers.dart`(`dioProvider`·`guestDioProvider`·`authRefresher`), `features/auth/presentation/auth_controller.dart`(`SessionController.restore`)
- 관련 계약: `auth-service.yaml` `POST /auth/tokens`(refreshTokens), `POST /auth/social-logins`, `GET /auth/sessions/current`

## 1. 백엔드 동작
| 값 | 고객 |
|---|---|
| access token(JWT, RS256) | 30분 |
| refresh token | 30일, 쓸 때마다 회전(새 값 발급, 쓴 값 무효) |
| **세션 수명** | **마지막 사용 기준 30일** — 토큰을 갱신할 때마다 세션 만료가 "지금 + 30일"로 늘어난다. 30일 동안 앱을 쓰지 않아야 다시 로그인 |

- `POST /auth/tokens` 가 **401** 이면 세션이 끝난 것이다 → 다시 소셜 로그인.
  - `TOKEN_INVALID`: refresh 만료(30일 미사용)·없는 값·계정 이용 불가
  - `TOKEN_REUSED`: 이미 쓴 refresh 를 다시 씀 → 서버가 세션을 폐기
- 다시 소셜 로그인하면 **같은 accountId** 로 새 세션이 생긴다(Google `sub` 로 계정을 찾음). 장바구니·주소·주문은 그대로다.

## 2. 지금 앱에서 비어 있는 부분
1. **앱을 새로 켰을 때 만료** — 정상. `restore()` → 갱신 401 → 토큰 삭제 → 비회원으로 시작.
2. **앱을 쓰는 도중 만료** — 처리가 없다.
   - `PmartAuthInterceptor` 는 만료 때 `onSessionExpired` 를 부르게 돼 있지만, 고객앱 `createPmartDio(...)` 호출(`dioProvider`)이 **이 콜백을 넘기지 않는다.**
   - 그래서 토큰은 지워졌는데 `sessionProvider` 는 계속 `MemberSession` 이다. 화면마다 "세션 만료" 오류 + [다시 시도] 가 뜨고, 다시 시도해도 같은 오류(토큰 없이 401 → refresh 없음 → 만료)가 반복된다. 앱을 다시 켜야 풀린다.
3. Google **조용한 재로그인**(`signInSilently`)을 쓰는 곳이 없어, 세션이 끝나면(30일 미사용·재사용 감지·계정 상태 변화) 고객이 로그인 화면을 다시 봐야 한다.

## 3. 요청
1. **만료 콜백 연결**: `dioProvider` 의 `createPmartDio(onSessionExpired: …)` 로, 만료되면 `sessionProvider` 를 `GuestSession` 으로 바꾼다(한 번만 — 동시에 여러 요청이 만료를 받아도).
2. **조용한 재로그인 먼저**: 만료되면 Google `signInSilently` 로 새 ID 토큰(또는 ADR-0025 가 승인되면 서버 인가 코드)을 받아 `POST /auth/social-logins` → 성공하면 `MemberSession` 으로 되돌리고, 실패한 요청을 사용자가 다시 시도할 수 있게 한다(자동 재전송은 조회만 — 명령은 같은 Idempotency-Key 로만).
3. **조용한 재로그인이 안 되면** "다시 로그인해 주세요" 안내 → 로그인 화면. 로그인 뒤 원래 화면으로 돌아온다.
   - **결제 중이었으면**(결제 진입 뒤 `PAY` 대기·결제창·승인 요청 중) 로그인 뒤 결제 결과를 먼저 확인한다: `GET /payments/{orderId}` 또는 초안 조회. 결과를 놓쳤을 수 있다 — 결제를 다시 시작하지 않는다.
   - 주문서·장바구니 입력값은 버리지 않는다.
4. 비회원 토큰(`guestDioProvider`)은 지금처럼 따로 둔다 — 회원 만료가 비회원 장바구니 토큰을 지우지 않게.

## 4. 함께 보면 좋은 것 (선택, 같은 영역)
- **앱 시작 때 401 한 번 줄이기**: `restore()` 가 access token 없이 `GET /auth/sessions/current` 를 보내 401 을 받은 뒤 갱신한다. 저장된 refresh token 이 있으면 **먼저 `POST /auth/tokens`** 로 받고 `sessions/current` 를 부르면 왕복 1번이 준다.
- **홈 두 번 로딩**: 복원이 끝나기 전 비회원 상태로 `GET /storefront/home`(mfcId 없음)을 부르고, 회원이 된 뒤 그 MFC 로 다시 부른다. 복원 중에는 홈을 잠깐 기다리거나(짧은 로딩), 결과가 바뀌어도 깜빡이지 않게.

## 5. 백엔드 결정 (2026-11-02)
- 고객 세션은 **마지막 사용 기준으로 연장**한다(pmart-config `auth-service.yml` `pmart.auth.customer.session-max-age: P30D` = 유휴 한도). 자주 쓰는 고객은 다시 로그인하지 않는다. 운영자 세션(12시간)은 절대 수명 그대로다.
- 그래도 §3 처리는 필요하다: 30일 미사용, refresh 재사용 감지(세션 폐기), 계정 이용 불가로 세션이 끝날 수 있다.
- **LAN 반영 2026-11-02 19:00 KST** — auth-service `00205b0`(고객 세션 마지막 사용 기준 연장). 갱신 경로는 게이트웨이 경유로 확인(비회원 토큰 갱신 200). 고객 토큰 연장은 통합 시험으로 확인 — 앱에서도 한 번 확인 부탁.
