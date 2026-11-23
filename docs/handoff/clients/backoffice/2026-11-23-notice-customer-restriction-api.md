# 알림 — 고객 계정 이용제한·정지 API(customer 0.16.0, CU-7)

보낸 영역: platform
받는 앱: backoffice(PC 2)
작성: pmart-backend, 2026-11-23 — CU-7 사용자 결정(#379·#382 포함), 사용자 "2번해줘"
상태: 코드·시험 끝, LAN 배포는 사용자 "배포" 뒤

- `POST /customers/{customerId}/restrictions` (Idempotency-Key) `{restriction: RESTRICTED|SUSPENDED, reason(2~200자)}` → 201 CommandResponse APPLIED(resultId = 이력 ID). 늘 지금 + 7일, 지금 제한이 있으면 새 값·새 기한으로 바꿈. 탈퇴 완료 409.
- `POST /customers/{customerId}/restriction-releases` `{reason}` → 200 APPLIED. 제한이 없으면 409 NOT_RESTRICTED(오인식 풀기).
- `GET /customers/{customerId}/restrictions` → `{customerId, restriction, restrictedUntil, items[]}` 최근 50개 — 항목 recordId·restriction·previous·restrictedUntil·source(STAFF·AUTO_PROFANITY)·reason·actor(직원 ID 또는 system:case-profanity)·createdAt.
- 고객 상세·찾기 응답에 `restriction`(NONE·RESTRICTED·SUSPENDED)·`restrictedUntil`. 기한이 지나면 NONE(자동 해제 — 이력 줄은 없음).
- 역할 CS_AGENT·OPERATOR_ADMIN(그 밖 403). 자동 정지는 문의 정지 단어(case) — 7일, 지난 사건은 남은 기간만.
- 막는 것: RESTRICTED = 새 문의·리뷰 작성(403 ACCOUNT_RESTRICTED), SUSPENDED = 그것 + 주문·로그인(403 ACCOUNT_SUSPENDED, 걸 때 나간 토큰 끊음). 고객에게 알림함·푸시 "7일 계정 정지".
