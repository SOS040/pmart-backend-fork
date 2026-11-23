# 알림 — 리뷰 신고 확인 API(review 0.7.0, BR-90)

보낸 영역: platform
받는 앱: backoffice(PC 2)
작성: pmart-backend, 2026-11-23 — 고객앱 BR-90 R2 "운영자 확인 화면은 백오피스", 사용자 "추천대로"(처리 CS_AGENT·OPERATOR_ADMIN)
상태: **LAN 배포 2026-11-23**(review 0.7.0, 게이트웨이 경로 — 이미지 6ba50de4, pmart-config ea6c5f8). claude-dev(OPERATOR_ADMIN)로 목록 200 확인

- `GET /review-reports?state=OPEN|RESOLVED&size=&cursor=` — 리뷰마다 신고 묶음 `ReviewReportGroup`(리뷰 본문·별점·작성자 가림 이름·사유별 수 `reasonCounts`·기타 글 `otherDetails` 최대 10·처음/마지막 신고 시각·`reviewHidden`·`reviewDeleted`, RESOLVED 면 `resolution` HIDE·KEEP·처리자·시각). 최근 신고 순, `nextCursor`.
- `POST /review-reports/{reviewId}/resolutions` (Idempotency-Key) `{action: HIDE|KEEP, note?}` → 200 APPLIED. 처리 전 신고를 모두 닫는다. HIDE 면 리뷰 숨김(공개에서 빠지고 별점에서도 빠짐). 처리할 신고가 없으면 409 `NO_OPEN_REPORTS`.
- 역할: CS_AGENT·OPERATOR_ADMIN, 그 밖 403. 처리 결과는 신고자에게 알리지 않는다(사용자 결정).
- KEEP 뒤 새 신고가 오면 다시 OPEN 으로 보인다.
