# search-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `search-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/search-service.yaml` → `openApiGenerate` 가 `com.pmart.search.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. **DB 가 없어 InboxGuard 대신 색인 문서의 버전 비교로 멱등**(ADR-0021).

## 패키지
`com.pmart.search.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- DB 없음. 색인 정의(설정·매핑)는 `src/main/resources/opensearch/*.json`. 매핑을 바꾸면 새 인덱스(`-v2`)를 만들고 별칭을 옮긴다(적용된 인덱스 매핑을 고치지 않는다).
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:search-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:search-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8105.

## 검색 색인 (2026-10-30, ADR-0021)
- OpenSearch 2.19.1(주소는 pmart-config `search-service-<프로필>.yml`). RestClient 로 REST 호출 — 공용 Builder(사용자 토큰 전파)를 쓰지 않는다.
- 구독: commerce.product.v1(ProductVersionPublished·ProductContentPublished·ProductSaleStateChanged), commerce.pricing.v1(PriceVersionEffective). 갱신은 Painless 스크립트가 버전·시각을 비교(재전달·옛 사건 무시). 실패 → Kafka 재시도 → DLT.
- 콘텐츠 사건에는 이름·이미지가 없어 product-content 공개 카드 API(`pmart.search.content-base-url`)로 보강한다(게시본만).
- 검색 대상: 이름·상품 버전이 모두 있고 판매중지가 아닌 상품. 이름은 1~2글자 n-gram, 검색어 조각 모두 일치(AND). 카테고리는 구조 id 조상 범위.
- 아직 없음: carried(null — MFC 취급 사건 없음), 인기·평점 정렬(관련도로 대체, appliedSort 로 알림), 승인 속성 필터(빈 목록), categoryPath(빈 목록), 최근 검색어·재색인 API(501).

## 상태
상품 검색·자동완성·필터(빈 목록) 구현(2026-10-30).
