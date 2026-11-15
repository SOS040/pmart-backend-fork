# 백오피스(backoffice) 인수인계 (2026-11-15 작성 — **2026-11-16 PC 2 로 넘김**)

보낸 영역: platform(PC 1). 받는 영역: backoffice(PC 2). 경계는 `docs/operations/pc-split.md` §1-1(사용자 2026-11-15 "너의 추천대로").

## 0. 맡는 것·안 맡는 것

| | PC 2(backoffice) | PC 1(platform)에 남음 |
|---|---|---|
| 백엔드 | **backoffice-bff**, **case-service**(문의·컴플레인·도움요청 사건함) | **audit-service**(개인정보 열람 원장 — ADR-0062), approval-service·hr-service·employee-auth-service, 게이트웨이, 라이브러리, **배포 전부** |
| 클라이언트(pmart-clients) | `web/backoffice`, `web/hr-backoffice`(인사팀 화면), `web/core`·`web/ui`(웹 공통 — **로그인·세션·토큰 부분 제외**) | 로그인·세션·토큰 코드 전부(§1-3) |
| 정하지 않음 | `web/mfc-backoffice` 담당(PC 3 또는 PC 2) — 사용자 2026-11-16 결정 | |

- **로그인 코드는 PC 1 만**(`pc-split.md` §1-3): `web/core` 의 `employeeAuth.ts`·`session.ts`·`sessionPersistence.ts`·`tokens.ts`·`pmartFetch.ts` 갱신 부분, 웹 앱 `src/features/session`. 바꾼 PR 은 PC 1 이 돌려보낸다. 필요하면 handoff 로 PC 1 에 요청.
- 웹 공통(`web/core`·`web/ui`)은 세 웹 앱(backoffice·hr-backoffice·mfc-backoffice)이 같이 쓴다 — 바꿀 때 세 앱 빌드를 같이 본다.
- **BO-01 권한 변경·회수**: 백엔드(employee-auth·approval·hr)는 PC 1 이 만든다(2026-11-15 시작). 화면은 PC 2 몫 — PC 1 이 API 가 생기면 handoff 로 알린다.
- audit-service 계약(`/internal/pii-access-logs`)에 새 `subjectType`·항목 코드가 필요하면 PC 1 에 요청(`service-requests/`).

## 1. 먼저 읽을 것 (순서대로)
1. `docs/operations/dev-on-other-pcs.md`(방법·PR 순서), `other-pcs-todo.md`(PC 2 절), `other-pcs-docs.md`(문서 지도·직원 토큰)
2. `docs/business/BackOffice-business-logic.md` — 업무 원문 BO-01~BO-28(확정 정책, 추측 금지)
3. `docs/architecture/service-map.md` 의 backoffice-bff·case-service 절, `services/backoffice-bff/CLAUDE.md`, `services/case-service/CLAUDE.md`
4. 받은 요청: `docs/architecture/service-requests/backoffice-bff.md`(BO-12), `case-service.md`(R-3~R-7)
5. ADR: 0059(refresh 쿠키·CSRF), 0060(고객센터 취소 없음), 0061(로그아웃 전파), **0062(개인정보 원문 열람 = 사유 + audit 동기 기록)**
6. 클라이언트 handoff: `docs/handoff/clients/backoffice/`(최근 2026-11-15 파일들)

## 2. 서비스 상태 (2026-11-15)

| 서비스 | 된 것 | 안 된 것 |
|---|---|---|
| backoffice-bff(8504, LAN PC 4) | 승인함 `GET /views/approval-inbox`(2026-11-03 — 운영자 토큰을 approval-service 로 전달) | 그 밖 `/views/*`·`/commands/*` 전부 501 |
| case-service(LAN PC 4) | 고객 문의 `/inquiries`(0.2.0 — 고객 작성, CS_AGENT 답변), 라이더 컴플레인 `/rider-complaints`(0.3.0 — 기록 + `RiderComplaintRecorded` 발행, 조회 RIDER_OPS 도) | 사건함(케이스 열기·배정·종료), 사건 구독, 그 밖 501 |

