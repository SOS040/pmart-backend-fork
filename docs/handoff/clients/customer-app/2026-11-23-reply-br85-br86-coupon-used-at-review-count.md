# 회신 — BR-85 쿠폰 usedAt · BR-86 리뷰 쓰기 주문별 품목 수

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-coupon-used-at.md`(BR-85)·`2026-11-23-backend-review-order-writable-count.md`(BR-86)
작성: pmart-backend, 2026-11-23 — 사용자 "응 두개부터 해줘"
상태: 코드·시험 끝(promotion 단위 7·통합 6, review 단위 15·통합 7 실패 0), **LAN 배포 2026-11-23**(이미지 a314622e, PC 2 promotion·review). 고객 토큰 API 라 LAN 응답은 실기기에서 확인 부탁

## BR-85 — promotion-service 0.2.1 `Coupon.usedAt`
- `GET /me/coupons` 의 `usedAt`(date-time | null): 쿠폰 사용이 **확정된 시각** — 결제 성공 → 주문 확정 때 점유를 확정한 시각(UTC). status USED 일 때만.
- 쓴 주문이 전체 취소돼 쿠폰이 돌아오면 null(원래 사용 기간 안). 결제 중 점유는 null.
- Q1 답: 네, 주문 확정 시각 기준이다(쿠폰 확정 = 주문 확정 때 한 번, 결제 승인 직후). 서울 날짜로 "M.d(E)에 사용" 하면 된다.

## BR-86 — review-service 0.6.1 `Eligibility.orderWritableCount`
- `GET /me/review-eligibilities` 품목마다 `orderWritableCount`(integer): 같은 주문에서 **지금 쓸 수 있는**(REV_ELIGIBLE) 품목 수 — state·쪽과 상관없이 그 주문 기준(쪽 크기 1 로 받아도 맞다).
- 쪽에 나온 주문들만 한 번에 센다(무겁지 않음 — null 로 두지 않음).
