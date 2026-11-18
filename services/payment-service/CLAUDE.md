# payment-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `payment-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/payment-service.yaml` → `openApiGenerate` 가 `com.pmart.payment.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.payment.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:payment-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:payment-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8205.

## 결제 규칙 (ADR-0020, 결제 명세 `docs/architecture/payment-system-spec.md`)
- 결제 시도 ID = 토스 orderId(`pay-` + 32자). 받은 orderId·paymentKey 는 가공하지 않는다. paymentKey 는 승인 API 호출 전에 저장.
- 승인 성공은 `status=DONE` + paymentKey·orderId·금액·통화 일치(`PaymentRules.mismatch`)일 때만. 아니면 MISMATCH_HOLD.
- 토스 결과 3분류(`TossClient.Result`): Ok / Rejected(4xx) / Unknown(5xx·타임아웃·연결·409 처리 중). Unknown 은 원거래 조회(`ReconciliationService`)로만 해소 — 같은 멱등키로만 재요청.
- 토스 호출은 트랜잭션 밖에서, 결과 반영은 결제 시도 행을 잠근 트랜잭션에서(`PaymentLedger`).
- `TossClient` 는 HTTP/1.1 고정(JDK 클라이언트의 h2c 업그레이드를 끊는 서버가 있다 — WireMock 시험에서 EOF).
- 웹훅은 대사를 앞당기는 신호일 뿐. 서명 방식(HMAC-SHA256 Base64)은 가정 — 웹훅을 켤 때 토스 방식과 맞춘다.
- 미결정(명세 §12 D1~D8)은 구현하지 않는다: 안전한 기본 동작만(ADR-0020 목록).
- 결제위젯 설정 `GET /payment-widget-config`(BR-34, `WidgetConfigService`): 회원 토큰만(그 밖 403), `{provider, clientKey, customerKey = 토큰 subject(accountId, 명세 D6)}`, `Cache-Control: private, max-age=3600`(앱은 세션마다 한 번). clientKey 없으면 422 CONFIG_REQUIRED.
- 상관 ID: 고객 요청(`confirm`·`reportFailure`)은 결제 시도를 찾은 직후부터 `CheckoutService.correlate` 로 correlationId = 시도의 sagaId(2026-11-05). 앱이 sagaId 를 모르기 때문이다. 사건 발행은 `PaymentEvents.publish` 가 따로 sagaId(환불 결과만 요청 값)를 쓴다. 새 고객 경로를 만들면 같은 규칙을 따른다.
- 키: `pmart.payment.toss.*`(pmart-config `payment-service-<프로필>.yml`, 비밀은 {cipher}). `live_` 키는 `allow-live-keys=true` 일 때만. 시크릿 키·Authorization·카드 정보 로그 금지.

## [LAN 시험 전용] 시험 결제 자동 승인 (2026-11-19, 계약 0.2.0 — MFC 시뮬레이터 R-21)
- `pmart.payment.test-auto.enabled=true` 면 `PaymentConfig` 가 `TestAutoTossClient`(TossClient 하위)를 쓴다 — paymentKey `TEST_AUTO_<토스 orderId>` 만 토스 없이: 승인 DONE(요청 금액, method TEST_AUTO, 다른 orderId 키는 Rejected `TEST_AUTO_KEY_MISMATCH`), 취소 DONE(거래 키 `TEST_AUTO_TX_<멱등키>`, 잔액 = refundable − 취소액, 총액 null — 원장 COALESCE), 조회는 Unknown. 그 밖 키는 진짜 토스. 앞뒤 검사·원장 반영·사건은 그대로. 운영 설정에는 없다. 시험 `TestAutoPaymentIT`(토스 WireMock 호출 0).

## 상태
결제 시작·고객 승인 요청·실패 보고·조회·원거래 조회·환불/보상 취소·불명 재조회·만료 정리·웹훅 구현(2026-10-30). 주문 쪽(order·orchestration) 연결은 그쪽 구현 때.
