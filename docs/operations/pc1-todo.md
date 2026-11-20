# PC 1 할 일 (platform — 작업 세션)

PC 1(작업 세션) 할 일을 한곳에 모은다. 다른 PC 할 일은 `other-pcs-todo.md`, 일부러 미룬 것은 `docs/business/flows/pending-user-decisions.md` "나중에 하기로 한 것", 한 일·배포 기록은 `docs/worklog/platform/YYYY-MM-DD.md`.

- 고칠 때: 끝나면 줄 앞을 `- [x]` 로 바꾸고 날짜·커밋을 붙인다. 다 끝난 절은 맨 아래 "끝난 것"으로 옮긴다.
- PR 검토·병합·PR 배포는 PR 세션 몫(이 문서에 넣지 않는다).
- 마지막 갱신: 2026-11-21.

## 0. 2026-11-21 정리 — 사용자 "9시 이후에 할게"
- [ ] **LAN 행사 승인 2건(사용자)** — 백오피스 승인 화면, MARKETING·OPERATOR_ADMIN: 배차 불가 보상 2,000원 `AP-477d415d-…`(행사 `PRM-f1be8d6d-…`), 가입 축하 3,000원 `AP-5b307c83-…`(`PRM-45734b27-…`). 내가 다른 시험 계정으로 승인하는 것은 자동 모드가 막음(감독 우회). 승인 뒤 PC 1: 행사 RUNNING·대기 보상 발급·"보상 쿠폰이 도착했어요" 알림 확인.
- [x] **처리방침 가입 쿠폰 지급 기록 기간** — 2026-11-21 사용자 "추천대로": 지급 후 5년(처리방침 2026-11-21.2 판, promotion `signup-grant-retention` P1826D 매일 04:50 파기).
- [ ] **RAG 채팅 14일 자동 삭제 켜기** — PC 4 가 `PolicyQuestionFlowIT` 시험을 고친 뒤 pmart-config `pmart.assistant.answers.retention: 14d` → 켜면 프론트 백오피스 세션에 알림(#339 화면 안내 문구가 이것을 말함).
- [ ] **결제 모니터링(Grafana) 마무리** — 2026-11-20 초안(지표·경보·대시보드), 커밋·시험·배포 안 함(작업 트리에 그대로).
- [ ] 고객 앱 회신 기다림 — 쿠폰·할인가·BR-71 LAN 실기기 확인, 쿠폰 알림 목적지(쿠폰함) 필요 여부(`handoff/clients/customer-app/2026-11-21-reply-coupons-questions.md`).
- [ ] 나중(ADR-0082 범위 밖): 할인가 기준 가격순·할인율순 정렬, 상품 쿠폰·1+1.

## 1. 사용자 확인·결정 기다림
- [x] **O-1 전달 실패 표시** — 2026-11-21 사용자 "추천대로"(나): 이미 동작(2026-11-19 BR-61 — 배송 목록 FAILED). 코드 변경 없음.
- [x] Docker Hub·LAN 배포 — 2026-11-18: Jib 은 push 토큰(`registry.env`)으로, employee-auth·hr(459135d8)·claim·고객앱 갭 A 7개·api-gateway 배포, 고객 FAQ 색인(63건).
- [x] **CL-2** — 2026-11-21 사용자 "추천대로": claim 0.12.0 `pickup.feeRefundOnCancel`(REFUND·NO_REFUND·AGENT_CHOOSES·null).
- [x] Firebase `pmart-123` 합치기 — 서버 쪽 끝(2026-11-18, pmart-config b625d04). 남은 것(클라이언트): 새 `google-services.json` 을 `apps/customer_app/android/app/` 에 두고 다시 빌드 → 고객 휴대폰 푸시 확인(직접 확인 3번).

- [ ] **Tailscale 시험 경로 내리기**(설정·계정 바꾸기·내리기 방법 `docs/operations/tailscale-lte-test-setup.md`, 2026-11-19 — 사용자가 계정 바꾸는 중)(2026-11-18 — PC 1 이 192.168.0.40/32 를 tailnet 에 광고 중, 라이더 폰 LTE 위치 시험용): 시험 끝나면 `"C:\Program Files\Tailscale	ailscale.exe" up --advertise-routes=` + 관리 화면 승인 해제(사용자).

- [x] **CL-5 쿠폰** — 2026-11-21 ADR-0082 5단계(a0d2c884) + 쿠폰 도착 알림(3404d76b) + MFC 백오피스 DC-1(claim 0.11.0 c5a4fc54). 남은 것은 아래 §0 행사 승인.

- [ ] **상품 검색 개선**(2026-11-19 분석 — `docs/operations/2026-11-19-search-performance-analysis.md`, 사용자 "할일목록에 추가" — 시작은 사용자 지시 뒤). 지금 규모에서 검색은 빠르다(서버 약 10ms) — 고칠 것은 오류·튀는 지연·장애 번짐:
  - **다음 계획(2026-11-19 밤): `docs/operations/2026-11-19-search-next-plan.md`** — 사용자 선택 5(별점·리뷰 정렬)·6(인기순)·7(동의어·오타), 순서 11(앱 쪽 크기 20, 요청서) → 5+6(products-v6 한 번에) → 7. 시작 전 결정은 그 문서 끝. ④ LAN 배포는 사용자가 나중에.
  - 1순위 ~~① 커서 위치 상한~~(2026-11-19 밤 구현) ~~② 카테고리 캐시 한 번만·미리 갱신~~(**2026-11-19 완료·배포 c2f7ff61** — 사용자 "카테고리 들어갈 때도 느려") ~~③ `ensureIndices` 서비스 시작 때 한 번·상품 색인만(검색 기록 색인 문제로 상품 검색 실패 가능) — 계약 변경 없음 — ③도 2026-11-19 밤 구현(배포는 승인 뒤)~~
  - 2순위 ~~④ ES 호출 지표·응답 시간 히스토그램~~(2026-11-19 밤 구현) ⑤ PC 3 ES·Logstash 메모리 97% — 확인함(OOM 없음·힙 밖 캐시). **2026-11-21 사용자 "추천대로": 올리지 않음**(OOM·상품 수 증가 때 다시)
  - 3순위 ⑥ `_source` 칸 제한 ⑦ 앱 첫 쪽 size 50→20(고객 앱 몫) ⑧ track_total_hits·search_after·1글자 조각은 규모 커지면 다시 ⑨ includeNotCarried 쓰지 않는 분기 정리

- [ ] **홈 화면 프로모션**(2026-11-20) — 범위 정함(사용자 "탭 + 배너·테마", ADR-0075), 탭·배너·테마 구현·LAN 배포(6e2f6d8a, 예시 편성 layoutVersion 1). 탭 정책값 **확정 2026-11-21 사용자 "추천대로"** — 신상품 14일·랭킹 30위(LAN 값 그대로). 남은 것: 백오피스 편성 화면·고객 앱 배너. 옛 메모: 1 랭킹·신상품 탭 → 2 일반 배너·테마(BO-05) → 3 할인·쿠폰(1차 제외 — 사용자 결정) 중 무엇부터인지. 지금 홈 상태·근거: `docs/operations/2026-11-20-pending-identity-switch-and-home-promotion.md` §2.

## 2. 어제(11-17) 저녁 목록
- [x] 1. 역할·센터 섞임 막기 — 253090cc, LAN 배포 11-18
- [ ] 2. **MFC 앱 로그인 브라우저**(PC 1 — 로그인 코드, pmart-clients PR): ⓐ Chrome → 삼성 인터넷 → Firefox 순서로 열기 ⓑ Firefox 만 있는 기기에서도 결과가 앱으로 ⓒ `TokenPair.refreshToken` nullable(ADR-0059) 맞추기. **MFC 앱만**. ⓑ 는 실기기(S25, 기본 브라우저 Firefox)로 확인.
- [x] 3. 고객센터 주문 취소 — 39d86a83, LAN 배포 11-18, PC 2 요청서
- [x] 4. 직원 권한 요청 검사 HR-2 — ①③④ 결정(2026-11-18), ③ 71ddc112, ① PC 2 요청서, ④ 나중(L-3)

## 3. 남은 권한 빈틈 (`docs/architecture/auth/permissions.md` §9·§10 — 고치기 전 사용자 승인)
- [ ] 게이트웨이 사용자 종류 허용 목록 `mode: RECORD` → ENFORCE — **2026-11-21 사용자 "추천대로"(기록 확인 → 걸린 것 없으면 전환 → 하루 지켜보기)**: 로그(es-logs 3일치) 53건 — 실제 경로의 NOT_LISTED·TYPE_NOT_ALLOWED 는 그 뒤 허용 목록 재생성(마지막 4c5fdec4, 11-21 00:26)으로 모두 고쳐졌고, `/control`·`/bff`·`/tracking` 등은 라우트에 없는 경로(어차피 404). 마지막 재생성 뒤 깨끗한 시간이 밤 7시간뿐이라 **낮 하루(11-21 업무 시간) 경고 0 이면 전환**. 확인: `curl es-logs:9200/pmart-logs-*/_search` match_phrase "user-type allowlist" 이후 시각. 전환 뒤 새 경로 PR 이 허용 목록 재생성을 빠뜨리면 바로 403 — PR 세션 점검 항목.
- [ ] `/internal/**` 호출자 확인 없음(네트워크로만 막힘 — orchestration manual-actions operatorId 본문, order 내부 배송지)
- [ ] (PC 3 영역 — 요청만) MFC 재고 정정 등 역할 검사 없음, approvalRef 확인(RECORD_ONLY)

## 4. 기다리는 것(다른 쪽이 끝나면 PC 1 이 이어서)
- [ ] 푸시 FID 전환 — 앱이 `register()`(FID)로 옮길 때 PC 1 이 먼저 서버를 FCM `fid` 칸으로(회신 `handoff/clients/customer-app/2026-11-18-reply-customer-push-q6-q8.md`).
- [ ] 고객앱 레퍼런스 갭 — 고객 앱이 B-2(백엔드 없음) 중 먼저 필요한 것을 알려 오면 요청서로(`handoff/clients/customer-app/2026-11-18-notice-reference-gap-update.md`).

- [x] **휴대폰 확인 스위치 + 처리방침** — 2026-11-20 켬(require-identity·처리방침 2026-11-20), 2026-11-21 처리방침 2026-11-21 판(가입 쿠폰 지급 기록).

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
