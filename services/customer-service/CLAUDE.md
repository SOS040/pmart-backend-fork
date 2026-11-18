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
- **약관 동의(2026-11-17, CU-1 사용자 결정 "추천대로", 계약 0.3.0, `ConsentService`)**: 필수 3 `TERMS_OF_SERVICE`·`PRIVACY_POLICY`·`AGE_OVER_14` + 선택 1 `MARKETING`(`domain/ConsentItem`). 위치기반서비스는 현재 위치 기능 때.
  - 현행 버전은 설정 `pmart.customer.consents.versions.{terms-of-service,privacy-policy,age-over-14,marketing}`(pmart-config `customer-service.yml`, 비면 CONFIG_REQUIRED). 바꾸면(Bus 갱신으로도) 다음 조회부터 그 항목을 다시 받는다 — 필수는 다음 로그인(auth AUTH_CONSENT)·구매 가능 여부.
  - 표 `customer_consent`(계정·항목별 마지막 결정: agreed·terms_version·agreed_at·withdrawn_at·channel·version) + `customer_consent_history`(바뀐 것 전부, V6). 같은 값·같은 버전이면 바꾸지 않는다(사건·기록 없음).
  - `GET /me/consents`(항목 넷, `requiredConsentOutdated`), `POST /me/consent-changes`(channel APP, 즉시 APPLIED, 현행 버전만 — 아니면 422 `CONSENT_VERSION_OUTDATED`, 필수 끄기 422 `REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN`(철회 = 탈퇴, 범위 밖), 같은 항목 두 번 400, 탈퇴 신청 이후는 동의만 409), `GET /me/consent-changes/{requestId}`(본인 것만). 버전·상태 검사는 멱등 처리 안(재시도가 그 사이 바뀐 버전에 막히지 않게).
  - auth 용 내부: `GET /internal/accounts/{id}/consents`(행이 없어도 200 — 모두 빠짐, `missingRequired`·`undecidedOptional` = `TYPE:version`), `POST /internal/accounts/{id}/consent-changes`(channel SIGNUP·LOGIN, 행이 없으면 만든다, 응답 = 반영 뒤 상태).
  - 바뀐 항목마다 `CustomerConsentChanged`(commerce.customer.v1, version = 행 차수, termsVersion·required·channel).
  - **구매 차단 스위치 `pmart.customer.consents.purchase-gate`**(기능 스위치, 정책값 아님 — `ConsentService.purchaseCheck`): `true` 면 내부 계정 상태 `purchasable` = ACTIVE **그리고** 필수 동의가 현행 버전(동의 전 계정은 주문 draft·checkout 422 ACCOUNT_NOT_PURCHASABLE). `false`(또는 없음)면 지금까지처럼 ACTIVE 만. `requiredConsentOutdated` 는 스위치와 상관없이 실제 값.
  - **서버는 준비됐고 LAN 은 꺼 둔다(2026-11-17)** — 고객 앱이 동의 흐름(auth completions·`/me/consent-changes`)을 반영하기 전에 켜면 기존 회원 전부가 주문·로그인을 못 한다. 켤 때는 이 `purchase-gate: true` 와 auth `require-consent: true` 를 **함께**(pmart-config). 시험: 꺼진 경로 `CustomerAddressFlowIT`, 켠 경로 `ConsentFlowIT`(속성으로 켬).

- **리뷰 작성자 표시(2026-11-16, 계약 0.2.0, ADR-0063)**: `GET /internal/accounts/{id}/review-author` → 별명 첫 글자 + `*`(최대 4개), 별명 없으면 "PMart 고객"(`ProfileRules.maskedAuthor`). 이름·이메일·전화는 내주지 않는다.
- **주문 기본 설정(2026-11-18, 계약 0.4.0, V7 — 고객 앱 "다음에도 이 품절 처리 적용", 사용자 "A해줘")**: `GET`·`PUT /me/order-preferences`(고객, `PreferencesController` → `OrderPreferenceService`, 표 `customer_order_preference` 계정당 한 행). 값 `outOfStockPreference` = order-service 주문 초안과 같은 `CANCEL_OOS_ONLY`·`CANCEL_ALL`(그 밖·null 400, 지우기 없음). 저장 전 null·version 0(지어내지 않음). PUT 은 Idempotency-Key·X-Source-System·(선택) X-Expected-Version — 즉시 반영, 바뀌면 version +1(같은 값 그대로), 버전 불일치 409 VERSION_CONFLICT, 탈퇴 신청 이후 409 INVALID_STATE(계정 행 잠금 안에서). 프로필 version 과 따로 센다. 이벤트 없음 — 앱 주문서 기본값일 뿐 order-service 는 읽지 않는다. 시험 `OrderPreferenceFlowIT`.

## 상태
프로필 조회·주소록·쇼핑 주소·비회원 배송지·내부 계정 상태/쇼핑 컨텍스트 구현(2026-10-30). 첫 구매 사용 기록(OrderConfirmed). 프로필 변경 닉네임(2026-11-03). 약관 동의(2026-11-17, CU-1). 탈퇴·연락처 변경·소셜 식별 기준 첫 구매 대조는 501(연락처는 400).
- **배송지 좌표 채우기(2026-11-18, 계약 0.5.0, V8 — 클라이언트 요청 rider-app drop-exact-coordinates 2, 사용자 "응 해줘")**: 주소 추가·수정(회원·비회원)에서 `geo`(핀)가 없으면 `AddressService.withCoordinates` 가 트랜잭션 밖에서 delivery-serviceability `POST /internal/address-geocodes`(`ServiceabilityClient.geocode` — 같은 도로명 결과, 없으면 첫 결과)로 좌표를 채우고 `customer_address.geo_source` GEOCODE(핀이면 PIN, V8 이 예전 좌표 행을 PIN 으로). 응답 `Address.geoSource`. 실패·0건은 예전처럼 좌표 없이(저장 안 막음). 멱등 해시는 고객이 보낸 값 그대로. 판정(serviceability-checks)은 그대로 도로명주소 기준. 시험 `CustomerAddressFlowIT.addressWithoutPinGetsGeocodedCoordinates`.
