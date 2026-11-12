# ADR-0056 직원 로그인 — Keycloak 은 신원만, PMart 직원 JWT 는 employee-auth-service(Keycloak 기밀 클라이언트), 계정 발급은 hr-service + 사내 메일(@pmart.com)

- 상태: 승인 (2026-11-12, 사용자 "그 설계를 따르고 싶다" — 결정 하나하나는 아래에 사용자 말과 함께 적음). **3·4단계 LAN 완료 2026-11-12**(employee-auth-service, Keycloak 공개 주소 = 게이트웨이 HTTPS `/iam`) — 구현에서 정한 것은 `employee-auth-structure.md` 진행 절. 백오피스 OTP(D10)는 넣지 않음(사용자 2026-11-12). 아래 "옮기는 순서"가 끝날 때까지 지금 동작(ADR-0054 직원 부분)은 그대로 돈다.
- 대체: ADR-0054 결정 L(직원 계정은 Keycloak 관리 화면에서 직접)·결정 M(Keycloak 관리 기록을 auth-service 가 읽어 차단 사건 중계) 전체. ADR-0054 결정 1·C 의 일부 — 직원 **토큰 발급자**가 Keycloak 에서 employee-auth-service 로 바뀐다(Keycloak 은 직원 신원 확인으로 남는다, `pmart_operator_id` 는 Keycloak 사용자 속성으로 남되 토큰 클레임으로 쓰지 않는다). service-map 의 "auth-service = 직원 RoleGrant·Delegation·긴급권한 정본"(결정 3).
- 유지: ADR-0054 결정 2(고객 = auth-service)·3(라이더 = rider-auth-service)·4(게이트웨이 1개, 발급자 표)·5(서비스가 보는 권한 형태)·A(Keycloak PC 5)·H·I·K(차단 목록 = Kafka → 게이트웨이 Redis), ADR-0055 전체, ADR-0011(서비스 신원 토큰).
- 근거(기준 설계): `docs/architecture/auth/references/2026-11-12/pmart-keycloak-guide.html`(강의 설계를 PMart 에 맞춘 적용안), `docs/architecture/auth/references/2026-11-12/baropick_mail_iam_guide.html`(사내 메일·IAM 가이드).
- 상세 구조: `docs/architecture/auth/employee-auth-structure.md`(정본 설계 — 구조도·흐름·데이터·API·배치·비밀값·옮기는 순서).
- 관련: ADR-0054, ADR-0055, ADR-0011, ADR-0027·0029(설정 `{cipher}`), 업무 원문 `docs/business/BackOffice-business-logic.md` BO-01(운영자 등록·권한 부여·회수).

## 배경
- ADR-0054 로 직원 로그인을 Keycloak 으로 옮겼다(2026-11-11 LAN). 백오피스가 Keycloak 의 공개 클라이언트(`pmart-backoffice`, PKCE)가 되어 **Keycloak 토큰을 API 에 그대로** 쓰고, 역할·센터는 Keycloak Realm 역할·사용자 속성, 계정은 관리 화면에서 직접 만들고, 차단은 auth-service 가 Keycloak 관리 기록을 읽어 중계한다.
- 사용자가 강의 설계를 따르기로 했다(2026-11-12): Keycloak 은 신원 확인만, 로그인을 완성하는 토큰은 PMart 백엔드(Employee Auth Service, Keycloak 의 OAuth 클라이언트)가 만들고, 인사팀 요청을 받는 HR Service 가 Keycloak 에 IAM 계정을 만든다. 직원 계정은 회사 메일(`xxx@pmart.com`)이고 인증 메일은 사내 메일 서버로 받는다.
- 이렇게 하면 고객(Google → auth-service)·라이더(Google → rider-auth-service)·직원(Keycloak → employee-auth-service)이 "밖에서 신원 확인 → PMart 가 토큰 발급"으로 같은 모양이 되고, 업무 서비스는 Keycloak 토큰 모양(`typ="Bearer"`, `realm_access.roles`)을 몰라도 된다.
- auth-service 는 ADR-0054 결정 2 로 고객·비회원·서비스 신원만 남기는 방향인데, service-map 에는 직원 RoleGrant 정본이 auth-service 로 남아 있었다(사용자 지적 2026-11-12). 코드에는 RoleGrant·Delegation 기능이 아직 없고 `operator_account.roles·centers`(JSON)만 있다.

