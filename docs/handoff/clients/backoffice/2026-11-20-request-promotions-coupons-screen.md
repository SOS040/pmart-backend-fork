# 요청 — 행사(기간 할인·쿠폰) 관리 화면 (promotion-service 0.2.0, ADR-0082)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20 — 사용자 "쿠폰 기능 만들고싶어. 그리고 프로모션으로 해당 상품들 전체적으로 적용하는 것까지", "다하면 메세지로 백오피스쪽 클라이언트에 날려"
- 서버 상태: **LAN 배포 2026-11-20** — promotion-service(PC 2, 8109) 80d1a952, approval-service 0.6.0(종류 PROMOTION), 게이트웨이 라우트·허용 목록, 역할 MARKETING.
- 계약: `contracts/openapi/promotion-service.yaml` 0.2.0, `contracts/openapi/approval-service.yaml` 0.6.0. 결정: `docs/adr/0082-coupons-and-period-promotions.md`.

## 무엇이 바뀌었나
- 원문의 "프로모션·쿠폰 1차 제외"를 사용자가 바꿨다(ADR-0082). 이번 범위 = **기간 할인(상품)** + **장바구니 쿠폰**. 상품 쿠폰·1+1·배송비 쿠폰은 아직 없다.
- 새 직원 역할 **MARKETING**(마케팅(행사·쿠폰)) — employee-auth 역할 목록에 들어갔다. 권한 요청 화면 역할 고르기에 자동으로 보인다.

## 만들어 줄 화면 (백오피스 웹)
1. **행사 목록** `GET /promotions?kind=&state=&size=&cursor=` — 최근 바뀐 순. 줄: 이름·종류(기간 할인/쿠폰)·상태(state)·단계(phase: 승인 전/시작 전/진행 중/끝남)·기간·할인·발급/사용 수(쿠폰).
2. **행사 만들기·고치기** `POST /promotions`, `PUT /promotions/{id}`(X-Expected-Version, DRAFT·REJECTED 만)
   - 공통: 이름(고객에게 보임, 60자)·시작·끝 시각·할인(RATE 1~90% 또는 AMOUNT 원).
   - 기간 할인(PRICE_DISCOUNT): **대상** SKU 또는 카테고리(그 아래 상품 전부 — 행사 중 새로 들어온 상품 포함), 최대 500개. 정액은 **개당** 할인.
   - 쿠폰(COUPON): 받는 방법 `issueMode` DOWNLOAD(앱 내려받기)·ADMIN(직원 지정 발급)·SIGNUP(가입 축하 — 자동 발급은 다음 단계), 최소 주문금액, 총 수량(비우면 무제한), 1인 한도(1~10), 사용 기간(받은 날 포함 N일 `useDays` 또는 고정 기한 `useUntil` 중 하나). 정률이면 최대 할인액 `maxAmount` 가능.
   - 응답 `problems[]`(field·reason) — 저장은 되지만 이대로 제출하면 막히는 곳. 칸 옆에 보여 주면 된다.
3. **제출** `POST /promotions/{id}/submissions` → `approvalRequest` 를 그대로 approval-service `POST /change-requests` 로(진열 편성·동의어와 같은 방식). 422 PROMOTION_INVALID 면 details 를 칸에. 승인되면 APPROVED, 반려되면 REJECTED(고쳐서 다시 제출).
4. **조기 종료** `POST /promotions/{id}/end-submissions` {reason} → 같은 방식으로 승인 요청. 승인되면 ENDED — 받은 쿠폰은 사용 기간까지 쓸 수 있고 받기·발급만 멈춘다.
5. **쿠폰 지정 발급**(ADMIN 행사, 진행 중) `POST /promotions/{id}/coupon-grants` {accountIds(최대 100), reason} → 고객별 ISSUED·SKIPPED_LIMIT·SOLD_OUT. 고객 찾기는 기존 고객 화면(bff views/customer-search)에서.
6. **승인함**: 종류 PROMOTION 을 다른 종류처럼 보이기. 행 attributes 에 promotionId·action(ACTIVATE/END)·kind·name·기간·할인·(종료면 reason).

## 권한
- 만들기·고치기·제출·조기 종료: MARKETING·OPERATOR_ADMIN (그 밖 403)
- 보기: + CS_AGENT
- 지정 발급: MARKETING·CS_AGENT·OPERATOR_ADMIN
- 승인: MARKETING 또는 OPERATOR_ADMIN, **만든 사람 본인은 승인 못 함**.

## 오류
- 409 INVALID_STATE(제출·발효된 행사 고치기, 승인 안 된 행사 끝내기 등), 409 VERSION_CONFLICT, 422 PROMOTION_INVALID, 422 NOT_ACTIVE(진행 중 아닌 행사에 지정 발급), 422 UNSUPPORTED_ACTION(ADMIN 이 아닌 행사에 지정 발급).

## 아직 서버에 없는 것 (다음 단계 — 화면은 나중에 붙이면 됨)
- 고객 주문 금액에 할인·쿠폰 반영(pricing·order), 상품 카드·상세 할인가 표시, 가입 쿠폰 자동 발급. 지금은 **행사를 만들고 승인·발급까지만** 된다.

## 확인
- LAN 게이트웨이로 `POST /promotions`(DRAFT 생성)·`GET /promotions` 200 확인. 시험 행사 "[시험] 배포 확인 쿠폰"(DRAFT) 하나가 남아 있다 — 지워도 되는 시험 데이터.
