# 상품 검색(Elasticsearch) 성능 분석 — 2026-11-19

- 작성: PC 1 작업 세션, 2026-11-19 (사용자 요청 — 코드 수정·배포 없이 분석만)
- 대상: `services/search-service`(`ProductSearch`·`SearchIndex`·`CategoryTreeClient`·`SearchController`·`products-v5.json`), 고객 앱 검색 호출(pmart-clients — 읽기만)
- 측정 환경: LAN, 색인 `products-v5` 상품 503개(489KB, 샤드 1·복제 0), ES 8.18.0(PC 3), search-service·product-service(PC 2), 게이트웨이(PC 4). 측정은 모두 조회만, 토큰 없는 호출(인기 검색어 기록에 섞이지 않음).
- 구조 안내: `docs/architecture/product-search.md`(§11 이 이 문서를 가리킨다). **상품 상세가 느린 것은 검색과 별개**(설명 이미지 원본 디코딩) — `docs/handoff/clients/customer-app/2026-11-19-request-detail-image-performance.md`.
- 결론: **지금 규모에서 검색은 빠르다**(서버 안 약 10ms, 사용자가 겪는 30~40ms 는 대부분 네트워크). 실제 문제는 평균 속도가 아니라 **가끔 튀는 지연·장애 번짐·오류 하나**.

## 1. 호출 흐름
- 고객 앱(클라이언트 `catalog_repository.dart:89-121`) → 게이트웨이(PC 4) → search-service(PC 2) → Elasticsearch(PC 3, `products` 별칭).
- 검색 화면: `GET /search/products`(앱이 `size` 를 보내지 않아 **기본 50개**) + `GET /search/filters` + 자동완성 `GET /search/suggestions`(입력 뒤 250ms 기다림, `search_screen.dart:43`).
- **검색 결과를 받은 뒤 상품마다 가격·재고·상품 API 를 부르지 않는다**(가격 따로 조회 `_pricesFor` 는 상품 상세에서만).
- search-service 안: 요청마다 `ensureIndices()`(첫 번만 실제 일) → ES `_search` 1번 → 결과마다 `CategoryTreeClient.path()`(product-service 공개 트리 5분 사본) → 응답 변환. 첫 쪽·검색어 있음·고객/비회원 토큰이면 검색 기록을 비동기로(`SearchActivity`, 응답을 기다리게 하지 않음).

## 2. 측정 (검색어 8개: 우유·두부·라면·콜라 제로·생크림·나랑드사이다제로·미역·ㄷㅂ, mfcId MFC-SEED-01, 첫 요청 + 반복 20회)
| 측정 지점 | p50 | p95 | 첫 요청 |
|---|---|---|---|
| Elasticsearch 자체(`took`) | 2ms | 4ms | 4~14ms |
| ES 왕복(PC 2 컨테이너 안) | 4.6ms(연결 재사용) / 8ms(새 연결) | – | – |
| **search-service 전체(PC 2 컨테이너 안)** | **약 10ms** | 약 11ms | 24ms |
| search-service(PC 1 에서) | 31ms | 34ms | 20~57ms |
| 게이트웨이 경유(PC 1 에서) | 33ms | 48ms | 33~75ms |

- 서버 약 10ms 나누기: ES 호출 약 5~8ms(그중 ES 계산 1~3ms, 나머지 PC 2→PC 3 네트워크) + 카테고리 경로(캐시면 0 에 가까움) + 변환·프레임워크 약 2~4ms. 게이트웨이는 약 +2ms.
- PC 1 에서 잰 "늘 약 31ms"는 **PC 1 측정 위치의 네트워크(10~20ms 출렁임)**였다 — PC 2 안에서 다시 재서 확인.
- 깊은 쪽(from 0·200·450): ES 1ms 로 차이 없음(503개).
- 카테고리 캐시 만료 직후 첫 검색 62ms → 다음 33ms(5분마다 한 번 약 +30ms).
- **필터 API 첫 호출 283ms**(카테고리 없음 — 최상위 17개 패널을 차례로) → 다음 약 10ms. 카테고리 주면 첫 호출 17ms.
- 자동완성 첫 호출 47ms → 다음 약 10ms.
- 운영 기록(7일, Prometheus): 상품 검색 187건 평균 29.7ms, 자동완성 50건 평균 50.9ms. 2026-11-19 16:41 **1.76초 요청 1건** — 추적 표본(Zipkin)에 안 잡혀 원인 미확인.
- ES(PC 3): CPU 7%, 힙 154/768MB(20%), GC young 220회·누적 1.1초(101시간), old 0, 검색 스레드 거절 0, 누적 평균 검색 1.6ms·가져오기 0.26ms. **컨테이너 메모리 한도 1.5GiB 의 97.6%**(Logstash 도 96.7%).

