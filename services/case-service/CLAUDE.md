# case-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `case-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/case-service.yaml` → `openApiGenerate` 가 `com.pmart.cases.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cases.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:case-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:case-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8506.

## 상태
3단계 스캐폴딩(2026-10-23). 아래 문의 외 엔드포인트는 501 UNSUPPORTED_ACTION.
- **고객센터 문의(2026-11-09, 계약 0.2.0, V2)**: `/inquiries`(`InquiryService`·`domain/InquiryRules`·`InquiryStore`) — 사용자 결정 C2(회원만)·C4(고객앱 문의 페이지). 고객이 남기고(본인 주문이면 order `GET /orders/{id}` 를 고객 토큰으로 불러 확인 — `OrderClient`, 설정 `pmart.cases.order-service-base-url`), 상담원 역할 `CS_AGENT` 가 답한다(→ ANSWERED). 문의 하나 = `cs_inquiry` + `case_record`(CS_INQUIRY). 남의 문의는 404, 내용은 로그에 남기지 않는다. 사진 첨부·답변 알림·종료 API 는 아직 없다.

## 다른 서비스의 요청
- MFC·주문 이행: `docs/architecture/service-requests/case-service.md`(R-1 결품 CS 이관 — ADR-0049 로 취소됨; R-2 진행 중 주문의 고객센터 취소, 2026-11-08). 구현 때 그 문서의 "보충 목록" 도 같이.
