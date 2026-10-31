# [pmart-backend → pmart-clients] 요청: Google 서버 인가 코드(`authorizeServer`) 실기기 시험 (2026-10-31)

- 관련: ADR-0025(초안, 고객 Google 로그인 하이브리드), ADR-0019(현행 ID 토큰 방식)
- 성격: **시험만** 요청한다. 계약·서버·로그인 흐름은 아직 바꾸지 않는다. 시험 코드는 출시 빌드에 넣지 않는다.

## 1. 왜
- 백엔드는 고객 Google 로그인을 하이브리드로 바꾸려 한다. 앱은 지금처럼 네이티브 로그인(`authenticate()`)을 쓰고, 그 뒤 **서버 인가 코드(`serverAuthCode`)** 를 받아 서버에 넘긴다. 서버(auth-service)가 client secret 으로 그 코드를 교환한다.
- 이 방식은 아래 두 조건을 만족해야만 채택한다. 둘 다 Google·기기 동작이라 실기기로만 확인할 수 있다.
  1. **추가 동의 화면이 없어야 한다**(사용자 결정). 계정 선택 창 뒤에 Google OAuth 동의 화면("pmart 에서 … 접근하려고 합니다")이 한 번 더 뜨면 안 된다.
  2. **재로그인 때도 코드가 나와야 한다.** `google_sign_in` 7.2.0 설명에 "일부 플랫폼은 첫 로그인 때만 서버 인가 코드를 준다"고 되어 있다.

## 2. 시험 방법 (Android 만 — iOS 는 개발하지 않는다)
지금 `GoogleSocialTokenSource.obtain()` 의 `authenticate()` 다음에 시험용으로 아래를 부른다.

```dart
final account = await _signIn.authenticate();
final server = await account.authorizationClient
    .authorizeServer(const ['openid', 'email', 'profile']);
final code = server?.serverAuthCode;
// 코드 값은 로그·화면에 남기지 않는다. null 여부와 길이만 기록한다.
```

- `initialize(serverClientId: …)` 는 지금 값(서버용 web 클라이언트 ID) 그대로 둔다.
- scope 는 위 세 개만 쓴다. 다른 scope 를 넣거나 오프라인 접근을 요청하지 않는다(동의 화면 원인).
- `authorizeServer` 는 로그인 버튼을 누른 흐름 안에서 부른다(사용자 조작 중에만 부를 수 있다).
- 시험 뒤에도 지금처럼 ID 토큰으로 서버 로그인을 계속해도 된다. 서버는 받은 코드를 아직 쓰지 않는다.

## 3. 시험할 경우

| # | 경우 | 준비 |
|---|---|---|
| 1 | 신규 계정 첫 로그인 | 이 앱에 한 번도 로그인한 적 없는 Google 계정, 또는 Google 계정 설정 → 보안 → "타사 앱 및 서비스"에서 pmart 연결 해제 뒤 |
| 2 | 같은 계정 재로그인 | 1 다음 앱 로그아웃(`signOut`) → 다시 로그인 |
| 3 | 앱 재설치 뒤 로그인 | 1 의 계정으로, 앱 삭제·재설치 뒤 |
| 4 | 취소 | `authorizeServer` 단계에서 창이 뜨면 취소(뜨지 않으면 생략) |

## 4. 회신해 줄 것 (경우마다)
- 계정 선택 창 뒤에 **추가 화면이 떴는가**(예/아니오). 떴다면 화면 문구와 스크린샷(계정 이메일은 가려서).
- `serverAuthCode` 가 **null 인가**, 아니면 값이 있는가(길이만).
- 예외가 났다면 `GoogleSignInExceptionCode` 값.
- 기기 모델·Android 버전·Google Play 서비스 버전.

회신은 collab 또는 pmart-clients 의 handoff 회신 문서로 주면 된다. 결과를 받으면 ADR-0025 를 승인할지(→ 계약·서버 구현 착수), 방식을 다시 정할지 결정한다.
