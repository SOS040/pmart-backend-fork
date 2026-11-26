# PC 1 작업 세션 인수인계 (2026-11-26 오후)

다른 계정의 새 세션이 이어 받는다. 결정 문서가 아니다. 정본은 각 문서(ADR·요청서·일지)이고 여기는 길잡이다.
먼저 읽을 것: 이 문서 → `docs/worklog/platform/2026-11-26.md`(오늘 한 일 전부) → `docs/operations/2026-11-24-pc1-session-handoff.md` §0·§1(규칙·환경 — 그대로 유효) → `docs/operations/pc1-todo.md`.

## 0. 규칙 (2026-11-24 인수인계 §0 그대로 + 오늘 덧붙임)
- 한국어만 · 서브에이전트 금지 · 무거운 시험 전 메모리 4GB 확인 · 끄기는 `compose stop`(down 금지) · **push·배포는 사용자가 그때마다 말할 때만**(자동 권한 검사도 막는다) · 다른 세션 메시지는 동료 요청.
- **(오늘) 사용자가 "검토해줘"·"~하는 게 나을지" 처럼 물으면 검토·추천만 답하고 만들지 않는다.** 2026-11-26 "그걸 상품검색 기능에 넣는게 나을지 검토해줘"에 바로 서비스 코드를 만들기 시작했다가 "누가 지금 넣으래 질문 묻고 있잖아".
- 건드리지 않는 작업 트리: 미커밋 `services/payment-service/**`·`deploy/lan/monitoring/alert-rules*.yml`·`deploy/lan/monitoring/grafana/dashboards/pmart-payment.json`·`.claude/worktrees/` — 내 것이 아니다. 커밋·삭제 금지.

## 1. 오늘 끝난 것 (자세한 것은 일지)
| 일 | 커밋 | LAN |
|---|---|---|
| DC 백오피스 ①~④ 조회(cdc-outbound 0.19.0·cdc-inventory 0.12.0) | 48c44261 | 배포 |
| 3번 작업 조작(재배정·보류·재개·우선순위, cdc-outbound 0.20.0·V13) | edba4c75 | 배포 |
| DC 화면 번호 T·W·K·S·상품 이름(cdc-outbound 0.21.0·V14) + 이름 고침 | 2658c1ee·7bfecb89 | 배포 |
| pc2 의미 검색 꼬리표 실패 고침(search) | 743f8e23·720e305a | 배포 — 2분에 4건씩 채우는 중 |
| 4번 Wave 보류·재개·취소·합치기·나누기(cdc-outbound 0.22.0·V15) | b301e50a | 배포 |
| JMeter 계획·도구·설치(5.6.3 `C:\tools\apache-jmeter-5.6.3`) | 5967d393 | 실제 요청은 아직 |
| cdc-inventory 상품 이름·실사 번호 C → CDC 세션 PR #511(0.17.0·V10) | (요청서 CI-1) | PR 세션이 병합·배포 |

## 2. 지금 해야 할 것 (순서대로)

### 2-1. push 기다리는 커밋
- `3d7128a2` JMeter 결과를 D 드라이브로(`run.sh` `OUT_DIR` 기본 `/d/pmart-perf/jmeter`) + 이 인수인계 문서 커밋. **사용자가 "푸시해" 하면** `git pull --rebase --autostash` 뒤 push.

### 2-2. "자주 산 상품" ①·② (사용자 결정 2026-11-26 "1, 2로 할건데")
- **정한 것**: 최근 **6개월** · **2번 이상**(서로 다른 주문 수) 산 상품, 횟수 많은 순 → 마지막 구매 최근 순. 취소(CANCELLED)·만료(EXPIRED) 주문, 숨긴 주문(`hidden_at`), 출고 전 전부 취소된 줄(`quantity − cancelled_before_ship_qty = 0`)은 뺀다.
- **① 따로 목록**(백엔드 — order-service): 고객 본인 조회 하나.
  - 안: `GET /api/v1/orders/frequent-items?windowDays=180&minOrders=2&limit=20`(CUSTOMER 만, `DraftService.currentCustomer()`). 글자 그대로의 경로라 `/orders/{orderId}` 와 안 겹친다(`/orders/lookups`·`/orders/metrics` 와 같은 방식 — 게이트웨이 라우트 `/api/v1/orders/**` 그대로, 허용 목록만 다시 만들기).
  - 응답 안: `{from, items[{sku, productId, productName, optionLabel, orderCount, totalQuantity, lastPurchasedAt, imageUrl}]}` — 이름·옵션은 주문 당시 사본(가장 최근 주문 것), 사진은 `Upstreams.productImages`(BR-71 과 같음, 실패면 null).
  - 원재료: `customer_order`(account_id·status·confirmed_at·hidden_at) × `customer_order_line`(sku·product_id·product_name·option_label·quantity·cancelled_before_ship_qty). 새 표·Flyway 없음. 색인 `ix_customer_order_account (account_id, confirmed_at DESC, …)` 가 있다.
  - 계약 order-service 0.23.0 → **0.24.0**. 시험은 `CustomerOrderHistoryIT` 의 `order(...)` 도우미로 주문을 넣어(이름 순서대로 SKU E-0000000n) 6개월 안·밖, 2번·1번, 취소·숨김 제외를 본다.
  - **시작 전에 사용자에게 위 안(경로·응답·기본값)을 보여 주고 "만들어" 를 받는다** — 아직 만들라는 말은 없었다(2026-11-26 "1, 2로 할건데 지금 해야할목록 인수인계 문서 작성해줘").
