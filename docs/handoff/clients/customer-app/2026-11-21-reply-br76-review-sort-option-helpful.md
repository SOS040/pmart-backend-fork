# 회신 — BR-76 상품 리뷰 추천순·옵션 필터·도움돼요 (review 0.6.0)

- 보낸 영역: platform (PC 1)
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-21-backend-review-sort-option-helpful.md`
- 작성: 2026-11-21 — 사용자 "BR-76부터 해줘"(앱 제안대로)
- 서버 상태: **review 0.6.0·게이트웨이 LAN 배포 2026-11-21**

## 답
- **R1 추천순**: `sort=RECOMMENDED` = 도움돼요 많은 순 → 같으면 최근 등록순. 사진 가산 없음. **기본 정렬은 NEWEST 그대로**(앱이 늘 명시하니 바꾸지 않음).
- **R2 옵션 필터**: `sku=<SKU>`(하나, 없으면 전체) — `withPhotoOnly`·`sort`·커서와 함께. `summary.reviewCount` = **거른 수**(sku·withPhotoOnly 적용). `averageRating` 은 상품 전체 그대로.
- **R3 도움돼요**: `PUT /reviews/{reviewId}/helpful`(누르기)·`DELETE …/helpful`(취소) — 회원(CUSTOMER)만, `Idempotency-Key`·`X-Source-System`. 이미 눌렀으면·안 눌렀으면 그대로 200(멱등). 응답 `{reviewId, helpfulCount, helpedByMe}` — 그 값으로 바꾸면 된다.
  - 내 리뷰 누르기 422 `HELPFUL_OWN_REVIEW`, 삭제된 리뷰 누르기 409 `REVIEW_NOT_PUBLISHED`, 없는 리뷰 404. 취소는 상태와 상관없이 받는다. LOCKED(수정 기간 지남)는 공개 리뷰라 누를 수 있다.
  - 같은 Idempotency-Key 재전송은 처음 응답을 그대로 준다(결과 불명이면 같은 키로).
- **R4 응답 필드**: `Review.helpfulCount`(0 이상)·`helpedByMe` — 목록 `GET /products/{productId}/reviews` 에 회원 토큰을 붙이면 채운다(비회원·토큰 없음은 null). 토큰은 선택(붙이지 않아도 지금처럼 열린다).
- **Q1**: 도움돼요 수는 그대로 공개(숨김·반올림 없음).
- **Q2**: 누르기·취소 반복에 따로 제한 없음 — 게이트웨이 회원 요청 제한만 적용된다(넘으면 429).
