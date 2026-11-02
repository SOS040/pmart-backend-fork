# product-content-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `product-content-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/product-content-service.yaml` → `openApiGenerate` 가 `com.pmart.product.content.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.product.content.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:product-content-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:product-content-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8102.

## 이미지 저장소
- 본체는 S3 호환 저장소(로컬·LAN SeaweedFS, ADR-0016), DB 에는 버킷·키·메타만. 키 `products/{productId 또는 draft-{draftId}}/{role}/{sha256}.{ext}`, 같은 sha256 은 다시 올리지 않는다.
- 설정 `pmart.storage.s3.*` ← pmart-config `product-content-service-<프로필>.yml`(자격 증명은 {cipher}). 버킷·키가 없으면 기동 실패.
- 버킷은 비공개. 클라이언트는 `GET /content-images/{imageId}` 로 받는다. 게시된 이미지는 토큰 없이(비회원·`<img src>`), 초안 이미지는 운영자·서비스만. presigned URL 을 내주지 않는다.
- 상품 상세·카드·게시 이미지는 공개(계약 `security: []`, 비회원 둘러보기 2026-10-30).
- 자동 시험(테스트 코드·Git 에 들어가는 자료)의 묶음·이미지는 합성 바이트만.
- seed-v1 묶음(B마트 화면 캡처)의 `images/`(main·description_NN·info)는 **LAN·로컬 저장소(SeaweedFS)에 내부 확인용으로 올려도 된다**(사용자 결정 2026-11-02). 묶음 zip·`captures/` 는 Git·컨테이너 이미지·공개 저장소·외부 공개 화면에 넣지 않는다(묶음 README 의 재배포 금지). 운영(prod)에는 올리지 않는다.

## 상태
2026-10-30 임시 백오피스 등록 구현: 콘텐츠 초안(개별·seed-v1 배치 upsert)·이미지 업로드·제출 준비(DISPLAY 승인 본문), ProductVersionPublished 투영·원천 키 연결, ApprovalDecided(DISPLAY) → 게시(`product_content` 불변 버전) + ProductContentPublished(commerce.product.v1), 고객 조회(상세·카드·스냅샷·projection-status).
미구현(501): `/product-contents/{id}/publications`(게시는 승인 이벤트로만), `rating-summary`(review 투영 전). 가격 필드는 채우지 않는다.

## 옵션 가용 (2026-10-31)
- 상세·카드에 `mfcId` 를 주면 SKU 옵션마다 mfc-inventory `/internal/availability`(내부 호출, 토큰 없음) 사본을 붙인다(`ContentRules.availability`): 취급 OFF carried=false·UNKNOWN, A≤0 SOLD_OUT, 그 밖 AVAILABLE. LOW("N개 남음")는 노출 임계값이 운영 설정이라 안 쓴다.
- 재고 조회 실패는 화면을 실패시키지 않고 UNKNOWN(0·품절 아님), 상세 sources 에 mfc-inventory-service UNAVAILABLE. mfcId 가 없으면 채우지 않는다.

