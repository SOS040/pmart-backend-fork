# search-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `search-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/search-service.yaml` → `openApiGenerate` 가 `com.pmart.search.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. **DB 가 없어 InboxGuard 대신 색인 문서의 버전 비교로 멱등**(ADR-0021).

## 패키지
`com.pmart.search.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- DB 없음. 색인 정의(설정·매핑)는 `src/main/resources/elasticsearch/*.json`(LAN 은 es-init 이 같은 파일로 만든다). 매핑을 바꾸면 새 색인(`-v3`)을 만들고 별칭을 옮긴다(적용된 색인 매핑을 고치지 않는다) — `SearchIndex.INDICES` 도 같이.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:search-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:search-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8105.

## 검색 색인 (ADR-0031 — 2026-11-03 OpenSearch → Elasticsearch)
- **Elasticsearch 8.18.0 + analysis-nori**(주소 pmart-config `pmart.search.elasticsearch-url`). RestClient 로 REST 호출 — 공용 Builder(사용자 토큰 전파)를 쓰지 않는다. **이 서비스는 조회만** 한다.
- **색인은 Logstash**(`src/main/logstash/search-index.conf`, 이 서비스 소유 — 배포가 Logstash 에 복사): commerce.product.v1(ProductVersionPublished·ProductContentPublished·ProductSaleStateChanged)·commerce.pricing.v1(PriceVersionEffective) → products·sku-prices. Painless 스크립트가 버전·시각을 비교(재전달·옛 사건 무시). 콘텐츠는 product-content 공개 카드 API 로 이름·이미지 보강(게시본만, 없으면 건너뛰고 stdout 기록). 작업자 1·배치 1(가격·상품 경합 방지). 재처리 = 소비 그룹 `search-index-logstash` 오프셋 되돌림. 파이프라인을 바꾸면 `SearchFlowIT`(실제 Kafka·ES·Logstash)로 확인한다.
- 이름 = `name`(nori) · `name.edge`(앞부분) · `name.partial`(1~2글자 n-gram). 찾기: nori 형태소가 모두 맞거나("두부를"→두부) n-gram 조각이 모두 있거나(붙임말 "나랑드사이다제로"·"미역"→미역국). 순위: 형태소 > 앞부분 > 조각. 카테고리는 구조 id 조상 범위.
- 검색 대상: 이름·상품 버전이 모두 있고 판매중지가 아닌 상품.
- categoryPath: 대표 카테고리의 이름 경로 — product-service 공개 트리(`pmart.search.product-base-url`)를 5분 사본으로 둔다. 조회 실패면 이전 사본, 없으면 빈 목록(검색은 실패하지 않음). (2026-11-02)
- carried: mfcId 를 줄 때만 대표 SKU 의 mfc-inventory `/internal/availability` handlingEnabled 사본(`pmart.search.inventory-base-url`). 응답 없음·실패는 null. includeNotCarried=false(기본)면 false 가 확인된 상품만 그 페이지에서 뺀다(total 은 빼기 전 색인 건수). (2026-11-02)
- 아직 없음: 인기·평점 정렬(관련도로 대체, appliedSort 로 알림), 승인 속성 필터(빈 목록), 최근 검색어·재색인 API(501).

## 상태
상품 검색·자동완성·필터(빈 목록) 구현(2026-10-30).