## 3. 클라이언트 `web/backoffice` 상태
- 역할별 메뉴(2026-11-09 요청), 고객센터·라이더 운영 메뉴(클라이언트 fe8d2fb, LAN 빌드 index-Dyq2y9wh.js — https://192.168.0.40), 상품 메뉴는 OPERATOR_ADMIN 도.
- **직원 주문 상세는 가린 값**(order 0.4.0~0.5.0). "보기" → 사유 입력 → `POST /orders/lookups/{id}/pii-reveals` 로 바꾸는 요청이 클라이언트에 가 있다(`2026-11-15-request-order-pii-reveal.md`) — 아직 미반영.
- **delivery 직원 API 가 2026-11-15 LAN 에 올라옴**(PR #17): `GET /riders`(라이더 ID·전화 완전 일치만, 이름 검색 없음), `GET /riders/{id}`(가린 값), `POST /riders/{id}/pii-reveals`, `GET /deliveries`(직원 배송 찾기), `GET /delivery-help-requests`, 도움요청 닫기. 화면은 아직 "서버 API 준비 전" — 연결할 차례. 필드는 `docs/architecture/service-requests/delivery-backoffice-staff-apis.md` 회신 절.
- 서빙: PC 4 nginx 443(`backoffice-web`). 올리기는 PC 1 `lan.sh publish-backoffice <dist>`.

## 4. 할 일 (추천 순서)
1. **BO-12 운영자 수동 개입 — 백엔드는 PC 1 이 만들어 LAN 배포(2026-11-16)**: bff `/views/pending-work`·`/commands/order-orchestration-service/saga-requery|saga-resolve`, orchestration stuck-sagas·manual-actions. PC 2 는 화면 연결(클라이언트)·MFC 인계 거절·중지 실패 목록(나중). 아래는 결정 당시 메모 — **결정됨 2026-11-16**(OPERATOR_ADMIN 만, 버튼 "재시도"·"처리 완료(사유 필수)", 환불은 기존 절차, 알림은 목록만). 쓸 수 있는 API:
   - order `GET /fulfillment-issues`·`POST …/acknowledgements`(이행 확인 필요 — 이미 직원용)
   - order-orchestration `GET /internal/sagas`·`/internal/sagas/{id}`(조회 됨), `POST /internal/sagas/{id}/manual-actions`(**501**)
   - mfc-fulfillment `POST /internal/package-handovers/{id}/retries`(D-8), `POST /internal/stop-requests/{id}/retries`
   - 내부 경로는 게이트웨이로 안 나간다 → bff 가 서비스 간 호출로 묶어 `/views`·`/commands` 로 내놓는다. 다른 영역 서비스에 API 가 더 필요하면 `service-requests/` 에 적는다(orchestration·order 는 PC 1, mfc 는 PC 3).
   - 클라이언트에는 메뉴 먼저: "처리 대기" 메뉴에서 이행 확인 필요 목록은 바로 동작, 멈춘 주문 조치는 "준비 중".
2. **case 사건함 — PC 1 이 만들어 LAN 배포(2026-11-16, case 0.4.0)**: R-4·R-6·R-7(도움요청·전달 실패·위치 끊김·오배송 확인), 팀 공용 목록 집기·닫기. PC 2 는 화면 연결, R-3(사가·결제 상신 사건), 이관·재개·의무(501). PC 5 에 연결 요청 적어 둠(execution DE-CASE, dispatch DD-2).
3. case **R-6**(위치 끊긴 라이더 알림 — `RiderLocationStale`), **R-7**(오배송 확인 기록 → 사건, DD-2 회수 배차 앞단), **R-3**(사가·결제 상신 받기 — 케이스 유형·담당·기한은 사용자에게 질문). R-2 는 ADR-0060 으로 거둠 — 만들지 않는다.
4. 클라이언트: 주문 원문 열람 흐름, 라이더 찾기·대기 목록 연결(위 §3).

## 5. 결정 대기 (사용자 — 손대기 전에 확인)
- ~~BO-12 화면 범위·절차~~ — 결정 2026-11-16(위 §4-1, 요청서).
- ~~지원센터 역할을 따로 둘지~~ — **결정 2026-11-16(사용자): `RIDER_OPS` 가 겸한다.** 사건함의 지원센터 업무(도움요청·전달 실패·위치 끊김·오배송 확인)는 RIDER_OPS 권한으로. 사람을 나눠 운영하게 되면 그때 분리.
- ~~사건함 케이스 유형·담당 배정·처리 기한~~ — 결정 2026-11-16(`service-requests/case-service.md` "사건함 공통 결정" — 다섯 유형, 유형별 팀이 공용 목록에서 집어 감, 기한은 표시만). 기한 값은 구현 때 사용자 확인.

## 6. 규칙·도구
- PR: 포크 `backoffice/<주제>` 브랜치 → PC 1 검토·병합·배포. main push 금지. 저장소마다 PR 따로(backend·clients).
- 일지: `docs/worklog/backoffice/YYYY-MM-DD.md`(첫 날 폴더를 만든다). 클라이언트 handoff: `docs/handoff/clients/backoffice/`(첫머리 `보낸 영역: backoffice`).
- 새 화면이 고객·라이더 개인정보 원문을 보여 주면 **ADR-0062 방식**(기본 가림 + 사유 + audit 동기 기록, 기록 실패면 원문 없음). 개인정보·사유·메모는 로그 금지.
- 직원 토큰: `deploy/lan/checks/staff_auth.py`(`staff_token(이름)`). 시험 계정 `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS), `claude-dev`(OPERATOR_ADMIN) — 비밀번호 파일은 `deploy/lan/.secrets/`(PC 1 에서 복사, Git 아님).
- Gradle 제한(workers 2·Xmx2g)은 PC 2 에 이미 적용 — PC 2 는 commerce·RAG 를 돌리는 LAN PC 라 무거운 시험 중 LAN 이 느리면 PC 1 에 알린다.