## 결정
1. **역할**: Keycloak = 인가 서버(직원 신원 확인 — 계정·비밀번호·세션·인증 메일). **employee-auth-service(새)** = Keycloak 의 **유일한 로그인 클라이언트**(기밀, 인가 코드 + PKCE, client_secret 은 서버에만) + **PMart 직원 JWT 발급자**(개인키·JWKS). **hr-service(새)** = Keycloak 관리용 기밀 클라이언트 `hr-provisioner` 로 Admin API 를 부른다(결정 16). PDA 앱·백오피스는 Keycloak 클라이언트가 아니다. 게이트웨이·업무 서비스는 PMart 직원 JWT 만 검증한다(Keycloak 토큰은 employee-auth-service 안에서만 쓰고 앱에 내려가지 않는다).
2. **로그인 흐름**: 앱이 시스템 브라우저(Custom Tabs, WebView 금지)로 employee-auth-service 시작 주소를 연다 → Keycloak 로그인 화면(게이트웨이 `/iam/**` 중계) → 콜백(employee-auth-service)에서 코드 교환·ID 토큰 검증·직원 상태 확인 → 60초·1회용·기기 확인 **로그인 티켓**을 딥링크(`pmart-mfc://login`)·백오피스 주소로 → 앱이 티켓을 PMart 직원 Access(15분)·Refresh(회전)로 바꾼다. 토큰을 주소에 싣지 않는다. 복귀 주소는 설정 허용 목록만.
3. **직원 JWT 모양** = 예전 auth-service 운영자 토큰과 같다(`iss` = employee-auth-service, `sub = OP-…`, `typ = OPERATOR`, `aud = [pmart-api]`, `roles`, `centers`). 발급자 표 `staff` 줄은 employee-auth-service 로 바뀌고 Keycloak 줄의 특수 설정(`type-claim: ''`, `subject-claim`, `roles-claim`)은 없어진다.
4. **직원 역할·권한 센터의 정본 = employee-auth-service**(D1, 사용자 "정본 너가 말한대로 확정할게"). 유효 grant·대행·긴급권한·행위 시점 검증 API 와 `GrantApplied` 발행이 auth-service 에서 옮겨 온다(서비스 경계 변경). auth-service 는 고객·비회원·서비스 신원 토큰만. hr-service 는 권한을 보기만 한다(BO-01 "인사상 상사라는 이유만으로 실행 권한을 줄 수 없다" — 고용 정보와 접근 권한 분리). Keycloak Realm 역할·`centers` 속성은 옮긴 뒤 지운다.
5. **직원 등록(hr-service, D4)**: 한 화면에서 기본 정보(이름, 메일 ID → `<id>@pmart.com`, 고용 형태 REGULAR·PART_TIME·DAILY = mfc-capacity `employmentType`, 소속(본사 부서·센터), 근무 시작일, 계약 종료일)와 **요청 권한**(역할·권한 센터·기간·근거)을 함께 받는다(사용자 "1번을 하자"). 기본 정보는 바로 효력, 요청 권한은 approval 에 권한 요청으로 자동 제출되고 승인돼야 효력(BO-01 확정 정책). 같은 사람은 새로 만들지 않고 변경 요청, 재입사는 같은 `OP-…` 다시 활성, 퇴사자 메일 ID 재사용 금지, 계약 종료일에 자동 정지, 일일크루도 사람마다 계정·사서함(2026-11-08 결정 Q2).
6. **자동 승인**(사용자 "자동 승인은 할거야", 범위 "소속 센터 현장 역할만"): 요청 역할이 `MFC_WORKER` 이고 권한 센터가 등록한 소속 센터와 같을 때만 심사 없이 승인. 기록의 승인자 = 자동 승인 규칙(ID·버전). 그 밖 역할·다른 센터는 승인자 심사. 규칙 표는 approval-service 설정.
7. **계정 발급 순서**: 사서함 → Keycloak 계정(`username = email`, 속성 `pmart_operator_id`, 첫 로그인 `UPDATE_PASSWORD`) → Keycloak 내장 비밀번호 설정 메일(`execute-actions-email`) → `EmployeeRegistered`. 단계별 진행 상태를 hr-service 에 기록하고 재시도는 멱등, 끝내 실패하면 보상(만든 사서함 비활성). 메일 본문에 비밀번호를 쓰지 않는다.
8. **퇴사·정지**는 hr-service 로만 한다(D6, 사용자 "제안대로"): Keycloak `enabled=false` + 세션 로그아웃 → `EmployeeLeft`/`EmployeeSuspended` → employee-auth-service 가 상태 변경·Refresh 무효·`SubjectBlocked` 발행 → 게이트웨이 Redis 차단 목록(ADR-0054 K 그대로). Keycloak 관리 화면에서 직접 막지 않는다 → auth-service `StaffBlockRelay`·Keycloak `pmart-auth-relay` 클라이언트를 내린다(결정 M 대체). 계정·사서함은 지우지 않고 비활성(D7 — 사서함은 계속 보존, 메일 백업은 DB 백업 방침과 함께).
9. **사내 메일**(사용자 "@pmart.com"): docker-mailserver(Postfix·Dovecot) + Roundcube 웹메일. 로컬 도메인 `pmart.com`, **바깥으로 보내지 않음**(릴레이 없음 — `pmart.com` 은 우리 소유 인터넷 도메인이 아님). 메일 포트는 LAN 에 열지 않는다. Keycloak → 메일 서버는 같은 Docker 네트워크 안 포트 25(인증·TLS 없음, Docker 네트워크만 허용), Roundcube ↔ 메일 서버는 내부 자체 서명 인증서. 이미지는 digest 고정. 직원은 IAM 비밀번호와 사서함 비밀번호를 따로 가진다.
10. **사서함 자동 만들기**(D9, 사용자 "2단계 바로 만드는것까지"): hr-service 와 함께 **`mail-provisioner` 사이드카**를 처음부터 만든다 — 내부 HTTP, 서비스 토큰 인증, 사서함 만들기·비활성화만, 메일 서버 설정 볼륨을 공유해 `setup email add` 와 같은 일을 한다. `docker.sock` 은 쓰지 않는다. 관리자 수동 명령은 장애 때 예비 수단.
11. **배치**(사용자 "적절하게 너가 배치해주고"): 직원 신원 묶음을 **PC 5** 에 — keycloak(있음, 8180), employee-auth-service **8511**, hr-service **8512**, mailserver(LAN 포트 없음), mail-provisioner(LAN 포트 없음), Roundcube **8190**. DB 는 PC 5 postgres `employee_auth_service`·`hr_service`. Keycloak 공개 주소는 게이트웨이 `/iam`(`KC_HOSTNAME=http://192.168.0.40:8080/iam`, 프록시 헤더) — `iss` 가 바뀐다. 게이트웨이는 `/iam/admin/**`·`/iam/realms/master/**` 를 막는다.
12. **ID**: 기존 운영자 13명은 지금 로그인 아이디를 메일 ID 로(`<아이디>@pmart.com`, D2), 운영자 ID 그대로. 인사 권한 역할 = **`HR_ADMIN`**(D3, 자동 승인 아님). **사번** = hr-service 가 자동으로 `P<연도>-<5자리>`(예 `P2026-00012`), 바뀌지 않고 재사용 안 함, 화면 표시·검색용 — 토큰·서비스 간 식별자는 계속 `OP-…`.
13. **LAN 시험 토큰**(D5): `pmart-dev-cli` 로 받은 Keycloak 토큰을 employee-auth-service 의 LAN 전용 교환 경로(설정으로만 켬, prod 프로필에는 경로 없음)에서 PMart 직원 JWT 로 바꾼다 — 브라우저 없는 E2E 용.
14. **공용 PDA 로그아웃**(D8): 로그아웃 때 Keycloak 세션도 끝냄, 근무(교대) 종료 시각 뒤 자동 로그아웃, 무활동 30분이면 다시 로그인. **보충(2026-11-12, 사용자 "백오피스는 sso적용하는게 나은것같아")**: 백오피스만 Keycloak SSO 를 쓴다 — Keycloak 세션을 남겨 새 탭은 비밀번호 없이, Keycloak 세션 무활동 한도는 Realm 값(12시간) 그대로, 로그아웃 때 Keycloak 세션도 끝낸다(로그아웃 응답의 Keycloak 로그아웃 주소로 브라우저 이동). PDA 는 그대로 매번 비밀번호. 구현 `employee-auth-structure.md` 진행 절.
15. 비밀값은 PMart 방식 — 원본 `deploy/lan/.secrets/`, 앱 설정은 pmart-config `{cipher}`, 컨테이너는 `render.sh` 가 넣는다(새 비밀값 목록은 상세 구조 §10).
16. **HR 관리 자격 = 세 가지**(2026-11-12, `references/2026-11-12/PMart_직원_IAM_아키텍처.html` §02-3.1 반영): ① 서비스 계정 클라이언트 **`hr-provisioner`**(Realm `pmart-staff`, 기밀·Secret, Service accounts ON, Standard flow·Direct access OFF — 사람이 이 클라이언트로 로그인하지 않음) ② 서비스 계정 사용자 `service-account-hr-provisioner` 에 `realm-management` 역할 **`manage-users`·`view-users`·`query-users` 만** — `realm-admin`·`manage-realm`·`manage-clients` 등은 주지 않음 ③ `client_credentials` 로 관리용 토큰(만료 전까지 캐시, 401 이면 한 번 다시 받아 재시도)을 받아 Admin API 에 `Bearer`. master 관리자 계정은 쓰지 않는다, employee-auth 로그인 클라이언트와 별개, Keycloak 내부 주소로만 호출(`/admin` 은 게이트웨이로 노출 안 함), Secret 원본 `.secrets/keycloak-hr-provisioner-client-secret` + pmart-config `{cipher}` 와 교체 절차(새 Secret 발급 → 설정 반영·재시작 → 옛 Secret 폐기), Realm **관리 이벤트(Admin events) 저장 켬**(감사용 — 중계용 아님). **U-11**: `manage-users` 가 역할 매핑·그룹 가입까지 허용하는지 고정 버전(26.8.1)에서 시험으로 확인한다. 최종 구조에서는 Keycloak 역할이 토큰 권한이 아니라(D1) 영향이 없지만, 전환 기간(발급자 표에 Keycloak 줄이 남아 있는 동안)에는 실제 권한이 되므로 — (B) hr-service 는 역할·그룹 매핑 API 를 부르지 않고 시험으로 확인(기본), (A) 가능하면 Keycloak 줄 제거(7단계)를 hr-service 배포 전으로 당긴다.

