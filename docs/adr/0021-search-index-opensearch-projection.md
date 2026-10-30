# ADR-0021: 상품 검색 색인 — OpenSearch 사건 투영, 버전 비교 멱등(inbox 없음), 콘텐츠 카드 보강

- 상태: 제안
- 날짜: 2026-10-30
- 관련: tech-stack.md(선택적 스택 "검색 서비스: OpenSearch"), ADR-0009(이벤트 계약), service-map `search-service`

## 배경
- 고객앱 우선 범위 ③ 에서 `GET /search/products` 를 실제 데이터로 채워야 했다. 기술 스택에 검색엔진은 OpenSearch(Elasticsearch 7.10 계열 오픈소스)로 정해져 있다. 사용자 결정(2026-10-30): **처음부터 OpenSearch** 로 간다(PostgreSQL 임시 검색 안 함).
- search-service 는 DB 없이 스캐폴딩돼 있어 `InboxGuard`(inbox 테이블)를 쓸 수 없다.
- `ProductContentPublished` 사건에는 상품명·이미지가 없다(productId·콘텐츠 버전·게시 시각만).

## 결정
1. **OpenSearch 2.19.1**(로컬·LAN·시험 같은 이미지), 단일 노드. 색인 `products-v1`(별칭 `products`), SKU 가격 `sku-prices-v1`(별칭 `sku-prices`). 정의는 `services/search-service/src/main/resources/opensearch/`.
2. **클라이언트 라이브러리 없이** Spring RestClient 로 REST API 를 부른다(새 의존성 없음). 공용 RestClient.Builder(사용자 토큰 전파)는 쓰지 않는다 — 검색엔진에 고객 JWT 를 보내지 않는다.
3. 한국어 부분 일치는 **1~2글자 n-gram** 분석기(내장)로 한다. nori 등 플러그인을 설치하지 않는다. 검색어의 모든 조각이 있어야 한다(AND).
4. 멱등·순서: inbox 대신 **문서마다 버전·시각을 비교하는 Painless 스크립트**로 갱신한다(상품 버전, 콘텐츠 버전, 판매 상태 시각, 가격 버전). 같은 사건 재전달과 늦게 온 옛 사건은 아무것도 바꾸지 않는다. 실패는 예외 → Kafka 재시도 → DLT(common-messaging 기본).
5. 가격은 SKU 문서에 두고 대표 SKU 가 같은 상품에 복사한다. 상품 문서를 먼저 쓰고(`refresh=wait_for`) 가격을 읽으므로, 사이에 온 가격은 가격 쪽 `update_by_query` 가 잡는다.
6. 콘텐츠 사건을 받으면 product-content-service **공개 카드 API**(`GET /product-contents?productIds=`)로 이름·대표 이미지를 보강한다(게시본만). 사건 계약은 바꾸지 않는다.
7. 카테고리 검색은 구조 id 의 조상 범위(`t01-c02` → `t01`, `t01-c02`)로 한다(계약 CategoryNode 의 구조 id). 이름 경로(`categoryPath`)는 CategoryChanged 투영 전까지 비운다.
8. storefront-service 는 DB 가 있어 같은 사건을 **PostgreSQL 투영 + InboxGuard** 로 받는다(검색엔진 불필요).

## 대안
- PostgreSQL ILIKE 로 먼저 시작: 가장 가볍지만 기술 스택과 달라 사용자가 택하지 않았다.
- opensearch-java 클라이언트: 의존성·전송 계층 추가. 필요한 API(색인 생성·스크립트 갱신·검색)가 적어 REST 로 충분.
- search-service 에 PostgreSQL 을 붙여 inbox 사용: 인프라·계정이 늘고, 색인이 이미 버전 비교로 멱등이라 얻는 것이 적다.
- 사건에 이름·이미지 추가: 발행자 계약 변경. 이미 발행된 사건(LAN 시험 데이터)은 보강되지 않는다. 필요해지면 필드 추가(호환)로 바꾼다.

## 결과
- LAN: PC4 끝에 `opensearch`(9200, 힙 512MB, 보안 플러그인 끔 — 시험망·공개 상품 정보만). 운영은 관리형/클러스터 내 여부 [팀 확인 필요], 인증·TLS 필요.
- 새 소비 그룹은 `earliest` 로 지난 사건부터 읽어 색인을 채운다. 색인을 다시 만들 때는 인덱스를 지우고 소비 그룹 오프셋을 처음으로 돌리면 된다(재색인 API 는 아직 501).
- 취급 여부(carried)는 MFC 취급 사건이 없어 불명(null). 인기·평점 정렬은 원천이 없어 관련도로 대체하고 `appliedSort` 로 알린다. 승인 속성 필터는 빈 목록.
