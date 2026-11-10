# ADR-0054 직원·라이더 로그인은 Keycloak(서버 1개, Realm 2개), 고객은 auth-service 유지, 같은 사람 연결은 Google 신원으로

- 상태: 승인 (2026-11-10, 사용자 "키클록 구성을 따를거야")
- 근거: 사용자가 준 추천 글 `docs/architecture/auth/references/2026-11-10/PMart-최종-인증-아키텍처-추천.md`(구성)와 `PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`(게이트웨이). 확인 기록 `docs/architecture/auth/2026-11-10-*-review.md`.
- 대체: ADR-0011 대안의 "Keycloak 은 도입하지 않는다"(서비스 신원 토큰 결정 자체는 유지), ADR-0018 대안의 "외부 IdP(Keycloak) 도입 안 함"과 운영자 로그인의 발급 주체(auth-service → Keycloak). ADR-0019·0025(고객 Google 로그인)는 그대로.

## 결정
1. **Keycloak 서버 1개, Realm 2개**: `pmart-staff`(백오피스·MFC 등 직원), `pmart-rider`(라이더, Google 연동 Identity Provider). 물리 서버 분리는 하지 않는다.
2. **고객은 지금 그대로** auth-service Google 로그인 + PMart JWT(ADR-0019·0025). 비회원(`GUEST`)·서비스 신원(`SERVICE`, ADR-0011) 토큰도 auth-service 가 계속 발급한다.
3. **같은 사람 연결은 Google `sub`** 로 한다(이메일 비교 금지 — Commerce 4.9 와 같음). 고객 Google `sub` 는 auth-service `social_identity` 에 이미 있다. 라이더 쪽 Google `sub` 는 Keycloak 이 연동하며 확인한 값을 **서버 쪽 신뢰 경로**(Keycloak 관리 API 등)로 얻는다 — 앱이 보낸 문자열을 믿지 않는다. 처음에는 라이더 등록 때 연결한다.
4. **연결은 권한을 옮기지 않는다**: 고객 가입이 라이더 자격을 주지 않고, 라이더 정지가 고객 계정을 막지 않는다. 라이더 승인·정지·배차 가능 상태는 rider-service 정본(service-map).
5. **게이트웨이는 1개 유지**하고 발급자 3개(auth-service, `pmart-staff`, `pmart-rider`)를 받는다. 발급자와 사용자 종류가 맞아야 통과(auth-service = CUSTOMER·GUEST·SERVICE, staff = OPERATOR, rider = RIDER). 각 서비스도 같은 규칙으로 다시 검증한다(게이트웨이 확인으로 서비스 검증을 생략하지 않음).
6. 서비스가 쓰는 권한 형태(`TYPE_x`, 역할, 센터)는 유지한다 — Keycloak 토큰을 이 형태로 바꾸는 일은 공통 변환기(`common-security`)와 Keycloak 클레임 매퍼가 맡는다.

## 정할 것(구현 전)
- **직원 주체 ID 유지 방법**: 지금 운영자 ID 가 여러 서비스에 기록돼 있다(예: mfc-capacity `workerId = 운영자 ID`, 승인 이력의 처리자). Keycloak 기본 `sub` 는 UUID 라, 기존 ID 를 클레임으로 싣는 방법·이전 순서를 정한다.
- **라이더 가입 방식과 업무 원문의 차이**: `Delivery-business-logic.md` §1.1 은 전화번호 인증·아이디·비밀번호·"가입 완료 → 즉시 운행 승인", 추천 글은 Google + 승인 심사. 어느 쪽을 따를지(또는 함께) 사용자 결정.
- 운영자 계정 이전 범위·시기(LAN 시험 계정 `claude-*` 포함), 백오피스·MFC 앱 로그인 변경 요청(pmart-clients handoff).
- Keycloak 이미지 버전 고정, 저장 DB, 배치 PC, 관리자 비밀 보관(`deploy/lan/.secrets`), 토큰 수명(지금 운영자 15분·갱신 12시간), `iss` 주소가 앱·게이트웨이·서비스에서 같게 보이도록 하는 호스트 이름.
- 즉시 차단이 필요한 경우(퇴사·라이더 정지)의 방법 — 짧은 수명 + 서버 쪽 상태 확인.

## 대안
- **auth-service 하나에 라이더 로그인 추가**(발급자 1개 유지): 변경이 가장 작다. 사용자가 Keycloak 구성을 골랐다.
- **Keycloak 서버 2개**: 장애 격리 외 이점이 작고 운영 부담이 크다(추천 글 §5).
- **고객도 Keycloak**: 고객 로그인은 이미 운영 중이고 바꿀 요구가 없다.

## 결과
- 새 운영 구성요소(Keycloak + DB)가 생긴다. LAN 배치·백업·모니터링 대상이 는다.
- 발급자가 3개가 되어 게이트웨이와 전 서비스의 JWT 검증 설정이 바뀐다(`common-security` 변경 = 전 서비스 영향, 변경 때 보고).
- 운영자 로그인 이전 동안 두 방식이 잠시 함께 있을 수 있다 — 이전 계획에서 정한다.