## 3. 항목별 (코드 확인 / 측정 검증 구분)
| # | 항목 | 근거 | 확인 | 결과 |
|---|---|---|---|---|
| 2 | `ensureIndices()` | `SearchIndex.java:88-119`, 검색마다 `:151` | 코드 + 비용 측정 | 시작 뒤 첫 검색에 ES 확인 요청 10번(색인 5 + 별칭 5)이 차례로(PC 1 기준 합 106ms). 확인과 표시가 원자적이지 않아(`:89`·`:118`) 동시 첫 요청이 같은 확인을 반복. **검색과 무관한 색인(검색 기록 등) 하나만 문제여도 상품 검색이 실패**. |
| 3 | `track_total_hits=true` | `ProductSearch.java:103` | 측정 | 503개에서 차이 없음(1ms). 1만 건 넘게 맞을 때부터 비용. 계약의 정확한 total 에 필요 — 유지. |
| 4 | `_source` 전체 반환 | `ProductSearch.java:108` | 측정 | 필요한 칸만이면 ES 응답 22KB → 15KB(약 −32%), 시간 차이는 오차 안. |
| 5 | 카테고리 캐시 | `CategoryTreeClient.java:58-100`, `:131-145` | 코드 + 측정 | 만료되면 **검색 요청 안에서 동기로** 다시 읽음(+30ms). 동시에 만료를 본 요청이 모두 다시 읽음(한 번만 읽기 없음). **사본이 없을 때 조회가 실패하면 캐시에 남기지 않아(`:93`) 결과 50개마다 다시 호출** — product-service 장애 때 검색 한 번이 최악 50번 × 연결 제한 시간. 필터 API 는 최상위 **17개 패널을 차례로**(첫 호출 283ms 원인). |
| 6 | offset 페이지네이션 | `SearchRules.java:80-93`, `ProductSearch.java:97` | 코드 + **재현** | 503개에서 비용 차이 없음. **커서 위치 상한이 없어 `o:20000` → ES 1만 건 한도 → 500**(재현, 로그 "Unhandled exception GET /api/v1/search/products"). |
| 7 | nori·partial·compact·edge·phrase | `ProductSearch.java:239-263`, `products-v5.json`(조각 1~2글자, 앞부분 1~10) | 측정 | 조각마다 1ms. 1글자 조각(partial)이 상품이 늘면 먼저 비싸질 후보 — 지금은 아님, 품질 때문에 유지. |
| 8 | `includeNotCarried` 기본값 | 계약 `default: false`, 생성 코드 `defaultValue="false"`, `SearchController.java:79` | 코드 | **실제 동작은 계약과 같다(false).** 컨트롤러의 "값이 없으면 true" 분기와 `ProductSearch.search` 5인자 겹쳐 만든 메서드(true)는 HTTP 로 닿지 않는 코드 — 헷갈림만. |
| 9 | 앱 추가 호출 | 클라이언트 `catalog_repository.dart:89-121`, `search_screen.dart:43` | 코드 | 상품마다 추가 호출 없음. 검색 1(기본 50개) + 필터 1 + 자동완성. |
| 10 | 관측 빈틈 | `SearchIndex.java:71-83` | 코드 + 측정 | ES 호출에 지표 없음(따로 만든 HTTP 클라이언트), 서버 응답 시간 히스토그램 없어 운영 p95 불가, 추적 표본이 1.76초 요청을 놓침. |

## 4. 개선안 — 우선순위·예상 효과·검증
보존할 것: 검색 품질(쿼리·순위)·MFC 취급 필터·total·정렬·커서 모양·이벤트 색인·재색인 정책. 1순위 세 건은 서버 안에서만 바뀌고 계약은 그대로.

