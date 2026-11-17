# [pmart-backend → pmart-clients] 알림: 약관 동의 — 가입·재동의(AUTH_CONSENT)와 내 동의 (2026-11-17)

- 보낸 영역: platform
- 받는 앱: customer_app (`lib/features/auth` — `login_screen.dart` 동의 화면, `auth_repository.dart` `completeWithConsents`)
- 계약: `contracts/openapi/auth-service.yaml` **0.2.0**, `customer-service.yaml` **0.3.0** — 둘 다 다시 생성해 주세요. 결정 CU-1(사용자 2026-11-17 "추천대로"). 백엔드 커밋은 이 문서와 같은 커밋. **서버는 준비됐고 LAN 스위치는 앱 반영 때까지 꺼짐 — §6.**
- **BR-20 회신**: (1) 토큰이 없는 동의 대기(AUTH_CONSENT)에서 동의를 보내는 곳 = auth `completions` 본문(customer `/me/consent-changes` 가 아님). (2) `requiredConsents` 형식 = `TYPE:version`(목에서 가정한 그대로).

## 1. 항목

| consentType | 앱 문구(arb) | 구분 | 지금 버전 |
|---|---|---|---|
| `TERMS_OF_SERVICE` | `consentTerms` 이용약관 | 필수 | `2026-11-17` |
| `PRIVACY_POLICY` | `consentPrivacy` 개인정보 수집·이용 | 필수 | `2026-11-17` |
| `AGE_OVER_14` | `consentAge14` 만 14세 이상 | 필수 | `1` |
| `MARKETING` | (새 키) 마케팅 정보 수신 — `noticeMarketingConsent` 와 같은 뜻 | 선택 | `2026-11-17` |

- 버전은 서버 설정 값이다. **앱에 상수로 두지 말고 응답에 온 값을 그대로 돌려보낸다**(버전이 바뀌면 서버가 다시 묻는다).
- 계약 `ConsentType` 에서 쓰지 않던 `MARKETING_PUSH`·`MARKETING_SMS`·`NIGHT_MARKETING` 을 빼고 `MARKETING` 하나로 했다(생성 enum 이 바뀐다). 위치기반서비스 약관은 현재 위치 기능을 만들 때 더한다.
- 약관 전문(본문) 보기 경로는 서버에 없다. 화면에 "보기" 링크가 필요하면 요청해 주세요(문서 위치·버전별 보관을 정해야 한다).

## 2. 로그인 흐름 (바뀜)

1. `POST /auth/social-logins` — 요청은 그대로. 응답 `state`:
   - `AUTH_MEMBER` → 지금과 같다(토큰).
   - `AUTH_CONSENT` → **토큰 없음**. `requiredConsents`(예 `["TERMS_OF_SERVICE:2026-11-17","PRIVACY_POLICY:2026-11-17","AGE_OVER_14:1"]`), `optionalConsents`(예 `["MARKETING:2026-11-17"]`, 없으면 `[]`), `accountId`, `newAccount`, `nextAction=COMPLETE_CONSENT`.
   - 언제 오나: 신규 가입, 그리고 **기존 회원도** 필수 약관의 현행 버전에 동의한 기록이 없으면(약관 버전이 바뀐 뒤 첫 로그인 포함). `newAccount=false` 면 재동의다(예: "약관이 바뀌었어요, 다시 동의해 주세요"). 동의 화면에서 나갔던 가입은 다음 로그인도 `newAccount=true`.
   - **스위치를 켜면(§6) LAN 의 기존 회원은 모두 동의 기록이 없어 다음 로그인에서 한 번 AUTH_CONSENT 를 받는다.**
   - 새로 올 수 있는 오류: 503 `DEPENDENCY_UNAVAILABLE`(서버가 동의 상태를 확인하지 못함 — 같은 요청을 다시, 실패 횟수에 안 들어간다).
