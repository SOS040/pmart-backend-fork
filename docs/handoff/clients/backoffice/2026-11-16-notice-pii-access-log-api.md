# [pmart-backend → pmart-clients] 알림: 개인정보 열람 기록 조회 API — OPERATOR_ADMIN 메뉴 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: backoffice(2026-11-16 부터 PC 2)
- 결정: ADR-0062 결정 10(사용자 2026-11-15) — 열람 기록은 **OPERATOR_ADMIN 만** 본다(본인 기록 보기·별도 감사 역할 없음). 사용자 2026-11-16 "바로 만들어줘".
- 계약: `audit-service.yaml` **0.3.0**. LAN 배포는 이 문서와 같은 날.

## 1. 메뉴 제안 — "개인정보 열람 기록"(OPERATOR_ADMIN 에게만 보임)
- 목록: `GET /api/v1/pii-access-logs?actorId=&subjectType=&subjectRef=&caseRef=&accessType=&from=&to=&size=&cursor=`
  - 모두 선택. 열람 시각(`occurredAt`) 새 것부터, `size` 최대 200, 다음 쪽은 `page.nextCursor`(null 이면 끝).
  - 화면 거르기 예: 열람한 직원(운영자 ID), 정보주체(고객 ID·라이더 ID + 종류 CUSTOMER·RIDER), 주문 참조(`ORDER:ORD-…`), 기간.
- 한 건: `GET /api/v1/pii-access-logs/{piiAccessLogId}`.
- 항목: `occurredAt`·`actor`(actorId·roles·centerScope)·`subjectType`·`subjectRef`·`caseRef`·`fields`(NAME·PHONE·ADDRESS·ENTRANCE …)·`purpose`(열람 사유)·`screen`·`sourceSystem`.
  - **원문 개인정보 값은 기록에 없다**(참조·항목 코드·사유만). 사유는 직원이 쓴 글이라 화면에만 보이고 브라우저 콘솔·로그에 남기지 않는다.
- 403 = OPERATOR_ADMIN 아님(메뉴를 숨긴다), 400 = from ≥ to 또는 cursor 깨짐.
- 기록은 2년 뒤 파기된다(ADR-0062 결정 9).
