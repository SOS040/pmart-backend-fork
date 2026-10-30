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
- 설정 `pmart.storage.s3.*` ← `PMART_S3_ENDPOINT/REGION/BUCKET/ACCESS_KEY/SECRET_KEY/PATH_STYLE/CREATE_BUCKET`. 버킷·키가 없으면 기동 실패.
- 버킷은 비공개. 클라이언트는 `GET /content-images/{imageId}` 로 받는다(고객은 게시된 이미지만). presigned URL 을 내주지 않는다.
- 시험 묶음·이미지는 합성 바이트만. seed-v1 캡처 원본을 저장소에 넣지 않는다.

## 상태
2026-10-30 임시 백오피스 등록 구현: 콘텐츠 초안(개별·seed-v1 배치 upsert)·이미지 업로드·제출 준비(DISPLAY 승인 본문), ProductVersionPublished 투영·원천 키 연결, ApprovalDecided(DISPLAY) → 게시(`product_content` 불변 버전) + ProductContentPublished(commerce.product.v1), 고객 조회(상세·카드·스냅샷·projection-status).
미구현(501): `/product-contents/{id}/publications`(게시는 승인 이벤트로만), `rating-summary`(review 투영 전). 가격·가용 필드는 채우지 않는다.