2. 동의 화면: `requiredConsents` 는 모두 체크해야 버튼이 켜진다(지금 화면 그대로). `optionalConsents` 는 기본 해제 체크박스("[선택] 마케팅 정보 수신").
3. `POST /auth/social-logins/{authRequestId}/completions` — 헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id`(**로그인한 그 기기**). 본문:

   ```json
   {
     "providerToken": "<로그인한 그 Google 계정의 ID 토큰>",
     "consents": [
       { "consentType": "TERMS_OF_SERVICE", "version": "2026-11-17", "agreed": true },
       { "consentType": "PRIVACY_POLICY",   "version": "2026-11-17", "agreed": true },
       { "consentType": "AGE_OVER_14",      "version": "1",          "agreed": true },
       { "consentType": "MARKETING",        "version": "2026-11-17", "agreed": false }
     ]
   }
   ```

   - `providerToken`: 토큰이 없는 단계라 서버가 이것으로 본인을 다시 확인한다. 로그인 때 보낸 값을 그대로 다시 보내도 되고(유효 약 1시간), 만료됐으면 Google 에서 새로 받아 보낸다. Google 프로필(이름·이메일·사진)은 이 단계에서야 고객 정보로 넘어간다(동의 전에는 넘기지 않는다).
   - `consents`: `requiredConsents` 의 각 `TYPE:version` 을 나눠 `agreed: true`. `optionalConsents` 는 고객이 고른 값 — **끈 것도 `false` 로 보내 주세요**(거부로 기록돼 다시 묻지 않는다. 안 보내면 "아직 안 고름"으로 남는다).
   - 200 → `AUTH_MEMBER` + `tokens` + `newAccount`. 그 뒤는 지금 `AUTH_MEMBER` 처리와 같다(`newAccount` 면 내 정보 입력).
   - **customer `POST /me/consent-changes` 를 먼저 부르지 않는다** — 지금 코드의 `// BR-20` 부분은 지운다. 계약에서 `consentChangeRequestId` 는 없앴다.
   - 같은 `Idempotency-Key` 재전송(응답 유실)은 새 토큰을 준다. `GET /auth/social-logins/{id}` 는 `AUTH_CONSENT` 면 `nextAction=COMPLETE_CONSENT` 와 같은 두 목록을 준다(앱이 다시 열렸을 때 복구).
4. 완료 오류:

| 상태 | code | 앱 처리 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 본문 오류(항목 이름 등) |
| 401 | `PROVIDER_REJECTED` | Google ID 토큰을 새로 받아 다시 / 안 되면 로그인 처음부터 |
| 403 | `FORBIDDEN` | 다른 기기이거나 다른 Google 계정 — 로그인 처음부터 |
| 403 | `ACCOUNT_NOT_AVAILABLE` | 이용할 수 없는 계정(지금 안내 그대로) |
| 409 | `INVALID_STATE` | 동의 대기가 아님(이미 다른 키로 끝남 등) — 로그인 처음부터 |
| 422 | `REQUIRED_CONSENT_MISSING` | 필수 항목이 빠짐(`details[].field` = 빠진 `TYPE:version`) — 동의 화면 유지 |
| 422 | `CONSENT_VERSION_OUTDATED` | 그 사이 약관 버전이 바뀜 — 로그인 처음부터(새 목록) |
| 423 | `AUTH_RESTRICTED` | 지금과 같음 |
| 503 | `DEPENDENCY_UNAVAILABLE`·`PROVIDER_UNAVAILABLE` | 같은 `Idempotency-Key` 로 다시 |

## 3. 로그인해 있는 고객 — 내 동의 (customer 0.3.0)

- `GET /me/consents` → `items` 4개 `{consentType, required, agreed, version(고른 버전, 없으면 null), latestVersion, agreedAt, withdrawnAt, channel}` + `requiredConsentOutdated`.
- `POST /me/consent-changes`(헤더 `Idempotency-Key`·`X-Source-System`) `{"changes":[{"consentType","version"(= latestVersion),"agreed"}]}` → 200 `APPLIED`. 결과 다시 보기 `GET /me/consent-changes/{requestId}`.
  - 마케팅 켜고 끄기는 여기로(설정 화면 "마케팅 정보 수신 동의"). notification `marketingConsent` 는 아직 UNKNOWN 이니 설정 화면의 마케팅 값은 이 응답의 `MARKETING` 으로.
  - 필수 항목 끄기 → 422 `REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN`(필수 동의 철회 = 탈퇴. 탈퇴는 아직 서버에 없다). 옛 버전 → 422 `CONSENT_VERSION_OUTDATED`(다시 조회).
