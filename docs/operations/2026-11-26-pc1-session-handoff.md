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

### 2-2. 구매 기록 활용 — 자주 산 상품 ①·② + 리뷰 재구매 표시 + 백오피스 재구매율 (사용자 결정 2026-11-26)
- 사용자: "1, 2로 할건데" → "리뷰 '재구매' 표시, 백오피스 재구매율까지 반영되게 하고 싶은데 검색순위 반영은 안할게".
- **범위 = 아래 네 가지. 검색 순위 반영(③)은 하지 않는다**(검색 서비스는 바꾸지 않음 — `docs/architecture/product-search.md` §15).
- ~~아직 만들라는 말은 없다~~ → **2026-11-26 오후 사용자 "응 그거 문서로 남기고 그대로 해줘"** — 오후 세션이 보여 준 안 그대로 만든다. 정한 것:
  - 순서: **①+② → O-13 → R4**(①·O-13 은 order 계약 0.24.0 한 판, R4 는 review 0.9.0 따로 커밋).
  - O-13 기간 기준 = **(가) 첫 구매 코호트** — 기간 안에 첫 구매(위 "산 것" 정의)를 한 고객만 모으고, 그 고객이 **기간 끝까지** 산 주문 수·금액을 센다. mfcId 를 주면 그 센터 주문만으로 첫 구매·횟수를 센다. 가입일 기준은 하지 않는다(가입일은 customer 에 있음). 고객 한 명 칸(누적·첫·마지막 구매일)은 O-12 때.
  - O-13 금액 = 줄 금액 − 할인 분담 × 남은 수량 ÷ 주문 수량(top-products 와 같은 산식, 배달팁·봉투값 빼고, 반품·환불 빼지 않음).
  - R4 정의 그대로(배송 완료만 셈, 2026-11-16 전 배송 없음 — 사용자 받아들임).
  - ① 상한: windowDays 1~365(기본 180), minOrders 1~10(기본 2), limit 1~50(기본 20).
  - **끝남(2026-11-26 오후)**: 0f01f259(order 0.24.0 ①·O-13)·9f4504ac(review 0.9.0 R4) push·LAN 배포(pc2 order·review, pc4 api-gateway), LAN 확인 통과. 고객앱 요청 `docs/handoff/clients/customer-app/2026-11-26-request-frequent-items.md`, 공용 표 O-13 LAN 배포. 남은 것은 앱(그쪽 세션)·백오피스 화면(PC 2).
  - **O-12(사용자 "고객별 남은거 해줘")**: order 0.25.0 `customer-summaries`·`customer-ids` — 같은 "산 것" 정의(요청서의 승인 금액 대신, 환불 안 뺌), CS_AGENT·OPERATOR_ADMIN. push·LAN 배포(b3e1e56e, 사용자 "푸시 배포 해줘") — LAN 확인 통과. 회신 `docs/handoff/clients/backoffice/2026-11-26-reply-o12-customer-purchase-summaries.md`. 고객관리 PC 1 몫 남은 것: **CU-5**(직원 고객 목록·이름 검색 방식 정하기 — ADR-0062)·CU-6(최근 활동일).
- 공통 정의: "산 것" = 확정 주문(customer_order) 중 CANCELLED·EXPIRED 아님, 출고 전 전부 취소된 줄(`quantity − cancelled_before_ship_qty = 0`) 제외. 횟수 = **서로 다른 주문 수**(한 주문에 2개 담은 것은 1번).

