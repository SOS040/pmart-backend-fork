# ADR-0054 인증을 셋으로 나눈다 — 직원은 Keycloak, 고객은 지금 auth-service, 라이더는 Google 로그인 라이더 auth 서비스(새로), 게이트웨이는 1개

- 상태: 승인 (2026-11-10, 사용자). 처음 기록(같은 날)은 사용자가 삭제한 첫 번째 추천 글(Keycloak Realm 2개·Google 신원 연결)을 따랐으나, 사용자가 기준은 **두 번째 글**이라고 바로잡았다:
  - "키클록 구성을 따를거야" / "auth 관련해서 두번째로 준 md 파일을 확인하란거였어"
  - "라이더 키클롭 안쓰는거야. 구글 소셜 로그인을 쓰는 라이더 auth 서비스를 하나 더만드는거야"
  - "지금 auth 서비스를 고객앱 쓰는 그대로 하면돼"
- 근거: `docs/architecture/auth/references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`, 확인 `docs/architecture/auth/2026-11-10-gateway-url-jwt-review.md`.
- 대체 예정(이전이 끝나면 확정 — 그 전까지 아래 ADR 은 그대로 유효): ADR-0018 결정 1~5(운영자 계정·로그인·발급 주체 auth-service → Keycloak, 결정 D 의 이전 기간에는 함께 둔다). ADR-0011·0018 대안 절의 "Keycloak 도입 안 함" 문장은 이 ADR 로 뒤집혔다(결정 아님). ADR-0011 의 서비스 신원 토큰 결정, ADR-0019(고객 Google 로그인)는 그대로(ADR-0025 는 2026-11-10 기각 — ADR-0055).

## 결정
1. **직원(백오피스·MFC 등 운영자) = Keycloak.** 서버 1개.
2. **고객 = 지금 auth-service 그대로**(Google 로그인 ADR-0019·0055, 고객 토큰). 비회원(`GUEST`)·서비스 신원(`SERVICE`, ADR-0011) 토큰도 지금처럼 auth-service.
3. **라이더 = 새 라이더 auth 서비스**(Google 소셜 로그인, 라이더 토큰 `typ=RIDER` 발급). Keycloak 을 쓰지 않는다. 라이더 업무 정보(승인·정지·운행 상태)는 지금처럼 rider-service 정본 — 라이더 auth 는 로그인·토큰만.
4. **게이트웨이 1개**, 발급자 3개(Keycloak, auth-service, 라이더 auth). 사용자 종류마다 허용 발급자·대상(`aud`)·권한을 검증하고, 맞지 않으면 거부(고객 토큰으로 라이더 API 불가). 서비스도 다시 검증하고, 업무 인가(배차 소유권·라이더 승인·센터 권한)는 서비스가 본다.
5. 서비스가 쓰는 권한 형태(`TYPE_x`·역할·센터)는 유지 — 발급자별 토큰을 이 형태로 바꾸는 일은 공통 변환기(`common-security`)가 맡는다.

## 정할 것(구현 전)
- ~~URL 구조~~ → **결정 2026-11-10(사용자 "url 말고 그 발급자 설정으로 하고")**: 경로(`/api/v1/<자원>`)는 그대로, 게이트웨이·서비스가 발급자 표로 `iss` 를 고르고 발급자 ↔ 사용자 종류 짝을 강제한다. 허용 목록·차단 목록도 이번 작업 범위에 넣는다(사용자). 구조: `docs/architecture/auth/issuer-type-pairing-and-access-lists.md`.
- **직원 주체 ID 유지**: 운영자 ID 가 여러 서비스에 기록돼 있다(mfc-capacity `workerId = 운영자 ID`, 승인 이력 처리자 등). Keycloak 기본 `sub` 는 UUID — 기존 ID 를 클레임으로 싣는 방법·계정 이전 순서.
- **라이더 가입과 업무 원문**: `Delivery-business-logic.md` §1.1 은 전화번호 인증·아이디·비밀번호·"가입 완료 → 즉시 운행 승인". 로그인은 Google 로 정했으므로 전화번호·약관·배달 정보 입력·운행 승인 시점을 어떻게 맞출지.
- 라이더 auth 서비스 이름·포트·DB, Google OAuth 클라이언트(라이더 앱용), service-map 등록(새 서비스 경계).
- Keycloak 이미지 버전 고정·DB·배치 PC·관리자 비밀 보관, `iss` 호스트 이름, 토큰 수명, 즉시 차단(퇴사·라이더 정지) 방법.
- 백오피스·MFC 앱·라이더 앱 로그인 변경 요청(pmart-clients handoff).

