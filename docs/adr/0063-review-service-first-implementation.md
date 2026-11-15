# ADR-0063 리뷰 1차 구현 — 배송 완료 사건으로 자격, 사진 5장·10MB, 받은 수량 전부 반품이면 새 작성 막음, 운영자 숨김은 뒤로

- 상태: 승인 (2026-11-16, 사용자 — "리뷰 기능 만들어야할것같아" → 질문 3개: 사진 "5장·장당 10MB", 환불 후 "전체 환불이면 새 작성 막기", 운영자 숨김 "이번엔 뺌")
- 관련: 업무 원문 Commerce 15장(리뷰 확정 정책 — 14일 작성·3일 수정·삭제 후 재작성 금지), `docs/architecture/service-map.md` review-service, 계약 `review-service.yaml` 0.2.0·`order-service.yaml` 0.7.0·`customer-service.yaml` 0.2.0, 사건 `DeliveryCompleted`(구독)·`ReviewPublished`·`ReviewDeleted`(발행)
- 고객 앱 리뷰 화면(pmart-clients `apps/customer_app/lib/features/reviews`)은 계약 0.1.0 에 맞춰 이미 있다(목 데이터).

## 배경
- review-service 는 계약(0.1.0)만 있고 코드가 없었다. 업무 원문은 기한 규칙은 확정했지만 사진 수·용량, 반품·환불 뒤 리뷰, 신고·숨김은 `[팀 확인 필요]` 로 남겼다(15.1·15.4).
- 주문 원장(order)은 아직 배송 완료를 받지 않아 품목별 전달 수량(`delivered_qty`)이 늘 0 이다. 반품·교환(claim RETURN/EXCHANGE)도 아직 없다.

## 결정
1. **자격은 review 가 `DeliveryCompleted`(delivery.delivery.v1)에서 직접 만든다.** 품목(orderLineId)마다 한 행, 수령 시각은 **그 품목의 첫 수령**(분할 수령이면 이른 쪽), 받은 수량은 누계. 계정·SKU·상품·주문 당시 상품명은 order `GET /internal/orders/{id}` 사본(0.7.0 에 productId·productName·deliveredQty·returnedQty 추가). order 에 없는 주문은 건너뛴다.
2. **기한**(원문 그대로): 작성 = 첫 수령 + 14일 경계까지(그 시각 포함), 수정 = 최초 등록 + 3일 미만(수정이 늘리지 않음), 품목당 1건, 삭제해도 행이 남아 재작성 금지. LOCKED·EXPIRED 는 저장하지 않고 서버 시각으로 계산한다.
3. **사진**(사용자): 리뷰당 5장, 장당 10MB, JPEG·PNG·WEBP(파일 앞부분으로 판정). `POST /review-photos` 로 먼저 올리고 photoId 를 작성·수정에 넣는다. 올린 사람만 붙일 수 있다. 본문 `GET /review-photos/{id}` 는 **공개 리뷰에 붙은 사진만**. 24시간 안에 붙지 않거나 수정으로 떨어진 사진은 1시간마다 지운다. 삭제된 리뷰의 사진은 지우지 않고 공개만 닫는다(거래 증빙 보존 — 15.1). 저장소는 LAN SeaweedFS 버킷 `pmart-review`.
4. **반품·환불 뒤**(사용자 "전체 환불이면 새 작성 막기"): 작성 때 order 원장의 그 품목 `returnedQty` 가 review 가 아는 받은 수량 이상이면 422 NOT_ELIGIBLE. **이미 쓴 리뷰는 그대로**(자동 삭제·표식 없음 — 15.4). order 를 못 읽으면 503(막지도 열지도 않음). 반품이 구현되기 전에는 `returnedQty` 가 0 이라 실제로 막히는 일은 없다 — 규칙만 먼저 둔다.
5. **작성자 표시**: 고객 별명 첫 글자 + 나머지 `*`(최대 4개), 별명이 없으면 "PMart 고객". customer `GET /internal/accounts/{id}/review-author`(0.2.0)를 작성 때 한 번 읽어 저장한다(별명을 바꿔도 지난 리뷰는 그대로). 못 읽으면 기본 표시로 쓰고 작성은 막지 않는다. 이름·이메일·전화는 쓰지 않는다.
6. **운영자 숨김·신고는 이번에 없다**(사용자 — 신고·차단 정책 승인 전, BackOffice 원문 "부정적 리뷰만으로 자동 삭제·라이더 제재 금지"). 삭제는 작성자 본인만.
7. **발행**: 작성·수정마다 `ReviewPublished`(수정은 별점이 바뀔 수 있어 같은 사건을 다시 — 소비자는 reviewId 로 덮어쓴다), 삭제 때 `ReviewDeleted`(reason AUTHOR_DELETED). 토픽 commerce.review.v1.
8. **배치**: LAN PC 2(commerce 와 같은 PC), 포트 8108, DB `review_service`. 게이트웨이 경로 `/products/*/reviews`·`/reviews/**`·`/me/review-eligibilities`·`/review-photos/**` 는 `/me/**`·`/products/**` 보다 먼저.

## 결과
- 고객 앱은 계약 0.1.0 그대로 붙일 수 있고, 사진 올리기(0.2.0)만 더하면 된다.
- 상품 카드·상세의 평균 별점(product-content `rating-summary`)은 아직 `ReviewPublished`·`ReviewDeleted` 를 받지 않는다 — 다음 작업. 그 전에는 리뷰 목록 응답의 `summary` 를 쓴다.
- 반품이 생기면(claim RETURN) order `returned_qty` 를 채워야 4번이 동작한다.
- 남은 정책: 신고·숨김·작성 보상, 부분 반품 품목의 리뷰 표식(지금은 아무 표시 없음).