| # | 무엇 | 어디 | 안 | 시간(대략) |
|---|---|---|---|---|
| ① | 자주 산 상품 목록 | order-service(PC 1) | `GET /api/v1/orders/frequent-items?windowDays=180&minOrders=2&limit=20`(CUSTOMER 본인, 숨긴 주문 제외) → `{from, items[{sku, productId, productName, optionLabel, orderCount, totalQuantity, lastPurchasedAt, imageUrl}]}`, 횟수 많은 순 → 마지막 구매 최근 순. 이름·옵션은 가장 최근 주문 사본, 사진 `Upstreams.productImages`. 글자 그대로 경로(`/orders/lookups`·`/orders/metrics` 와 같은 방식) — 게이트웨이 라우트 그대로, 허용 목록만. 계약 0.23.0 → 0.24.0, Flyway 없음 | 1~1.5시간 |
| ② | 검색창 빈칸 제안 | 고객앱(그쪽 세션) + PC 1 요청 문서 | 검색어 입력 전 화면에 ① 을 칩으로. PC 1 은 `docs/handoff/clients/customer-app/YYYY-MM-DD-request-frequent-items.md`(보낸 영역 platform)만 | 백엔드 10~20분 |
| R4 | 리뷰 "재구매" 표시 | review-service(PC 1 — 고객앱 요청 `pmart-clients docs/handoff/customer-app/2026-11-25-backend-product-page.md` R4 `repurchased`) | 리뷰 공개 조회 항목에 `repurchased`(boolean) = 작성자가 **그 상품을 서로 다른 주문 2번 이상** 받은 회원. **order 를 부르지 않고** review-service 자기 표 `review_eligibility`(배송 완료 품목 — order_id·account_id·product_id)로 쪽 하나에 한 번 집계(`count(DISTINCT order_id) ≥ 2`). 한계: 배송 완료된 것만 센다(①과 약간 다를 수 있음), review-service 가 생기기 전(2026-11-16 전) 배송은 없음 — 사용자에게 이 정의로 될지 확인 | 30분~1시간 |
| O-13 | 백오피스 재구매율 | order-service(PC 1 — 요청 `docs/architecture/service-requests/order-service.md` O-13, PC 2 #365) | 기간 안 고객의 분자·분모: 주문 1건 이상 고객 수(분모)·2건 이상(재구매)·1건만(이탈 — 사용자 결정 2026-11-23 "한 번 사고 다시는 안 산 고객 비율")·누적 구매액·평균 구매 횟수. 비율은 화면 계산(BO-28). 권한은 `OrderMetricsRules.access`(OPERATOR_ADMIN 전 센터·MFC_SUPERVISOR 자기 센터) 그대로, 기간 상한 366일 안. `/orders/metrics/customers` 같은 글자 그대로 경로. 기간 기준(가입·첫 구매·확정 시각)은 요청서대로 — 애매하면 사용자에게 | 1~2시간 |

- 같은 정의를 쓰도록 order 쪽 집계 규칙은 `domain` 에 한 곳(예: `PurchaseCountRules`)으로 두고 ①·O-13 이 같이 쓴다. R4 는 서비스가 달라 같은 정의를 review-service 에 따로 둔다(서비스 경계 — 다른 서비스 DB 접근 금지).
- 끝나면: 고객앱 회신(①·R4), 백오피스(PC 2) 회신(O-13 — 공용 표 `service-requests/README.md` O-13 상태), 일지.

### 2-3. JMeter 부하 시험
- 계획·순서: `docs/operations/2026-11-26-jmeter-load-test-plan.md` §6 그대로(다른 세션에 시간 알리기 → 비회원 토큰 100개 → 1분 확인 → 10·50·100명 → 결과 문서).
- 결과는 `D:\pmart-perf\jmeter\`(사용자 2026-11-26). 토큰은 저장소 `deploy/lan/perf/jmeter/out/`(git 제외, 1시간).
- 주문·결제 소량(목포 시험 고객 `CU-d58124e3-…`, 결제 `TEST_AUTO`)은 스크립트가 없다 — **몇 건 넣을지 사용자 확인 뒤** 만든다. 사용자 본인 Google 계정(`CU-eae131fa-…`)은 쓰지 않는다.

### 2-4. 사용자 결정 기다리는 것
- **부분 출고 승인·수동 할당**(DC 4번 나머지): 추천 = 둘 다 지금처럼(부분 출고는 피킹된 만큼 출발·잔량 RESIDUAL_CLOSED, 할당은 FEFO). CDC 시뮬 세션도 동의(일지). 사용자 답 아직.
- **DC 큰 순서 남은 것 → CDC 세션 몫**(2026-11-26 사용자 — cdc 영역, `docs/operations/pc-split.md` §1): 이 세션(platform)은 cdc-*-service 코드를 더 고치지 않는다. 남은 것(1번 cdc-inbound 입고·적치·보충 — 사용자 "뒤로 미루고" · 2번 운송 TMS · 5번 이상 알림·사건 기록 · 6번 반품·폐기·작업자 생산성·KPI · 7번 시뮬레이터 끝까지)과 부분 출고 승인·수동 할당 결정은 "CCCdc 백엔드 (PR 필수)" 세션에 넘겼다(2026-11-26 메시지). 이 세션 몫은 그 PR 의 게이트웨이 라우트·허용 목록·pmart-config·LAN 배포(병합·배포는 PR 세션). DC 화면 요청(PC 5)이 이 세션으로 오면 CDC 세션으로 돌린다.

### 2-5. 지켜볼 것
- pc2 의미 검색 꼬리표: 504 중 446 + 2분에 4건 — 오후엔 다 찼을 것. 확인 ES `products` 에서 `semanticHash` 없는 수(pc3 `http://192.168.0.222:9200`, 읽기만). search-service 로그에 `의미 검색 상품 하나 실패` 가 다시 늘면 ollama 응답 확인(`docs/worklog/platform/2026-11-26.md` 원인 기록).
- PR #511(cdc-inventory CI-1) 병합·배포는 PR 백엔드 세션 몫 — pmart-config `cdc-inventory-service-lan.yml` `product-base-url` 은 이미 들어감(5dda420). 병합되면 공용 표 `service-requests/README.md` CI-1 상태 갱신(PC 1 몫).
- PR 백엔드 알림(2026-11-26): cdc-quality 배치로 x-common extra_hosts 가 한 줄 늘어 **pc2·pc5 다음 배포 때 postgres 컨테이너가 다시 만들어질 수 있다**(데이터는 볼륨). 그 PC 배포 때 다른 서비스 ERROR 확인.

## 3. 계정·도구
- DC 백오피스 전 화면: `heeji-test`(사용자 비밀번호, CDC_SUPERVISOR·CDC01 + OPERATOR_ADMIN) 또는 `claude-cdc-sup`(비밀번호 `deploy/lan/.secrets/keycloak-claude-cdc-sup-password`, 화면에 찍지 않는다). 확인 스크립트는 `staff_auth.staff_token("claude-cdc-sup")`.
- LAN 확인 예: `deploy/lan/checks/` 아래 python(`python -I -X utf8 - "$PWD"` + `sys.path.insert`).
- JMeter `C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat`, Java 21.