## 옮기는 순서
1. 이 ADR, service-map·이벤트 카탈로그 반영(이 커밋).
2. 메일 서버·Roundcube 배치, Keycloak SMTP, 기본 사서함(`no-reply`·`postmaster`).
3. employee-auth-service — 계약 → 구현 → 시험(Keycloak 컨테이너로 인가 코드 흐름) → 배치. 발급자 표에 줄을 **추가**(옮기는 동안 Keycloak 줄과 함께).
4. Keycloak 공개 주소를 게이트웨이 `/iam` 로(`iss` 변경) — 3 과 같은 배포 창.
5. hr-service + mail-provisioner — 계약 → 구현(진행 상태·재시도·보상, 자동 승인 연동, 역할 매핑 API 미사용 시험) → 배치(결정 16 U-11 — 가능하면 7단계의 Keycloak 줄 제거를 이보다 먼저). 운영자 13명 이전(메일 ID·사번·권한 값을 auth-service `operator_account`/Keycloak 에서 옮김).
6. 클라이언트 요청(`docs/handoff/clients/`): 백오피스 로그인 다시 변경(티켓 교환), mfc_app 로그인 새로, 인사팀 직원 등록 화면.
7. 전환 확인 뒤 정리 — 발급자 표 Keycloak 줄, `pmart-backoffice`·`pmart-auth-relay`, auth-service 운영자 로그인·`operator_account`·`StaffBlockRelay`, Keycloak Realm 역할·`centers` 속성. 계정 발급 흐름은 구현 커밋에서 `docs/business/flows/` 에 쓴다.

