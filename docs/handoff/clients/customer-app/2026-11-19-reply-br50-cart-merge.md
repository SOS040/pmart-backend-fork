# 회신 — BR-50 주문서 로그인 뒤 장바구니 병합 (질문 1~4)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/backend-requests.md` BR-50(2026-11-07 — 그동안 백엔드 회신이 없었다)
- 사용자 결정(2026-11-19): "응 그거부터해줘" — 서버 신호를 새로 만들지 않고 회신으로 정리
- 계약: auth-service **0.2.1**, cart-service **0.2.2** — 둘 다 설명만 고침(값·모양·코드 변경 없음)

## 답
1. **`CART_MERGE_CONFIRM` 은 서버가 보내지 않는다.** 로그인 요청에 비회원 정보가 없어서 서버는 비회원 장바구니가 있는지 모른다. 계약 enum 에 값은 하위 호환으로 남긴다.
   - **앱이 정한다**: `purpose=CHECKOUT` 로그인이 끝나면(`nextAction=NONE` — `COMPLETE_CONSENT` 면 동의를 마친 뒤) 로그인 전에 쓰던 비회원 장바구니(`GET /carts/{비회원 cartId}`, 비회원 토큰)에 품목이 있는지 본다 → 있으면 고객 확인(고른 항목만) → `POST /carts/{회원 cartId}/merges`. 품목이 없으면 바로 주문서로.
   - 일반 로그인(`GENERAL`)은 지금처럼 병합하지 않는다(자동 병합 금지).
2. **비회원 토큰 만료**: `X-Guest-Token` 이 만료·무효이거나 비회원 토큰이 아니거나 원본 장바구니 주인이 아니면 **403 `FORBIDDEN_OWNER`**. 병합 전에 비회원 토큰을 갱신해도 된다 — `POST /auth/tokens`(비회원 refresh token, 같은 `X-Device-Id`). 갱신 뒤 **같은 Idempotency-Key** 로 다시 보내면 된다(앞 요청은 거절이라 기록이 없다).
3. **센터(mfcId)가 다를 때**: **거절하지 않는다.** 병합 재검증은 원항목 그대로(`SOURCE_ITEM_CHANGED`)·판매 상태·구매 상한(대상 + 앞서 이관분 누적)뿐이고, 현재 가용·가격·센터는 막지 않는다(담기와 같다 — Commerce 7장 "가용은 담기를 막지 않는다"). 합친 뒤 **회원 장바구니를 다시 읽으면** 그 장바구니 센터 기준으로 `availability`(SOLD_OUT·NOT_CARRIED·LOW)가 보이고, 주문서 견적이 막는다. 이전 계약 설명("현재 가용·가격 재검증")은 실제 동작과 달라서 고쳤다.
4. **LAN**: 병합 API 는 구현·배포돼 있다(501 아님 — cart-service `MergesController`, 게이트웨이 `/api/v1/carts/**`).

## 결과 읽기(이미 계약에 있음)
- 응답 상태 CONFIRMED(모두)·PARTIAL(일부 — 실패 항목은 `reasonCode` 와 함께 비회원 장바구니에 그대로)·REJECTED. 202·결과 불명은 `GET /carts/{cartId}/merges/{mergeId}`.
- 성공분은 회원 장바구니에 합산(같은 SKU 는 수량 합)되고 비회원 장바구니에서 빠진다(한 트랜잭션).