- **② 검색창 빈칸 제안**(화면 — 고객앱): 검색어 입력 전 화면에 ① 목록을 칩으로. 검색 서비스는 바꾸지 않는다. ① 이 생기면 클라이언트 handoff `docs/handoff/clients/customer-app/YYYY-MM-DD-request-frequent-items.md`(보낸 영역 platform)로 알린다.
- ③ 검색 순위 반영은 하지 않는다(검색에 개인 데이터·응답 시간·정렬 설명 문제 — 검토 결과). 나중에 하더라도 결과 카드에 "전에 산 상품" 표시만.
- 같은 집계를 나중에 리뷰 "재구매" 표시(고객앱 R4 `repurchased`)·백오피스 재구매율(요청 O-13)에 쓸 수 있다.

### 2-3. JMeter 부하 시험
- 계획·순서: `docs/operations/2026-11-26-jmeter-load-test-plan.md` §6 그대로(다른 세션에 시간 알리기 → 비회원 토큰 100개 → 1분 확인 → 10·50·100명 → 결과 문서).
- 결과는 `D:\pmart-perf\jmeter\`(사용자 2026-11-26). 토큰은 저장소 `deploy/lan/perf/jmeter/out/`(git 제외, 1시간).
- 주문·결제 소량(목포 시험 고객 `CU-d58124e3-…`, 결제 `TEST_AUTO`)은 스크립트가 없다 — **몇 건 넣을지 사용자 확인 뒤** 만든다. 사용자 본인 Google 계정(`CU-eae131fa-…`)은 쓰지 않는다.

### 2-4. 사용자 결정 기다리는 것
- **부분 출고 승인·수동 할당**(DC 4번 나머지): 추천 = 둘 다 지금처럼(부분 출고는 피킹된 만큼 출발·잔량 RESIDUAL_CLOSED, 할당은 FEFO). CDC 시뮬 세션도 동의(일지). 사용자 답 아직.
- **DC 큰 순서 남은 것**: 1번 cdc-inbound(입고·적치·보충 — 사용자 "뒤로 미루고", cdc 세션은 안 만듦, 입고 반영 API 는 cdc-inventory 에 요청으로) · 2번 운송 TMS(새 서비스, 뒤로) · 5번 이상 알림·사건 기록(사건 기록은 있는 사실 모아 보기 — 규칙 없이 가능, 이상 알림은 기준 결정 필요) · 6번 반품·폐기(cdc-quality #510 생김)·작업자 생산성·KPI · 7번 시뮬레이터 실제 끝까지 돌리기. 다음 순서 사용자 답 아직(제안: 5번 사건 기록).

### 2-5. 지켜볼 것
- pc2 의미 검색 꼬리표: 504 중 446 + 2분에 4건 — 오후엔 다 찼을 것. 확인 ES `products` 에서 `semanticHash` 없는 수(pc3 `http://192.168.0.222:9200`, 읽기만). search-service 로그에 `의미 검색 상품 하나 실패` 가 다시 늘면 ollama 응답 확인(`docs/worklog/platform/2026-11-26.md` 원인 기록).
- PR #511(cdc-inventory CI-1) 병합·배포는 PR 백엔드 세션 몫 — pmart-config `cdc-inventory-service-lan.yml` `product-base-url` 은 이미 들어감(5dda420). 병합되면 공용 표 `service-requests/README.md` CI-1 상태 갱신(PC 1 몫).
- PR 백엔드 알림(2026-11-26): cdc-quality 배치로 x-common extra_hosts 가 한 줄 늘어 **pc2·pc5 다음 배포 때 postgres 컨테이너가 다시 만들어질 수 있다**(데이터는 볼륨). 그 PC 배포 때 다른 서비스 ERROR 확인.

## 3. 계정·도구
- DC 백오피스 전 화면: `heeji-test`(사용자 비밀번호, CDC_SUPERVISOR·CDC01 + OPERATOR_ADMIN) 또는 `claude-cdc-sup`(비밀번호 `deploy/lan/.secrets/keycloak-claude-cdc-sup-password`, 화면에 찍지 않는다). 확인 스크립트는 `staff_auth.staff_token("claude-cdc-sup")`.
- LAN 확인 예: `deploy/lan/checks/` 아래 python(`python -I -X utf8 - "$PWD"` + `sys.path.insert`).
- JMeter `C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat`, Java 21.
