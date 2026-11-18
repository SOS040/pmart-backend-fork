# 회신 — BR-59 처음 실행 약관 (위치기반서비스 약관·로그인 전 동의·수신동의 처리 결과·약관 본문)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-19-backend-first-run-consents.md`(BR-59 ①~④)
- 사용자 결정(2026-11-19): 추천안 "응" → auth 도 같이("응 그렇게 해줘")
- 계약: customer-service **0.6.0**, auth-service 변경 없음(내부 호출만 더함). LAN: **배포됨**(2026-11-19 07:31, 이미지 3ffa9868 — customer·auth·api-gateway). 게이트웨이로 확인: `/consent-terms` 항목 다섯·senderName PMart, 기기 동의 200, 기기에 이용약관 400

## ① 위치기반서비스 약관 — 동의 유형 `LOCATION_BASED_SERVICE`
- **현재 위치 기능을 쓸 때만 필수**: `required=false`, `requiredFor="CURRENT_LOCATION"`(Consent·ConsentTermList 에 새 칸).
- 로그인·구매 가능 여부의 필수 목록(`missingRequired`)에도, 로그인 동의 단계의 선택 목록(`optionalConsents`)에도 **들어가지 않는다** — 기존 회원이 다시 동의하지 않는다.
- 앱이 휴대폰 위치로 주소를 찾기 전에 이 동의를 받는다(로그인 전이면 ②, 로그인 뒤면 `POST /me/consent-changes`). 주소를 직접 넣는 고객에게는 묻지 않아도 된다.
- 현행 버전 LAN `2026-11-19`(`GET /consent-terms` 의 latestVersion 으로 읽는다 — 앱에 고정하지 않는다).
- `/me/consents` 항목이 다섯이 됐다(전에는 넷).

## ② 로그인 전(기기) 동의 — 토큰 없이 X-Device-Id (비회원 배송지와 같은 방식)
- `GET /api/v1/device-consents` → `{deviceId, senderName, items: [MARKETING, LOCATION_BASED_SERVICE]}`(고르지 않았으면 agreed=false·version null).
- `POST /api/v1/device-consent-changes`(Idempotency-Key·X-Source-System·X-Device-Id, 본문 `/me/consent-changes` 와 같은 `{changes:[{consentType, version, agreed}]}`) → 반영 뒤 상태(200).
  - 받는 항목은 **MARKETING·LOCATION_BASED_SERVICE 뿐** — 필수 약관(이용약관 등)은 400(가입 때 로그인 동의 단계에서).
  - 낡은 버전 422 `CONSENT_VERSION_OUTDATED`, 같은 항목 두 번 400, 같은 키 재전송은 다시 반영하지 않는다.
- **로그인하면 서버가 옮긴다**: auth 가 로그인(회원 토큰을 준 뒤)·동의 완료 뒤 그 기기의 동의를 계정으로 옮기고(계정에 현행 버전 결정이 없는 항목만 — 계정 값이 이긴다) 기기 기록은 지운다. 앱이 따로 부를 것은 없다.
  - 로그인 동의 단계(AUTH_CONSENT)는 기기에서 이미 고른 마케팅을 **다시 묻지 않는다**(`optionalConsents` 에서 빠짐).
  - 옮긴 항목의 `channel` 은 `DEVICE`(새 값).
  - 같은 기기를 로그아웃 뒤 다른 사람이 비회원으로 쓰면 처음 실행 동의를 다시 받는다(기기 기록이 비었으므로 `GET /device-consents` 가 고르지 않은 상태).
- 앱이 보안 저장소에만 두던 값(`pmart.start.welcome`)은 이 경로로 보내면 된다.

## ③ 수신 동의 처리 결과(전송자·일시·처리 내용)
- 동의 조회 `GET /me/consents`·`GET /device-consents` 에 **`senderName`**(전송자 — LAN `PMart`, 설정)과 항목별 **`lastChangedAt`**(처리 일시)이 생겼다. 처리 내용은 `agreed`(동의/거부·철회).
- 바꾼 뒤(기기는 POST 응답이 곧 반영 뒤 상태, 회원은 POST 뒤 GET /me/consents) 이 값으로 "수신동의 처리완료" 창을 띄우면 된다. 서버가 처리하지 않은 값으로 창을 띄우지 않는다.

## ④ 약관 본문 — `GET /api/v1/consent-terms`(토큰 없음)
- `{senderName, items: [{consentType, required, requiredFor, latestVersion, documentUrl}]}` — 항목 다섯.
- `documentUrl` 은 설정(`pmart.customer.consents.documents.<항목>`)이고 **지금은 모두 null** — 약관 문구가 정해지면 넣는다(서버가 본문을 지어내지 않는다). null 이면 `>` 펼쳐 보기를 숨기면 된다.
