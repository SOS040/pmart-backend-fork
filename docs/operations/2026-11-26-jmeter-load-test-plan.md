# JMeter 부하 시험 계획·인수인계 (2026-11-26, PC 1 백엔드)

- 사용자 요청(2026-11-26): "Jmeter 돌리려고하는데 로그인인증 무마, 결제 제외 — 계획부터", "결제가 토스만 없이 가능한 걸 물었어", "너가 말한대로 해주고 계획서 작성해줘. 오늘 오후쯤 다른 계정에서 인수인계해서 수행할거야. 마지막에 설치도 해줘".
- **이 문서만 읽고 이어서 돌릴 수 있게** 썼다. 이어받는 세션은 §6 순서대로 한다. 오늘 전체 인수인계는 `docs/operations/2026-11-26-pc1-session-handoff.md`(§2-3 이 이 시험).
- 상태(2026-11-26 오후): 도구·설치 끝, **실제 요청은 한 번도 안 보냄**. 결과 폴더 `D:\pmart-perf\jmeter\`.

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

## 6. 이어받는 세션이 할 순서
1. `git pull`, 이 문서 읽기. `C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat --version` 확인.
2. 다른 세션에 시험 시간 알리기(§5).
3. `python -I -X utf8 deploy/lan/perf/jmeter/guest_tokens.py 100` → `비회원 토큰 100/100`.
4. 짧은 확인: `DURATION=60 RAMP=10 sh deploy/lan/perf/jmeter/run.sh 10` → summary 표에서 모든 요청이 2xx 인지(4xx·5xx 면 경로·본문을 고친 뒤 `gen_jmx.py` 다시).
5. 본 시험: `sh deploy/lan/perf/jmeter/run.sh`(10 → 50 → 100, 멈춤 기준 넘으면 거기서 끝).
6. (선택, 사용자 확인 뒤) 주문 소량 §3.
7. 결과 문서 §4 + 작업 일지 `docs/worklog/platform/YYYY-MM-DD.md`.

## 7. 바뀐 이력
- 2026-11-26: 처음 작성(안 A). 사용자 "d드라이브에 쌓을순없어?" → 결과 `D:\pmart-perf\jmeter`(`run.sh` `OUT_DIR`). 의미 검색 504/504 확인, 개인화 없음 명시.

## 8. 설치 기록
- `apache-jmeter-5.6.3.zip`(Apache 공식 `dlcdn.apache.org/jmeter/binaries/`, sha512 대조) → `C:\tools\apache-jmeter-5.6.3`. 저장소 밖. 결과는 아래에 덧붙인다.
- 2026-11-26 08:41 KST 설치 끝: zip 90,631,635 바이트, sha512 일치, `jmeter.bat --version` 5.6.3. `pmart-guest.jmx` 를 0명으로 열어 "Created the tree successfully"·오류 없음 확인(요청은 보내지 않음). **실제 요청을 보내는 확인(§6-4)은 아직** — 이어받는 세션이 한다.