## 구현 전 결정(2026-11-10, 사용자 "너 권장대로")
- A. Keycloak 은 **PC 5** + PC 5 postgres 에 DB 하나(`keycloak`).
- B. Keycloak 관리자·DB 비밀번호는 `deploy/lan/init-secrets.sh` 가 무작위 생성(`.secrets/keycloak-admin-password`, `.secrets/keycloak-db-password`, 값 출력 없음, 관리 화면 로그인 때 사용자가 파일을 연다).
- C. 직원 주체 ID 는 Keycloak 사용자 속성 `pmart_operator_id` → 같은 이름 클레임. 서비스는 이 값을 주체 ID 로 쓴다(Keycloak `sub` UUID 는 쓰지 않음).
- D. 운영자 비밀번호(bcrypt)는 옮기지 않는다 — LAN 시험 계정은 Keycloak 에서 새로 정하고, 이전 동안 auth-service 운영자 로그인을 잠시 함께 둔다.
- E. 라이더 auth 서비스 = `rider-auth-service`, 포트 8510, PC 5 + PC 5 postgres DB. service-map 에 등록.
- F. 라이더 Google 로그인은 고객과 같은 방식(앱이 받은 Google ID 토큰을 서버가 Google 공개키로 확인, client secret 없음). Google Cloud 콘솔에 **라이더용 웹 클라이언트 + Android 클라이언트(패키지 `com.pmart.rider_app`, 서명 SHA-1 = PC 1 디버그 키스토어 `58:0F:75:0A:83:91:28:84:AA:ED:58:52:55:4E:E5:0F:E2:B2:CF:D9` — 고객 앱과 같은 키)**를 사용자가 만들고, 클라이언트 ID 를 알려 주면 설정에 넣는다. 고객용 웹 클라이언트와 나눠 두 앱의 Google 토큰이 섞이지 않게 한다.
- G. 라이더 가입: Google 로그인 → 전화번호·배달 지역·배달 수단·약관 입력 → **즉시 운행 승인**(업무 원문 §1.1). 전화번호는 본인인증 API 가 없어 **중복 확인만**.
- H. 차단 목록 저장소(PC 4 Redis) 장애 때는 **통과(fail-open)** — 직원 토큰 15분, 업무 상태는 서비스가 따로 확인.
- I. 관리 경로는 LAN 대역(192.168.0.0/24)만, 결제 웹훅은 토스 문서의 IP 대역 확인 뒤.
- J. LAN 전체 재배포는 작업을 모두 마친 뒤 한 번에(사용자).
- L. (2026-11-11, 사용자 "A로 하자") 직원 계정 관리는 **당분간 Keycloak 관리 화면에서 직접** 한다 — 관리자가 `pmart-staff` 에 사용자를 만들고 역할(Realm 역할)·`pmart_operator_id`(`OP-<UUID>`, 새로 매김 — 기존 계정은 auth-service 의 ID 를 그대로)·`centers` 속성을 넣는다. 백오피스의 운영자 계정 화면(auth-service `/operator-accounts`)은 이전 뒤 내린다. 계정이 늘면 백엔드가 Keycloak 관리 API 를 대신 부르는 방식으로 옮긴다(다시 결정).
- 순서: ① 발급자 표 구조(게이트웨이·`common-security`, 표에는 auth-service 만 — 동작 같음) ② 경로별 허용 목록 기록 모드 ③ Keycloak 배치·직원 로그인 ④ rider-auth-service ⑤ 차단 목록(주체 → jti → IP) ⑥ 허용 목록 차단 모드.

