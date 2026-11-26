# JMeter 부하 시험 계획·인수인계 (2026-11-26, PC 1 백엔드)

- 사용자 요청(2026-11-26): "Jmeter 돌리려고하는데 로그인인증 무마, 결제 제외 — 계획부터", "결제가 토스만 없이 가능한 걸 물었어", "너가 말한대로 해주고 계획서 작성해줘. 오늘 오후쯤 다른 계정에서 인수인계해서 수행할거야. 마지막에 설치도 해줘".
- **이 문서만 읽고 이어서 돌릴 수 있게** 썼다. 이어받는 세션은 §6 순서대로 한다. 오늘 전체 인수인계는 `docs/operations/2026-11-26-pc1-session-handoff.md`(§2-3 이 이 시험).
- 상태(2026-11-27 05:11): **본 시험 10 → 50 → 100명 끝 — 모두 오류 0%·p95 53~54ms** — 결과 `docs/operations/2026-11-27-jmeter-load-test-result.md`, 할 일 §6. 결과 폴더 `D:\pmart-perf\jmeter\`.

## 1. 정한 것 (사용자 "너 추천대로" — 안 A)

| 항목 | 정한 값 | 이유 |
|---|---|---|
| 큰 부하 | **비회원 토큰 100개**로 둘러보기·검색·장바구니·배송 조회 | LAN 회원 토큰은 실제 소셜 로그인 또는 개발용 토큰(허용 목록 2명뿐·실제 계정)뿐. 비회원 토큰은 공식 `POST /auth/guest-sessions` 로 받고 계정을 만들지 않는다 |
| 로그인 | 화면·소셜 로그인만 건너뜀. **인증은 그대로**(토큰을 미리 받아 CSV) | 게이트웨이·서비스에 "검증 끄기"를 넣지 않는다(보안 약화) |
| 주문·결제 | **계정 1개로 소수만**(1분에 1건 안팎) 따로 섞는다. 결제는 토스 없이 `TEST_AUTO` | 주문마다 재고 예약·MFC 작업·배차·고객 앱 알림이 실제로 움직인다 — 수천 건이면 다른 PC 시험 데이터가 섞인다 |
| 의미 검색 | **켠 채로** | 실제와 같게. 검색어 벡터가 1.5초를 넘으면 키워드만으로 돌아간다 — 그 비율과 pc2 ollama 부하를 같이 본다. 꼬리표·벡터는 504/504 채워짐(2026-11-26 — `docs/architecture/product-search.md` §14) — 검색 결과가 실제 상태와 같다 |
| 개인화 | **없음** | 자주 산 상품은 검색 순위에 넣지 않기로(product-search.md §15) — 시나리오에 없다. ①·② 가 생기면 `GET /orders/frequent-items` 를 회원 시나리오에 더할 수 있다(회원 토큰 필요 — §3) |
| 인원 | **10 → 50 → 100명**, 단계마다 5분(램프 60초), 생각 시간 1~3초 | 멈춤 기준: 오류율 > 1% 또는 전체 p95 > 2초면 다음 단계 안 감(`summary.py` 종료 코드) |

## 2. 무엇이 준비됐나 (`deploy/lan/perf/jmeter/`)

| 파일 | 하는 일 |
|---|---|
| `guest_tokens.py` | 비회원 토큰 N개 → `out/tokens.csv`(기기 ID·토큰, git 제외 `out/`). access **1시간** — 그 안에 끝낸다. 익명 제한(IP 10/s)에 안 걸리게 0.15초 간격 |
| `pmart-guest.jmx` | 시험 계획(`gen_jmx.py` 로 만든 것 — 고칠 때는 gen_jmx.py 를 고치고 다시 만든다) |
| `gen_jmx.py` | jmx 만들기 `python -I -X utf8 deploy/lan/perf/jmeter/gen_jmx.py deploy/lan/perf/jmeter/pmart-guest.jmx` |
| `keywords.csv` | 검색어 20개(의미 검색 "빨간 라면" 포함) |
| `run.sh` | 단계 실행(메모리 4GB 확인 → 단계마다 jtl·HTML 보고서 → `summary.py` 멈춤 판정). **결과는 D 드라이브 `D:\pmart-perf\jmeter\<시각>-u<인원>\`**(사용자 2026-11-26 — C 여유 30GB, D 159GB). 바꾸려면 `OUT_DIR=/d/다른곳` |
| `summary.py` | 요청별 건수·오류%·p50·p95·상태 코드, 전체 처리량 |

JMeter: `C:\tools\apache-jmeter-5.6.3`(PC 1, 2026-11-26 설치 — §7). Java 21 은 PC 1 에 있다.

### 시나리오 (한 사람 = 스레드 하나, 반복마다 아래를 비중대로)
- 처음 한 번: 검색 하나로 상품 ID·대표 SKU 를 잡는다(이후 검색이 바꾼다).
- 둘러보기 40%: `GET /storefront/home?mfcId` · `GET /search/category-images?mfcId` · `GET /product-contents/{productId}?mfcId`
- 검색 30%: `GET /search/products?q&mfcId&size=20`(의미 검색 포함) · `GET /search/suggestions?q`
- 장바구니 20%: `POST /carts {mfcId}` → `POST /carts/{id}/items {sku, quantity 1, expectedVersion}` → `GET /carts/{id}` → `DELETE /carts/{id}/items/{itemId}`(담은 것을 바로 뺀다 — 장바구니가 쌓이지 않게). 변경은 `Idempotency-Key` 매번 새 UUID
- 배송·쿠폰 10%: `GET /delivery-options?mfcId&itemAmount=20000` · `GET /slots?mfcId&date=오늘` · `GET /coupon-campaigns`
- 공통 헤더 `Authorization: Bearer <비회원 토큰>`·`X-Source-System: perf-jmeter`·`X-Device-Id`. 센터 기본 `MFC-BJG-01`(`-JmfcId=` 로 바꿈). 주소 `https://192.168.0.40:8443`(`-Jhost`·`-Jport`).
- 비중은 JMeter Throughput Controller(백분율, 반복마다 독립) — 정확히 40:30:20:10 으로 나뉘지는 않는다(합 100%, 평균으로 맞음).

