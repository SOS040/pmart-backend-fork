# 회신 — BR-90 리뷰 신고하기

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-review-report.md`
작성: pmart-backend, 2026-11-23 — 사용자 정책(요청서) + "추천대로"(처리 CS_AGENT·OPERATOR_ADMIN, 숨기면 별점·리뷰 수에서 뺌)
상태: 코드·시험 끝(review 단위 15·통합 8), **LAN 배포는 사용자 "배포" 뒤**(게이트웨이 경로 추가와 같이)

## R1 — review 0.7.0 `POST /reviews/{reviewId}/reports` (회원, Idempotency-Key)
- 본문 `{reason: ABUSE|AD|IRRELEVANT|PRIVACY|OTHER, detail?}` — detail 300자, OTHER 일 때만 필수·저장(다른 사유면 버림).
- 성공 201 `CommandResponse{status: APPLIED, resultId: reportId}` — **Q1 답: APPLIED**(바로 기록). "신고가 접수됐어요"를 보여도 된다.
- 거절 code: 404 `REVIEW_NOT_FOUND`(없는·숨긴·삭제된 리뷰), 409 `ALREADY_REPORTED`(이미 신고 — "이미 신고한 리뷰예요"), 422 `REPORT_OWN_REVIEW`(내 리뷰), 400 `VALIDATION_FAILED`(기타인데 내용 없음), 비회원 401·403.
- 상품 리뷰 목록 카드 `reportedByMe`·`mine`(boolean, 회원 토큰일 때만 — 비회원 null). mine 이면 신고 버튼을 숨기고, reportedByMe 면 "신고함".

## 숨김
- 운영자가 숨기면 상품 리뷰 목록·단건에서 빠지고 별점 평균·리뷰 수·순위에서도 빠진다(지운 리뷰와 같은 길).
- 작성자 본인은 단건·리뷰관리에서 **LOCKED**(고칠 수 없음)로 본다 — 상태 값을 새로 늘리지 않았다. 신고자에게는 알리지 않는다.