- **주문 전 확인(중요)**: 서버 스위치가 켜지면(§6) 필수 동의가 현행 버전이 아닌 고객은 구매 가능 여부가 false 라 주문 초안·결제 진입이 422 `ACCOUNT_NOT_PURCHASABLE` 이다. 이미 로그인해 있는 고객(세션이 살아 있어 로그인을 다시 하지 않는 사람 — 지금 LAN 의 기존 회원 모두)은 로그인 동의 화면을 거치지 않으므로, **앱 시작 또는 주문서 진입 전에 `GET /me/consents` 의 `requiredConsentOutdated=true` 면 같은 동의 화면을 보여 주고 `POST /me/consent-changes` 로 받는다**(토큰이 있으니 completions 가 아님). 업무 흐름 Commerce 3장 "회원·재동의 필요 → SCR-CONSENT-001".

## 4. 앱에서 할 일

1. 계약 다시 생성(auth 0.2.0, customer 0.3.0).
2. `completeWithConsents`: `/me/consent-changes` 호출을 지우고 completions 본문을 `providerToken` + `consents` 로. `LoginNeedsConsent` 가 `providerToken`(또는 다시 받을 방법)·`optionalConsents`·`newAccount` 를 들고 있게.
3. 동의 화면: 선택 항목 체크박스(예 새 arb `consentOptional` "[선택] {name}", `consentMarketing` "마케팅 정보 수신"), `newAccount=false` 면 재동의 안내 문구.
4. §2-4 오류 처리, 로그인 503 `DEPENDENCY_UNAVAILABLE` 은 "다시 시도".
5. 로그인해 있는 고객의 재동의(§3 주문 전 확인)와 설정의 마케팅 켜고 끄기.

## 5. 서버 쪽 사실(참고)

- 동의 기록: 항목·동의 여부·약관 버전·동의/철회 시각·받은 곳(`SIGNUP` 가입·`LOGIN` 로그인 재동의·`APP` 앱). 바뀐 기록은 모두 남고, 바뀔 때마다 `CustomerConsentChanged` 사건.
- 같은 값·같은 버전을 다시 보내면 바뀌지 않는다(그래도 200).
- 비회원 세션·장바구니는 바뀌지 않는다(동의와 무관).

## 6. LAN — 서버는 준비, 스위치는 앱 반영 때까지 꺼짐

- **서버 기능은 준비됐고, LAN 은 두 스위치를 꺼 둔다**(앱이 이 흐름을 반영할 때까지):
  - auth `pmart.auth.customer.require-consent: false` → 로그인은 지금처럼 바로 `AUTH_MEMBER`(AUTH_CONSENT 가 오지 않는다).
  - customer `pmart.customer.consents.purchase-gate: false` → 동의가 없어도 지금처럼 주문할 수 있다(`requiredConsentOutdated` 는 실제 값으로 온다).
- 꺼져 있어도 `GET /me/consents`·`POST /me/consent-changes`(마케팅 켜고 끄기, 재동의)는 동작하니 앱이 먼저 붙여 시험할 수 있다. completions 새 본문은 스위치가 켜져야(AUTH_CONSENT 가 와야) 시험할 수 있다 — 앱 쪽이 준비되면 알려 주세요, PC 1 이 함께 켠다.
- **켤 때는 둘을 함께** `true`(PC 1, pmart-config). 하나만 켜면 동의할 길 없이 주문이 막히거나, 묻기만 하고 구매 판단과 어긋난다. 켜는 순간 LAN 의 기존 회원은 모두 동의 기록이 없어 다음 로그인(또는 §3 주문 전 확인)에서 한 번 동의한다.
- 배포·스위치를 켠 날은 회신 파일에 적는다.
