# rider-service 에 대한 요청 (MFC·배차 쪽에서)

rider-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다.

## RS-1. 라이더 단계 사건 발행 (D-4, 2026-11-08 결정 "추천대로")

- **요청한 곳**: delivery-dispatch(배정 풀기의 원천), mfc-fulfillment(간접).
- **필요한 것**: `RiderStateChanged`/`RiderRestricted` — 단계: 신규배차 제한 / 일시 중지 / 계약 종료. dispatch 가 일시 중지·계약 종료 라이더의 배정을 풀고(`DeliveryAssignmentChanged`) 재배정한다(DD-1 4번).
- **설계상 그대로 둘 것**: MFC 는 라이더 단계를 직접 보지 않는다 — `DeliveryAssignmentChanged` 로만 막는다.
- 라이더 앱의 QR → 인계 허가 → 확정 흐름은 rider 앱·delivery-execution(DE-1) 쪽.
- 근거: `docs/business/flows/package-handover-contract.md`, `package-handover-decisions.md` D-4.

## RS-2. 라이더 가입을 rider-auth-service 토큰으로 받기 (2026-11-10, ADR-0054 G·ADR-0055)

- **요청한 곳**: rider-auth-service(라이더 로그인·토큰은 거기서, 가입 업무는 여기서).
- **필요한 것**:
  - `POST /riders/signups` 를 라이더 토큰(`iss` = rider-auth-service, `typ=RIDER`)으로 받고, **riderId = 토큰 `sub`(`RD-…`)** 로 라이더 프로필을 만든다(본문에 riderId 를 받지 않는다). 같은 주체로 다시 가입하면 기존 가입 결과.
  - 입력·처리는 ADR-0054 결정 G: Google 로그인 → **전화번호·배달 지역·배달 수단·약관** 입력 → **즉시 운행 승인**(업무 원문 §1.1). 전화번호는 본인인증 API 가 없어 **중복 확인만**.
  - `GET /riders/me` 가 미가입이면 404 — 라이더 앱은 로그인 뒤 이것으로 가입 화면 여부를 정한다(rider-auth 응답 `newAccount` 는 참고).
- **지금(임시 방식)**: rider-service 는 501. LAN 시험은 auth-service 서명 시험 라이더 토큰(`RD-claude-*`).
- **계약과 어긋난 곳(구현 때 고칠 것)**: 지금 `rider-service.yaml` 가입 설명은 "전화 본인확인 후, 면허·주민번호 중복 차단", `422 PHONE_NOT_VERIFIED(auth 본인확인 미완료)` — 결정 G(본인확인 없음, 전화번호 중복 확인만)와 다르다. **사용자 결정(2026-11-10): 가입 때 면허·주민번호를 받지 않고, 중복 확인은 전화번호만 한다.** 구현 때 계약에서 `PHONE_NOT_VERIFIED` 와 면허·주민번호 중복 차단 문구를 빼고, 전화번호 중복은 `409` 로 둔다.
- **나중**: 정지(`RiderRestricted`)를 rider-auth-service 가 받아 세션을 끊는 일(ADR-0054 ⑤ 차단 목록).
- 근거: `docs/adr/0054-auth-split-keycloak-staff-rider-auth-service.md` 결정 G, `docs/adr/0055-google-login-id-token-only-customer-rider.md`, `contracts/openapi/rider-auth-service.yaml`.
