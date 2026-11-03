# customer-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `customer-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/customer-service.yaml` → `openApiGenerate` 가 `com.pmart.customer.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.customer.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:customer-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:customer-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8103.

## 주소록·쇼핑 주소 (2026-10-30)
- 고객(회원) 기준 테이블은 `customer`(account_id 한 행, V4 에서 customer_profile 이름 변경 + name·email·phone, V5 email_verified·profile_image_*). 개인정보 로그 금지.
- name·email·email_verified·profile_image_source_url 은 `CustomerAuthenticated` 의 Google 프로필로 채운다(ADR-0024): 로그인마다 최신 값, 사건에 없는 값은 유지, 인증 시각이 가장 최근인 사건만 덮어씀. `GET /me` 가 돌려준다.
- 프로필 사진(ADR-0024): 제공자 URL 을 앱에 주지 않는다. `ProfileImageService` 가 커밋 뒤(가상 스레드) 원본이 바뀌었을 때만 받아(`ProfileImageRules`: 허용 호스트 https·리다이렉트 안 따름·256px·1MB·본문 머리로 형식) S3 `pmart-customer` 의 `profile-images/<accountId>/<sha256>` 에 둔다. `GET /me/profile-image`(본인), `profileImageUrl=/api/v1/me/profile-image?v=…`. 받기 실패는 다음 로그인 때 다시, 허용 밖·이미지 아님은 건너뜀. 버킷(`PMART_S3_BUCKET`) 미설정이면 사본 기능만 꺼짐. 설정 `pmart.customer.profile-image.*`.
- 회원 행은 auth `CustomerAuthenticated`(platform.auth.v1) 로 생기고, 이벤트보다 `/me` 가 먼저 와도 만든다(제공자는 이벤트가 채움).
- 주소 추가·수정은 delivery-serviceability `POST /serviceability-checks` 로 판정한 **사본**을 함께 저장한다. 판정 실패·불명·발효 버전 없음이어도 주소는 저장하고 `UNKNOWN`(Commerce 5.1: 저장 ≠ 배송 가능). OVERLAP·NEEDS_INPUT 도 UNKNOWN(임의 MFC 금지).
- 판정 호출은 트랜잭션 밖에서 먼저. 회원은 본인 토큰 전파, 비회원 배송지는 익명(판정 경로는 계약 `security: []`).
- 쇼핑 주소 선택은 다시 판정하고 `shoppingContextVersion` +1, `ShoppingAddressChanged`(토픽 `commerce.customer.v1` 명시). 현재 쇼핑 주소를 고치면 컨텍스트도 +1. 현재 쇼핑 주소는 삭제 불가(INVALID_STATE).
- 주소록 최대 건수 `pmart.customer.address-limit`(설계값 20, 비우면 CONFIG_REQUIRED). 비회원 배송지는 기기(`X-Device-Id`)별 같은 한도, 다른 기기 조회는 403.
- 구독 `OrderConfirmed`(order.order.v1, InboxGuard, 2026-10-31): `firstPurchase=true` 면 `first_purchase_used` 에 (계정, 주문) 기록(ON CONFLICT DO NOTHING — 처음 쓴 주문 유지) → first-purchase-checks 후보 아님. `OrderCancelled`(scope FULL·firstPurchase)는 그 주문의 사용 기록만 지워 자격을 복원한다(OMS 175행 — 출고 전 전체 취소는 자격을 소진하지 않는다, 2026-10-31). 부분 취소는 그대로.
- 프로필 변경(2026-11-03, `ProfileChangeService`): 지금은 **닉네임만**(`customer.nickname`, V2 부터 있음). 즉시 반영 APPLIED·version +1, 같은 값이면 그대로. 앞뒤 공백 제거·1~30자·제어 문자 불가(`ProfileRules`). 탈퇴 신청 이후 409 INVALID_STATE, X-Expected-Version 불일치 409. 멱등 해시는 클라이언트 값만(재시도가 처음 결과를 받게), 상태·버전 검사는 멱등 처리 안. 결과 조회는 `request_record`(본인 것만). **phone 은 본인 확인 방식을 정한 뒤 — 지금은 400.** 닉네임 값은 로그 금지. 이벤트 없음(주문 연락처는 주문 사본, BR-37).
- 약관 동의는 아직 받지 않는다(ADR-0019) — 내부 계정 상태의 `purchasable` 은 ACTIVE 여부만, `requiredConsentOutdated=false`.

## 상태
프로필 조회·주소록·쇼핑 주소·비회원 배송지·내부 계정 상태/쇼핑 컨텍스트 구현(2026-10-30). 첫 구매 사용 기록(OrderConfirmed). 프로필 변경 닉네임(2026-11-03). 동의·탈퇴·연락처 변경·소셜 식별 기준 첫 구매 대조는 501(연락처는 400).