### 1순위 — 오류·장애 번짐
- 진행(2026-11-19): **② 카테고리 캐시 — 완료, LAN 배포 19:45(search-service c2f7ff61)**. 만료 사본은 바로 쓰고 뒤에서 한 번만 다시 읽음·사본 없는 실패 30초 기억·없는 패널 병렬(`CategoryTreeClientTest`). 배포 뒤 PC 2 안 측정: 카테고리 있는 필터 첫 호출 25~28ms(전 33~98ms), 카테고리 없는 필터는 재시작 직후 한 번 0.59초 뒤 약 20ms(전 첫 호출마다 194ms), 카테고리 상품 28~49ms.
- 진행(2026-11-19 밤, 사용자 "1순위 항목부터 처리해줘"): **① 커서 상한·③ 색인 확인 분리 — 구현·시험 통과**(`SearchRulesTest`·`SearchFlowIT` o:20000 → 400). LAN 배포는 승인 뒤. 함께: `SearchActivityIT.keepsOnlyLatestTen` 이 고치기 전 코드에서도 실패 — 20ms 간격 12번 기록이 ES 새로 고침(1초) 한 번 안에 몰려 정리가 안 보던 것(설계상 다음 기록 때 정리) → 시험을 새로 고친 뒤 한 번 더 기록하고 보게 고침.
1. **커서 위치 상한**: 1만 건 넘는 위치는 400, 그 너머로는 다음 커서를 주지 않는다(커서는 앱이 내용을 모르는 값이라 계약 변경 없음).
   - 효과: 500 이 없어진다.
   - 검증: `SearchRules` 단위 시험(상한·경계) + 통합 시험(창 밖 커서 400, 마지막 쪽 다음 커서 없음).
2. **카테고리 캐시 한 번만·미리 갱신**: 한 번에 하나만 다시 읽기, 만료 전에 뒤에서 갱신, 실패도 짧게 캐시, 필터 패널은 한꺼번에(병렬).
   - 효과: 5분마다 +30ms·필터 첫 호출 283ms 가 사라짐(약 10ms 로), product-service 장애가 검색 지연으로 번지지 않음.
   - 검증: 단위 시험(동시 만료 때 호출 1번, 사본 없을 때 실패도 1번) + WireMock 으로 product-service 지연·장애 흉내 낸 통합 시험(검색은 바로, 경로는 이전 사본 또는 빈 목록).
3. **`ensureIndices` 분리**: 서비스 시작 때 한 번(동시 실행 막음), 상품 검색은 상품 색인만 확인.
   - 효과: 첫 요청 약 100ms 제거, 검색 기록 색인 문제로 상품 검색이 실패하지 않음.
   - 검증: 통합 시험 — 검색 기록 색인이 없어도 상품 검색 200, 동시 첫 요청 때 ES 확인 요청 수.

### 2순위 — 관측
4. ES 호출 지표(측정이 되는 클라이언트 또는 타이머) + search-service 응답 시간 히스토그램 → 운영 p95, ES 시간·서비스 시간 분리, 다음 1.76초 건 원인 추적.
5. ES·Logstash 컨테이너 메모리(97%) — 한도 올리기 또는 힙과 비율 다시 맞추기. 그 전에 메모리 부족 강제 종료 기록 확인(배포 쪽).

### 3순위 — 지금 규모에서는 효과 작음
6. `_source` 필요한 칸만: ES 응답 −32%, 시간 효과는 거의 없음.
7. 앱 첫 쪽 `size` 50 → 20(고객 앱 몫): 응답·대표 이미지 수가 줄어 화면이 빨라짐.
8. `track_total_hits` 상한·`search_after`·1글자 조각 다시 보기: 상품 수천~수만 개 때 다시 잰다(지금 바꾸면 total·정렬·커서 보존 위험만).
9. `includeNotCarried` 쓰지 않는 분기 정리(동작 그대로).

## 5. 다시 재는 법
- 측정 스크립트(로컬 사본, 저장소 밖): ES 직접(`http://192.168.0.222:9200/products/_search`)·search-service 직접(`http://192.168.0.34:8105/api/v1/search/products`)·게이트웨이 세 층, 검색어 8개 × 반복 20, 변형(track_total_hits·_source·size·쿼리 조각), from 0·200·450, 카테고리 캐시 만료(5분 20초 대기) 뒤 첫 요청.
- **서비스 자체 시간은 PC 2 컨테이너 안에서 잰다**(`docker exec -i pmart-lan-search-service-1 sh -s` 로 curl) — PC 1 에서 재면 네트워크 출렁임이 섞인다.
- 운영: Prometheus `http_server_requests_seconds_*{application="search-service"}`(지금은 평균·최대만), ES `_nodes/stats`(검색 누적·GC·스레드 거절), PC 3 `docker stats`.
