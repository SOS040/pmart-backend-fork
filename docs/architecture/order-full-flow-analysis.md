# 주문 전체 흐름 분석 (order-full-flow)

- 그림: [`diagrams/order-full-flow.png`](diagrams/order-full-flow.png) · 원본 [`diagrams/order-full-flow.mmd`](diagrams/order-full-flow.mmd) (커밋 `7e48af9`)
- 기준: 그림을 만든 시점에 **구현된 코드**(main `dae7b8c`, 2026-11-03). 이후의 Eureka(ADR-0032)·설정 실행 중 갱신(ADR-0033)은 주문 흐름을 바꾸지 않는다.
- 관련: ADR-0003(사가 조정·choreography), ADR-0020(Toss 결제), ADR-0026(서비스 간 인증 없음), `docs/business/flows/`

![주문 전체 흐름](diagrams/order-full-flow.png)

## 1. 흐름 (7단계)

| 단계 | 내용 | 핵심 |
|---|---|---|
| **1. 주문서** | `POST /order-drafts` → order-service 가 customer·product·pricing·center-master·delivery-capacity·product-content 를 **동기 조회**해 초안 저장 | 견적 유효 10분(`quoteExpiresAt`). 막는 사유(`blockers`)와 다음 할 일(`nextAction`)로 앱을 안내 |
| **2. 결제 진입** | `POST /order-drafts/{id}/checkouts` → 견적 만료·버전 확인 → orchestration 에 결제 사가(S1) 시작 | 202 ACCEPTED 는 **접수일 뿐 성공이 아님**. 초안 하나에 열린 사가는 하나(`SAGA_ALREADY_ACTIVE`) |
| **S1 a~f 자원 확보** | 재고 예약(mfc-inventory) → 처리 여력 hold(mfc-capacity) → (예약배송이면) 슬롯 hold(delivery-capacity) → 결제 시도 생성(payment) | 각 단계가 한 트랜잭션. 참여자가 거절하면 보상, **결과 불명이면 실패로 보지 않고 같은 키로 재전송** |
| **3. Toss 위젯** | 앱이 `GET /payment-widget-config`(clientKey, customerKey = accountId) → 주문서를 1초 × 30회 조회해 `PAY` 가 되면 결제창 | 앱 안 SDK 방식(successUrl 리다이렉트 없음) |
| **4. 결제 승인** | `POST /payment-confirmations` → 짧은 트랜잭션으로 검증 → **트랜잭션 밖에서** Toss confirm | 결과 4가지: 승인·거절·불명(`CONFIRM_UNKNOWN`)·불일치(`MISMATCH_HOLD`). 대사 sweep 15초, 웹훅은 재조회를 앞당길 뿐 |
| **S1 g~j 확정** | 결제 이벤트 → orchestration 이 **결제를 다시 조회해 확인** → 금액 대조 → 자원 확정 → 주문 확정(`OrderConfirmed`) | 이벤트는 "깨우는 신호", 결과는 조회로 확인. 금액이 다르면 `RECONCILIATION_REQUIRED` 로 모두 멈춤 |
| **j 보상** | 이번 시도에서 확보한 것만 해제. 결제가 이미 승인됐으면 전액 취소 먼저(확인 후 자원 해제) | 해제 결과를 조회로 확인 → `COMPENSATED` |
| **6. 확정 후 전파** | `OrderConfirmed` → cart(주문한 항목 빼기)·customer(첫 구매 기록) | choreography(Debezium → Kafka), 받는 쪽은 InboxGuard |
| **7. 취소 S3** | `POST /claims` → 작업 중지 요청(mfc-fulfillment) → 자원 해제(슬롯 → 여력 → 재고) → 주문 취소 → 환불(Toss cancel) → 클레임 종료 | **피킹 전 전체 취소만** 구현 |

## 2. 설계에서 잘 된 점
- **결과 불명을 실패로 단정하지 않는다.** 결제·자원 호출의 시간 초과는 UNKNOWN 으로 두고 재조회한다. 결제 불명일 때 자원을 일부러 풀지 않고 10분 TTL 로 자연 만료시킨다 — 늦게 승인되면 `RESERVATION_LOST` → 결제 보상 취소로 정리된다.
- **외부 PG 호출이 DB 트랜잭션 밖에 있다.** 확인 → `CONFIRMING` → Toss 호출 → 결과를 다시 행 잠금으로 반영.
- **금액을 세 번 대조한다.** 결제 승인 때(요청 금액), 사가에서(승인 금액 = 사가 금액·KRW), 주문 확정 때(= 고정 견적).
- **보상 범위가 좁다.** 이번 시도에서 새로 확보한 자원만 되돌린다(서비스 규칙과 일치).
- **멱등키가 결정적이다.** 주문 ID(`ORD-` + nameUUID(draftId:requestId)), 사가 단계 키(`sagaId:STEP`), 환불 키(`REFUND-intentId`)가 입력값에서 만들어져 재시도해도 같은 결과.

## 3. 아직 없는 것 (그림에도 미구현으로 표시)
1. **이행 사가 S2** — 작업 요청 → 피킹·패킹 → 출고. 확정 뒤 실물이 움직이는 흐름이 없다(슬라이스 6, 결정 대기).
2. **배차·인계** — delivery-dispatch·execution 연결.
3. **쿠폰** — S1 e단계 자리만 있다.
4. **알림 반응** — 주문 확정·취소 알림.
5. **취소 범위** — 피킹 시작 후 취소, **부분 취소**. 지금은 전 품목·전 수량만.

## 4. 개선 여지
- **앱의 조회 대기가 길다.** 주문서 2초 × 10, 결제 1초 × 30, 확정 1초 × 30 — 최악이면 1분 넘게 조회만 한다. SSE·푸시로 바꿀 후보.
- **주문서 생성 때 동기 조회가 많다(6개 서비스).** 2-hop 규칙은 지키지만 하나가 느리면 전체가 느려진다. 일부 병렬화·캐시 검토.
- **대사 sweep 이 여러 군데다.** 결제 15초, 사가 백업 10초, 불명 재조회 1분 × 5 후 15분. 운영 지표(멈춘 사가 수, `MANUAL_REVIEW` 건수)로 지켜봐야 한다.

## 5. 참고
- 같은 이름의 Eum 그림: `Eum-main/eum_backend/diagrams/order-full-flow.*` (비교 분석은 아직 없음).
- 그림 다시 만들기: `npx @mermaid-js/mermaid-cli -i order-full-flow.mmd -o order-full-flow.png -s 2` (`docs/architecture/diagrams/`).
