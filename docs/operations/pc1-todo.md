# PC 1 할 일 (platform — 작업 세션)

PC 1(작업 세션) 할 일을 한곳에 모은다. 다른 PC 할 일은 `other-pcs-todo.md`, 일부러 미룬 것은 `docs/business/flows/pending-user-decisions.md` "나중에 하기로 한 것", 한 일·배포 기록은 `docs/worklog/platform/YYYY-MM-DD.md`.

- 고칠 때: 끝나면 줄 앞을 `- [x]` 로 바꾸고 날짜·커밋을 붙인다. 다 끝난 절은 맨 아래 "끝난 것"으로 옮긴다.
- PR 검토·병합·PR 배포는 PR 세션 몫(이 문서에 넣지 않는다).
- 마지막 갱신: 2026-11-19.

## 1. 사용자 확인·결정 기다림
- [ ] **O-1 나머지 — 전달 실패를 주문에 어떻게 보일지**(2026-11-19 추가, order-service = PC 1): 사고·파손은 재발송 사가가 처리하지만, 고객 부재 같은 다른 실패는 주문에 아무것도 남지 않는다(`Order.shipments` 늘 빈 목록). (가) 지금처럼 — 상담원 안내 / (나) 주문 상세 배송 목록에 그 배송을 "전달 실패"로(주문 상태·돈은 그대로) — **추천 (나)**. 요청서 `service-requests/order-service.md` O-1.
- [x] Docker Hub·LAN 배포 — 2026-11-18: Jib 은 push 토큰(`registry.env`)으로, employee-auth·hr(459135d8)·claim·고객앱 갭 A 7개·api-gateway 배포, 고객 FAQ 색인(63건).
- [ ] **CL-2 결정** — 반품 취소 때 회수비 돌려주기 판정을 claim 조회에(`pickup.feeRefundOnCancel`) — 추천안 바로 만들기 / 목록에 두기(요청서 `service-requests/claim-service.md` CL-2, PR #161).
- [x] Firebase `pmart-123` 합치기 — 서버 쪽 끝(2026-11-18, pmart-config b625d04). 남은 것(클라이언트): 새 `google-services.json` 을 `apps/customer_app/android/app/` 에 두고 다시 빌드 → 고객 휴대폰 푸시 확인(직접 확인 3번).

- [ ] **Tailscale 시험 경로 내리기**(2026-11-18 — PC 1 이 192.168.0.40/32 를 tailnet 에 광고 중, 라이더 폰 LTE 위치 시험용): 시험 끝나면 `"C:\Program Files\Tailscale	ailscale.exe" up --advertise-routes=` + 관리 화면 승인 해제(사용자).

## 2. 어제(11-17) 저녁 목록
- [x] 1. 역할·센터 섞임 막기 — 253090cc, LAN 배포 11-18
- [ ] 2. **MFC 앱 로그인 브라우저**(PC 1 — 로그인 코드, pmart-clients PR): ⓐ Chrome → 삼성 인터넷 → Firefox 순서로 열기 ⓑ Firefox 만 있는 기기에서도 결과가 앱으로 ⓒ `TokenPair.refreshToken` nullable(ADR-0059) 맞추기. **MFC 앱만**. ⓑ 는 실기기(S25, 기본 브라우저 Firefox)로 확인.
- [x] 3. 고객센터 주문 취소 — 39d86a83, LAN 배포 11-18, PC 2 요청서
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
5. (새로) 고객 앱 실서버: 주문 내역 대표 상품·기간·숨기기, 장바구니 "N원 더 담기", 품절 "다음에도 적용", FAQ 화면, 배송 지연 신고, 리뷰 요청 알림(배송 완료 1시간 뒤) — 앱이 붙인 뒤.
7. (새로) 백오피스 쿠키(2026-11-18 employee-auth 0.12.1 배포): `192.168.0.40` 사이트 데이터 한 번 지우고 로그인 → 개발자 도구 Application → Cookies 에서 `__Secure-pmart_ea_csrf_…` 만료가 약 12시간 뒤인지, 431 이 다시 안 나는지.
6. 다시 올린 웹 4개(2026-11-18 `web-sync` — clients 1869c49): PR #64~#102 화면, 443·8444·8445·8446(Ctrl+F5). 올라간 버전은 `https://192.168.0.40/version.txt` 등.

## 끝난 것 (2026-11-18)
- CL-1 반품 추가 결정 9가지 LAN 배포(453dbbcf), LOT 라벨(5b1e9626), 주문서·장바구니 재고 = `shippableAvailable`(a8101448, LAN 배포), 카트 `CRT-C-01` 닫기(11-17).
- 고객 푸시 Q6·Q8 회신(ec2b7b3e), `staff_auth` 비밀번호 저장(a1733e91)·PR #163(73eaa8e7), `claude-mfc-sup` 비밀번호 파일 PC 5·PC 3.
- LAN 웹 맞추기 `lan.sh web-sync`(bfee6e07) — PR 세션이 PR 확인 때·30분마다.
- 나중에 하기로 한 것 L-1~L-13 기록, 고객앱 레퍼런스 갭 B 갱신.
- 고객앱 갭 A 8가지(리뷰 요청 알림·주문 내역 상품·기간·숨기기·장바구니 부족액·품절 다음에도·지연 신고·FAQ) + 후속 3가지(지연 신고 범위 확정·오배송 리뷰 보류·최소 주문 금액 설정 하나) — LAN 배포, FAQ 63건 색인.
- Docker Hub: Jib 저장 토큰 무효 원인 확인, 사용자 재로그인(지금 `pmart-ci-push` 와 같음).