## 대안
- **토큰 보관 R-1(employee-auth 가 Keycloak 토큰 보관, 앱엔 세션 값, 게이트웨이가 요청마다 세션 → Keycloak 토큰 변환)** — `references/2026-11-12/PMart_직원_IAM_아키텍처.html` 의 추천안. 즉시 차단은 이미 있는 차단 목록(ADR-0054 K)으로 되고, 모든 업무 요청이 employee-auth 를 거쳐 단일 장애 지점이 되며 업무 서비스가 Keycloak 토큰 모양을 계속 알아야 함 — 사용자 "R-3 그대로"(2026-11-12)로 결정 3 유지. R-2(Keycloak 토큰을 앱에 넘김)도 기각.
- **지금처럼 앱이 Keycloak 공개 클라이언트(ADR-0054)**: 더 단순하지만 업무 서비스가 Keycloak 토큰 모양을 알아야 하고, 계정·차단이 관리 화면과 중계에 기댄다. 사용자가 강의 설계를 택함.
- **직원 권한 정본을 auth-service(RoleGrant 그대로)**: auth-service 를 고객용으로 정리하는 방향(ADR-0054 결정 2)과 거꾸로 — 기각. **hr-service**: 고용 정보와 접근 권한을 섞음(BO-01) — 기각. **Keycloak**: "Keycloak 은 신원만"과 맞지 않음 — 기각.
- **사서함은 관리자 수동만(1단계)**: 등록 때마다 사람이 끼어 일일크루·자동 승인 흐름이 끊김 — 예비 수단으로만.
- **메일 서버 대신 개발용 메일 수신 도구(Mailpit 등)**: 사서함 발급이 강의 설계의 요구라 쓰지 않음.

## 결과
- 서비스 51개 → 53개(employee-auth-service, hr-service), 인프라 구성요소 3개(mailserver, mail-provisioner, roundcube) — 모두 PC 5. PC 5 메모리 약 3 GB 더 씀.
- 이미 배포한 백오피스 Keycloak 직접 로그인을 다시 바꾼다(클라이언트 작업).
- 운영(TLS) 전환 때 `/iam` 중계·로그인 티켓·딥링크가 HTTPS 위에서 돌아야 한다.
- 새 사건: `EmployeeRegistered`, `EmployeeSuspended`, `EmployeeReinstated`, `EmployeeLeft`(hr-service → employee-auth-service). `SubjectBlocked`·`GrantApplied`·`DelegationStarted/Ended` 의 발행자가 auth-service → employee-auth-service(직원).