## 진행
- 2026-11-10 ① 발급자 표(105da93) ② 경로별 허용 목록 RECORD(204c072) ③ Keycloak 배치 준비 — `placement.conf` pc5 끝에 `keycloak`(quay.io/keycloak/keycloak:26.8.1, LAN 8180, iss `http://192.168.0.101:8180/realms/pmart-staff`), `render.sh`(DB·계정 keycloak, 비밀 `.secrets/keycloak-*`), Realm `deploy/lan/keycloak/pmart-staff-realm.json`(클라이언트 `pmart-backoffice` PKCE·`pmart-dev-cli` LAN 시험용 직접 로그인, aud `pmart-api`, 사용자 속성 `pmart_operator_id`·`centers` → 같은 이름 클레임, 접근 토큰 15분·세션 12시간·5회 실패 잠금). 이 PC 의 일회용 Keycloak 에서 가져오기·토큰 클레임 확인 — **Keycloak 은 `typ` 에 "Bearer" 를 넣는다** → 발급자 설정 `type-claim`(비우면 발급자 종류로만) 추가. LAN 배치·운영자 계정 옮기기는 사용자 결정 J 대로 마지막에.
- 2026-11-10 ④ rider-auth-service — 라이더 웹 클라이언트 ID(`293585219605-la3cj13q…`)를 사용자가 줌. Google 로그인 방식은 ADR-0055(ID 토큰 검증만, client secret·인가 코드 교환 없음). 계약 `contracts/openapi/rider-auth-service.yaml`, 서비스(로그인·결과 조회·갱신 회전·로그아웃·현재 세션·JWKS, 주체 `RD-…`, Kafka 없음), pmart-config `rider-auth-service*.yml`(LAN 서명키 `{cipher}`, 원본 `.secrets/rider-auth-signing-key.pem`), 발급자 표 `rider` 줄(LAN·로컬), 게이트웨이 라우트 `/api/v1/rider-auth/**`·공개 경로·허용 목록, `placement.conf` pc5 끝. 가입(결정 G)은 rider-service 요청 RS-2. LAN 배치는 결정 J 대로 마지막에 — 배치·확인 뒤 auth-service 줄의 RIDER(시험 라이더 토큰)를 뺀다.
- 2026-11-11 LAN 전체 재배포(결정 J, 이미지 5265276): ①~④ 와 Keycloak(pc5, 운영자 계정은 아직 auth-service) 배치. 확인 — PMart JWT aud=pmart-api(없으면 401), rider-auth-service 경로·서명 토큰 통과, Keycloak discovery 200. 발급자 표 auth-service 줄에서 RIDER 제거(pmart-config baca65e) 후 전 앱 재시작 — auth-service 서명 RIDER 토큰 401. 다음: 운영자 Keycloak 이전(D), ⑤, ⑥(RECORD 경고 로그 관찰부터).
- 2026-11-11 ③ 이어서: `pmart-staff` 에 시험 계정 `claude-dev`(운영자 ID = auth-service 와 같은 `OP-eaf58e67…`, OPERATOR_ADMIN, 비밀번호 `.secrets/keycloak-claude-dev-password`). 같은 계정의 auth-service·Keycloak 토큰으로 게이트웨이 GET 14곳 비교 — 13곳 같은 결과, `/auth/sessions/current`(auth-service 세션)만 Keycloak 토큰 401(예상). 백오피스 클라이언트 redirect 에 `http://192.168.0.40/*`(지금 백오피스 주소) 추가 — Realm 파일·실행 중 Keycloak 둘 다. 결정 L(계정 관리). 백오피스 로그인 변경 요청 `docs/handoff/clients/2026-11-11-clients-request-backoffice-keycloak-login.md`. 남은 것: 백오피스 전환 확인 뒤 auth-service 줄 OPERATOR 제거, 운영자 차단 사건(⑤, 보류) 발행 방법.

- 라이더도 Keycloak(첫 번째 글) — 사용자가 쓰지 않기로 함.
- 라이더를 지금 auth-service 에 넣기 — 사용자가 별도 서비스로 정함.
- 고객도 Keycloak — 바꿀 요구 없음.

## 결과
- 새 운영 구성요소 2개(Keycloak + DB, 라이더 auth 서비스 + DB). LAN 배치·백업·모니터링 대상이 는다.
- 발급자가 3개가 되어 게이트웨이와 전 서비스의 JWT 검증 설정이 바뀐다(`common-security` 변경 = 전 서비스 영향).
- 운영자 로그인 이전 동안 두 방식이 잠시 함께 있을 수 있다.