## 3. 주문·결제 소량 섞기 (아직 스크립트 없음 — 이어받는 세션이 만든다)
- 토큰: auth-service 직접 `POST http://192.168.0.40:8501/api/v1/internal/dev-customer-tokens {accountId}`(헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id`, 15분). 허용 목록(pmart-config `auth-service-lan.yml`): `CU-eae131fa-…`(PC 1 지정 고객 = **사용자 Google 계정 — 쓰지 않는다**), `CU-d58124e3-…`(**목포 MFC 시험 고객 — 이것을 쓴다**, 기본 쇼핑 주소 목포). 둘 다 실제 계정이라 주문이 그 앱 내역에 남고 알림이 간다 — **시작 전에 사용자에게 몇 건 넣을지 확인**.
- 순서(MFC 시뮬레이터 R-21 과 같음 — `docs/handoff/simulator/mfc/2026-11-19-simulator-notice-r21-dev-order.md`): 장바구니 담기 → `POST /order-drafts`(purchaseIntentId·items[cartItemId·sku·quantity]·shoppingAddressId(`GET /me/addresses`)·deliveryMode·outOfStockPreference) → 초안 `quote.quoteVersion` → `POST /order-drafts/{id}/checkouts {acceptedQuoteVersion, paymentMethod: TOSS_PAYMENTS}` → 결제 시작 결과의 pgOrderId·금액 → `POST /payment-confirmations {orderId: pgOrderId, paymentKey: "TEST_AUTO_<pgOrderId>", amount}` → `stage=APPROVED`.
- 토스는 부르지 않는다(payment `test-auto.enabled=true`, LAN 만). 그 뒤 주문 확정·MFC 작업·배차는 실제로 움직인다.
- 권장: Python 작은 스크립트(`deploy/lan/perf/jmeter/order_trickle.py`)로 1분에 1건, 부하 단계와 같은 시간만. 처음엔 **1건만** 넣어 끝까지 되는지 본 뒤.
- 넣은 주문은 끝나고 목록(주문 ID)을 남긴다 — 필요하면 고객 취소(시험 결제라 환불도 토스 없이).

## 4. 보는 것
- JMeter: `D:\pmart-perf\jmeter\<시각>-u<인원>
eport\index.html`(응답 시간 분포·처리량·오류), `summary.py` 표.
- Grafana(PC 1, `deploy/lan/monitoring`): 서비스별 `http_server_requests` p95, JVM 힙·GC, Hikari 연결, 게이트웨이 429 수(`spring_cloud_gateway…`/rate limit), Kafka 소비 지연, search `pmart_search_es_seconds`, pc2 ollama(검색 벡터 시간 초과 — search-service 로그 WARN).
- 각 PC CPU·메모리(`lan.sh status <pc>`, node exporter).
- 결과 정리: 단계마다 처리량·p95·오류%·병목 서비스·429 비율을 `docs/operations/2026-11-26-jmeter-load-test-result.md` 로.

## 5. 주의
- **검색 부하와 ollama**: 검색마다 pc2 ollama 에 검색어 벡터를 묻는다(최근 500개 검색어는 사본 — `keywords.csv` 20개라 대부분 사본에서 나온다). 그래서 이 시험은 ollama 부하를 **덜** 잰다 — ollama 한계를 보려면 검색어를 수백 개로 늘린 따로의 짧은 시험을 한다. 그 동안 search-service 로그 `query-timeout`·kNN 실패 WARN 수를 센다.
- 꼬리표 뒤 작업(2분에 4건)은 504/504 라 지금은 ollama 를 거의 쓰지 않는다 — 시험 중 상품이 바뀌면 다시 돈다.
- **공유 LAN**: 다른 PC 세션(PR 백엔드·PR 클라이언트·CDC·MFC·라이더 시뮬레이터)이 같은 환경을 쓴다. 돌리기 **전에** ListAgents 로 보이는 세션들에 "몇 시부터 몇 분, 게이트웨이 부하" 를 알리고, 배포 중인 세션이 있으면 기다린다.
- **처리율 제한**(api-gateway.yml `rate-limit`): IP 단위 익명 10/s·20, 토큰 주체 단위 20/s·40. 모든 요청에 각자의 비회원 토큰을 붙이므로 주체 단위로 걸린다. 100명 × 1~3초 생각이면 한 토큰 1/s 안팎 — 걸리지 않을 것. **429 가 나오면 그것도 결과**(설정은 바꾸지 않는다).
- 사용자 종류 허용 목록은 RECORD(막지 않음) — 비회원이 못 가는 경로는 서비스가 403.
- 장바구니 담기는 센터가 취급하지 않는 SKU 면 4xx 가 날 수 있다(검색 결과의 대표 SKU 를 쓰므로 드묾) — 상태 코드 표로 구분.
- 메모리: `run.sh` 가 4GB 아래면 미룬다. JMeter 힙 2GB(`HEAP`). flutter 시험과 동시에 돌리지 않는다.
- 운영 DB·설정은 바꾸지 않는다. 토큰·결과 jtl 은 저장소에 넣지 않는다(`out/` git 제외).

## 6. 할 일 (2026-11-27 다시 정리 — 결과 `docs/operations/2026-11-27-jmeter-load-test-result.md`)

끝난 것: 설치·도구, 다른 세션 알림, 비회원 토큰, 짧은 확인, 스크립트 결함 고침, **본 시험 10·50·100명**.

### 가. 본 시험 — 끝남 (2026-11-27 05:11)
- 10 → 50 → 100명 모두 오류 0%·p95 53~54ms(결과 문서). 고친 것: `NONE` 건너뛰기, 422 따로 세기, 토큰 스레드마다 고정(409 원인).
1. [x] 422 를 오류에서 뺀 멈춤 기준 — 사용자 2026-11-27 "422 계속빼고"(결과 §2-2).
2. [x] 한계 찾기 — 2026-11-27 끝: 게이트웨이 초당 약 260건에서 pc2 DB 연결 풀 5개 포화(결과 §5). 고칠지(풀 크기·promotion 사본·그대로)는 사용자 판단.
3. [ ] PC 1 메모리 — 04:38 끊김 원인 미확인(시작 11GB, 50·100명 동안은 11.2GB 아래로 안 내려감). 다음에도 30초 기록과 단계마다 포그라운드 실행(대기 중일 때 백그라운드 작업이 꺼질 수 있다).

### 나. 시험하다 찾은 것 확인 (결과 §3)
4. [x] "빨간 라면"·"매운 과자" 결과 0건 — 의미 검색은 동작, 코사인이 기준 0.55 바로 아래(신라면 0.542). 30개 비교 → 0.52, LAN 반영(pmart-config e56daf1·search-service 재시작, 결과 §7).
5. [x] 묶음 상품(20g×25입·12입) 1개 담기가 "최대 10개(지금 25개)"로 거절 — **규칙대로**(사용자 2026-11-27). 고치지 않음.
6. [x] 품절 상품이 검색 대표 SKU 로 나옴 — 결과의 `saleState: TEMPORARILY_SOLD_OUT` 으로 고객 앱 카드가 "일시품절" 표시·"N개 남음" 숨김(코드 확인, 화면은 안 봄). `availability` 는 재고만 봐서 AVAILABLE — 앱이 saleState 를 먼저 봐서 문제없음.

### 다. 주문·결제 소량 (§3 — 사용자 확인 뒤)
7. [x] 주문 소량 6건(사용자 Google 계정, 부하 없이 1 + 100명 중 5) — 모두 확정 약 1.1초(결과 §6). 6건 고객 취소·환불 끝(클레임 CLOSED·주문 CANCELLED).

### 라. 덧붙일 시험 (§9)
- ~~9-1 의미 검색·ollama 한계~~ — 사용자 2026-11-27 "의미검색도 시험테스트 할 목록에서 뺄게"로 뺌.
8. [ ] 9-3 급증 — 10 → 200명 몇 초 만에(토큰 200개).
- ~~9-2 오래 돌리기~~ — 사용자 2026-11-27 "오래 돌리기 시험테스트 뺄게"로 뺌.
9. [ ] 9-4 주문 몰림 — 따로 날 잡아(계정·센터·규모·뒷정리 사용자 확인 뒤).

## 7. 바뀐 이력
- 2026-11-26: 처음 작성(안 A). 사용자 "d드라이브에 쌓을순없어?" → 결과 `D:\pmart-perf\jmeter`(`run.sh` `OUT_DIR`). 의미 검색 504/504 확인, 개인화 없음 명시.
- 2026-11-27: 덧붙일 시험 기록(§9 — 의미 검색 한계·오래 돌리기·급증·주문 몰림). 기록만, 만들지 않음.
- 2026-11-27: 첫 실행 — 10명만 됨(PC 1 메모리 부족으로 끊김), 결과 `2026-11-27-jmeter-load-test-result.md`. 스크립트 `NONE` 건너뛰기, 422 업무 거절 따로 세기. §6 할 일 다시 정리.
- 2026-11-27: 50·100명 끝(오류 0%). 토큰을 스레드마다 고정(CSV 공유 → 409). 9-2 오래 돌리기 뺌(사용자).
- 2026-11-27: 9-1 의미 검색·ollama 한계 뺌(사용자). 9-1·9-2 내용은 기록으로 남김.

## 9. 덧붙일 시험 (2026-11-27 기록만 — 아직 만들지도 돌리지도 않음)
- 사용자(2026-11-27): "다른 부하테스트 할만한거 있어?" → 추천(③ 의미 검색 한계·⑤ 오래 돌리기·급증) 중 "응 덧붙이는데 1번도 하고싶어 일단 기록만해줘". 그래서 아래 넷을 기록한다. 순서는 §6 본 시험이 끝난 뒤.

### 9-1. 의미 검색·ollama 한계 (짧게) — **뺌**(사용자 2026-11-27 "의미검색도 시험테스트 할 목록에서 뺄게"). 아래는 기록으로만 둔다.
- 검색어를 수백~수천 개(겹치지 않게)로 바꾼 CSV(`keywords-wide.csv`) — 최근 500개 사본(§5)에 안 걸리게 한다. 검색 시나리오만(둘러보기·장바구니 끔), 10 → 30 → 50명, 단계 3분.
- 보는 것: search p95, 키워드만으로 돌아간 비율(search-service 로그 `query-timeout`·kNN 실패 WARN 수), pc2 ollama CPU/GPU·응답 시간.
- 만들 것: `gen_jmx.py` 에 검색 전용 계획(또는 비중 인자), 검색어 목록 만드는 방법(상품명·카테고리 조합 등).

### 9-2. 오래 돌리기 (soak) — **뺌**(사용자 2026-11-27 "오래 돌리기 시험테스트 뺄게"). 아래는 기록으로만 둔다.
- 50명, 2~4시간, §2 시나리오 그대로.
- 보는 것: 서비스별 JVM 힙·GC 추세, Hikari 활성 연결(줄지 않고 쌓이는지), ES·Kafka 디스크 증가, p95 가 시간에 따라 오르는지.
- 막히는 곳: 비회원 access 토큰 1시간 — 중간에 갱신(refresh)하거나 토큰을 다시 받아 바꿔 끼우는 방법이 필요. 장바구니는 담고 바로 빼므로 쌓이지 않는다.
- 시간: 다른 PC 세션 작업이 적은 때(밤 등) — 시작 전 알림(§5).

### 9-3. 급증 (spike)
- 10명에서 200명으로 몇 초 안에 올렸다가 다시 10명으로. jmx 의 램프 시간만 짧게(`RAMP=5` 안팎).
- 보는 것: 게이트웨이 429 수(설정은 바꾸지 않는다 — §5), 오류율, 내려간 뒤 p95 가 원래대로 돌아오는 시간.
- 비회원 토큰 200개 필요(`guest_tokens.py 200` — 0.15초 간격이라 30초쯤).

### 9-4. 주문 몰림 (사용자 "1번도 하고싶어" — 따로 날 잡아서)
- 목적: 짧은 시간에 주문이 몰릴 때(마감 직전·세일) 주문 확정 사가·재고 예약 충돌·outbox 지연·Kafka 소비 지연. 핵심은 **같은 SKU 를 여럿이 동시에 예약할 때 재고가 음수·이중 예약이 되지 않는지**.
- 결제는 토스 없이 `TEST_AUTO`(§3 순서 그대로).
- 정해야 할 것(돌리기 전 사용자 확인):
  1. **고객 계정** — 지금 개발용 고객 토큰 허용 목록은 실제 계정 2명뿐(§3). 몰림 시험은 계정이 여럿 필요 — 시험 전용 고객 계정을 여러 개 만들지, 한 계정으로 여러 주문을 낼지.
  2. **센터·재고** — 주문마다 MFC 작업·배차·라이더까지 움직인다. 시험 전용 센터(또는 SKU)를 따로 두고 재고를 일부러 적게 넣어 충돌을 만들지.
  3. **규모** — 예: 1분 안에 50·100·200건.
  4. **뒷정리** — 끝난 주문 취소·환불(시험 결제라 토스 없음), MFC 작업·배차 정리 방법.
- 보는 것: 주문 확정까지 시간 p95, 재고 예약 실패 사유 분포, 재고 원장 합계 대조(주문 전 − 확정 수량 = 주문 후), outbox 미발행 건수·지연, Kafka 소비 그룹 지연, 사가 보상(취소) 건수.
- 만들 것: 주문 넣는 스크립트(`order_burst.py` — §3 `order_trickle.py` 를 동시 실행으로 넓힌 것), 끝난 뒤 대조 쿼리.
- 다른 PC(MFC·라이더·DC 시뮬레이터) 세션에 날짜·시간을 미리 알린다 — 그 화면에 시험 주문이 보인다.

## 8. 설치 기록
- `apache-jmeter-5.6.3.zip`(Apache 공식 `dlcdn.apache.org/jmeter/binaries/`, sha512 대조) → `C:\tools\apache-jmeter-5.6.3`. 저장소 밖. 결과는 아래에 덧붙인다.
- 2026-11-26 08:41 KST 설치 끝: zip 90,631,635 바이트, sha512 일치, `jmeter.bat --version` 5.6.3. `pmart-guest.jmx` 를 0명으로 열어 "Created the tree successfully"·오류 없음 확인(요청은 보내지 않음). **실제 요청을 보내는 확인(§6-4)은 아직** — 이어받는 세션이 한다.
