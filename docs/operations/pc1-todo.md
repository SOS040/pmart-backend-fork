# PC 1 할 일 (platform — 작업 세션)

PC 1(작업 세션) 할 일을 한곳에 모은다. 다른 PC 할 일은 `other-pcs-todo.md`, 일부러 미룬 것은 `docs/business/flows/pending-user-decisions.md` "나중에 하기로 한 것", 한 일·배포 기록은 `docs/worklog/platform/YYYY-MM-DD.md`.

- 고칠 때: 끝나면 줄 앞을 `- [x]` 로 바꾸고 날짜·커밋을 붙인다. 다 끝난 절은 맨 아래 "끝난 것"으로 옮긴다.
- PR 검토·병합·PR 배포는 PR 세션 몫(이 문서에 넣지 않는다).
- 마지막 갱신: 2026-11-18.

## 1. 사용자 확인·결정 기다림
- [ ] **Docker Hub 다시 로그인**(사용자 — Docker Desktop GUI, 잠금 풀린 뒤) → 아래 LAN 배포를 한 번에:
  - employee-auth-service·hr-service — 역할·센터 섞임 막기(253090cc), HR-2 ③ 계정 직접 역할 중복(71ddc112)
  - claim-service — 고객센터 주문 취소 0.4.0(39d86a83)
  - 2026-11-18 Jib push 가 unauthorized(저장 자격 증명 무효) → 재시도로 "too many failed login attempts" 잠김. 풀릴 때까지 push·`docker login` 재시도 금지.
- [ ] **CL-2 결정** — 반품 취소 때 회수비 돌려주기 판정을 claim 조회에(`pickup.feeRefundOnCancel`) — 추천안 바로 만들기 / 목록에 두기(요청서 `service-requests/claim-service.md` CL-2, PR #161).
- [ ] **Firebase `pmart-123` 합치기**(2026-11-18) — 사용자: 고객 앱 등록·`google-services.json` 다시 받기 → PC 1: 서버 고객 키 바꾸고 notification-service 다시 띄움 → 고객 휴대폰 푸시 다시 확인.

## 2. 어제(11-17) 저녁 목록
- [x] 1. 역할·센터 섞임 막기 — 253090cc(배포는 1번 대기)
- [ ] 2. **MFC 앱 로그인 브라우저**(PC 1 — 로그인 코드, pmart-clients PR): ⓐ Chrome → 삼성 인터넷 → Firefox 순서로 열기 ⓑ Firefox 만 있는 기기에서도 결과가 앱으로 ⓒ `TokenPair.refreshToken` nullable(ADR-0059) 맞추기. **MFC 앱만**. ⓑ 는 실기기(S25, 기본 브라우저 Firefox)로 확인.
- [x] 3. 고객센터 주문 취소 — 39d86a83(배포는 1번 대기), PC 2 요청서
- [x] 4. 직원 권한 요청 검사 HR-2 — ①③④ 결정(2026-11-18), ③ 71ddc112, ① PC 2 요청서, ④ 나중(L-3)

## 3. 남은 권한 빈틈 (`docs/architecture/auth/permissions.md` §9·§10 — 고치기 전 사용자 승인)
- [ ] 게이트웨이 사용자 종류 허용 목록 `mode: RECORD` → ENFORCE(먼저 RECORD 로그로 정상 요청이 걸리는지 확인 — 모든 영역 영향)
- [ ] `/internal/**` 호출자 확인 없음(네트워크로만 막힘 — orchestration manual-actions operatorId 본문, order 내부 배송지)
- [ ] (PC 3 영역 — 요청만) MFC 재고 정정 등 역할 검사 없음, approvalRef 확인(RECORD_ONLY)

## 4. 기다리는 것(다른 쪽이 끝나면 PC 1 이 이어서)
- [ ] 푸시 FID 전환 — 앱이 `register()`(FID)로 옮길 때 PC 1 이 먼저 서버를 FCM `fid` 칸으로(회신 `handoff/clients/customer-app/2026-11-18-reply-customer-push-q6-q8.md`).
- [ ] 고객앱 레퍼런스 갭 — 고객 앱이 B-2(백엔드 없음) 중 먼저 필요한 것을 알려 오면 요청서로(`handoff/clients/customer-app/2026-11-18-notice-reference-gap-update.md`).

## 5. 사용자가 직접 확인할 것 (브라우저·실기기 — 확인했다고 하면 지운다)
1. 고객 앱 실서버: 라이더 앱으로 주문 하나 배달 → 리뷰 쓰기·사진·수정·삭제, 알림함(주문 확정 → 준비 → 출발 → 완료).
2. MFC 시뮬레이터 바코드 탭 "현장 기록"(서버는 2026-11-18 `claude-mfc-wrk` 로 200 확인 — 화면만).
3. 실기기 푸시: 라이더 앱, 고객 앱 7종(Firebase 합친 뒤 다시).
4. LOT 라벨 인쇄(`deploy/lan/seed/labels/lots/*.html`) → PDA·스캐너로 스캔.
5. 다시 올린 웹 4개(2026-11-18 `web-sync` — clients 1869c49): PR #64~#102 화면, 443·8444·8445·8446(Ctrl+F5). 올라간 버전은 `https://192.168.0.40/version.txt` 등.

## 끝난 것 (2026-11-18)
- CL-1 반품 추가 결정 9가지 LAN 배포(453dbbcf), LOT 라벨(5b1e9626), 주문서·장바구니 재고 = `shippableAvailable`(a8101448, LAN 배포), 카트 `CRT-C-01` 닫기(11-17).
- 고객 푸시 Q6·Q8 회신(ec2b7b3e), `staff_auth` 비밀번호 저장(a1733e91)·PR #163(73eaa8e7), `claude-mfc-sup` 비밀번호 파일 PC 5·PC 3.
- LAN 웹 맞추기 `lan.sh web-sync`(bfee6e07) — PR 세션이 PR 확인 때·30분마다.
- 나중에 하기로 한 것 L-1~L-7 기록(3ecb3363), 고객앱 레퍼런스 갭 B 갱신(2026-11-18).
