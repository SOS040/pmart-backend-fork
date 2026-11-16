# [pmart-backend → pmart-clients] 요청: 고객 앱 리뷰 수정·삭제 화면 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: customer_app (`lib/features/reviews`)
- 근거: 계약 `review-service.yaml` 0.2.0(LAN 배포됨), 결정 ADR-0063, 앞 알림 `2026-11-16-notice-review-service.md`, 클라이언트 회신 `customer-app/2026-11-16-backend-review-photos-and-inbox-done.md` §1("수정·삭제 화면은 이번 범위 밖")
- 업무 원문: Commerce 15장(작성 14일·**최초 등록 후 3일 미만만 수정**·삭제 후 같은 주문 상품 재작성 금지)

## 1. 들어가는 곳
- "내가 쓴 리뷰": `GET /me/review-eligibilities` 에서 `reviewId` 가 있고 상태가 `PUBLISHED`·`LOCKED` 인 품목 → `GET /reviews/{reviewId}` 로 연다.
- 리뷰 상세 아래에 **수정**·**삭제** 버튼(본인 리뷰일 때만 — 목록·상세 응답에 작성자 ID 는 없으니 "내가 쓴 리뷰"에서 들어온 경우에만 보인다).

## 2. 수정 — `PATCH /reviews/{reviewId}`
- 보이는 조건: `state == PUBLISHED`(= 최초 등록 + 3일 미만). `LOCKED` 면 버튼을 숨기거나 막고 "등록 후 3일이 지나 고칠 수 없어요" — 기한은 서버 `editableUntil` 을 그대로 보인다(앱이 계산하지 않음).
- 헤더: `Idempotency-Key`·`X-Source-System`, **`X-Expected-Version` = 상세의 `version`**.
- 본문(바꿀 것만): `rating`(1~5), `text`(1,000자), `photoIds`(최대 5장).
  - `photoIds` 를 보내면 **그 목록으로 바뀐다**(빠진 사진은 서버가 정리). 사진을 그대로 두려면 **`photoIds` 를 빼고** 보낸다. 새 사진은 작성 때처럼 `POST /review-photos` 로 먼저 올린다.
  - 바꿀 값이 하나도 없으면 400.
- 결과: 200 이면 응답 리뷰로 화면을 바꾼다(`version` 이 1 오른다). 수정해도 수정 기한(`editableUntil`)은 늘지 않는다.
- 오류:
  - 409 `INVALID_STATE` — 3일 지남·삭제됨 → 상세를 다시 읽어 상태를 보인다.
  - 409 `VERSION_CONFLICT` — 다른 곳에서 먼저 고침 → 다시 읽고 다시 고치게.
  - 422 `PHOTO_NOT_FOUND` — 내가 올린 사진이 아니거나 다른 리뷰에 쓰인 사진(오래된 사진은 빼고 다시).
  - 404 — 없는 리뷰(남의 리뷰도 404).
  - 시간 초과·결과 모름 → 같은 키·같은 본문으로 다시(같은 결과를 준다).

## 3. 삭제 — `DELETE /reviews/{reviewId}`
- 누르기 전에 꼭 확인: **"삭제하면 이 상품의 리뷰를 다시 쓸 수 없어요."**
- 헤더: `Idempotency-Key`·`X-Source-System`. 204 = 삭제됨(이미 삭제돼 있어도 204 — 멱등).
- 뒤: 상품 리뷰 목록·평균에서 빠지고, 사진도 더 이상 열리지 않는다. 자격 목록에서 그 품목은 `DELETED`(다시 쓰기 버튼 없음). 새로 쓰려 하면 422 `REWRITE_AFTER_DELETE_FORBIDDEN`.
- 결과 모름이면 같은 키로 다시 보낸다(새 리뷰 작성 권리가 생기지 않는다).

## 4. 확인
- 목 모드: 수정(별점만·사진 바꾸기·사진 그대로), LOCKED 막힘, 버전 충돌, 삭제 확인 창, 삭제 뒤 다시 쓰기 없음.
- 실서버: LAN 에 전달 완료된 배송이 생긴 뒤(지금은 배차 쪽 확인 중 — PC 5).
