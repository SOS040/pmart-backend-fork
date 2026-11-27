# 검색 평가 — 절 기준 (ollama:qwen3-embedding:0.6b@eval/1024)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **절 기준**: 상위 5 안에 정답 절(questions.yml `sections` — 원문을 읽어 정함, 검색 결과로 넓히지 않음)의 청크가 있어야 적중.

- 질문 24 · 절 기준 recall@5 **1.00** · MRR **0.872** (같은 실행의 문서 경로 기준: recall@5 1.00 · MRR 0.889)

| id | 문서 기준 순위 | 절 기준 순위 | 정답 절 |
|---|---|---|---|
| R1 | 1 | 1 | rag/rag-order_claim/01-order-amount.md § 제7조 (봉투값<br>rag/rag-customer_app/06-order-payment-fees.md § 제4조 (봉투값 |
| R2 | 1 | 1 | rag/rag-order_claim/03-cancel.md § 제7조 (취소와 라이더 인계가 겹칠 때<br>rag/rag-mfc/05-dispatch-handover.md § 제7조 (인계와 주문 중지 |
| R3 | 1 | 1 | rag/rag-order_claim/03-cancel.md § 제2조 (취소 가능 시점<br>rag/rag-order_claim/03-cancel.md § 제3조 (고객센터 대리 취소<br>rag/rag-customer_app/08-order-cancel.md § 제2조 (취소할 수 있는 시점 |
| R4 | 2 | 2 | rag/rag-order_claim/01-order-amount.md § 제10조 (나눠 배송<br>rag/rag-customer_app/09-delivery-status.md § 제7조 (나눠 보내는 배송 |
| Q01 | 1 | 1 | rag/rag-order_claim/01-order-amount.md § 제5조 (배달팁<br>rag/rag-customer_app/06-order-payment-fees.md § 제3조 (배달팁 |
| Q02 | 2 | 2 | rag/rag-order_claim/02-payment-order-status.md § 제7조 (결제 후 주소·연락처·요청 사항<br>rag/rag-customer_app/07-delivery-area-address.md § 제5조 (결제 뒤 주소·연락처 변경 |
| Q03 | 1 | 1 | rag/rag-order_claim/01-order-amount.md § 제3조 (구매 수량 상한<br>rag/rag-commerce/01-product-sku.md § 제6조 (구매 수량 상한 |
| Q04 | 1 | 1 | rag/rag-rider_app/03-pickup-delivery.md § 제3조 (고객 도착과 전달 완료<br>rag/rag-delivery/04-pickup-completion-failure.md § 제3조 (전달 완료 |
| Q05 | 1 | 1 | rag/rag-mfc/05-dispatch-handover.md § 제6조 (인계 확정과 재고<br>rag/rag-common/02-business-overview.md § 제3조 (두 가지 물류 흐름 |
| Q06 | 1 | 1 | rag/rag-order_claim/01-order-amount.md § 제12조 (주류<br>rag/rag-customer_app/06-order-payment-fees.md § 제8조 (주류 구매 |
| Q18 | 2 | 3 | rag/rag-common/01-glossary.md § 제2조 (회사·시설 |
| Q21 | 1 | 1 | rag/rag-order_claim/04-shortage.md § 제3조 (결품 처리 기준<br>rag/rag-customer_app/11-shortage.md § 제2조 (결품 처리 |
| Q22 | 1 | 1 | rag/rag-order_claim/03-cancel.md § 제2조 (취소 가능 시점<br>rag/rag-customer_app/08-order-cancel.md § 제2조 (취소할 수 있는 시점 |
| Q23 | 3 | 3 | rag/rag-order_claim/07-misdelivery.md § 제4조 (귀책별 처리<br>rag/rag-delivery/05-incidents-help-recovery.md § 제4조 (오배송 회수 배송 |
| N01 | 1 | 1 | rag/rag-order_claim/06-return-exchange.md § 제4조 (회수비<br>rag/rag-customer_app/13-return-exchange.md § 제3조 (비용 |
| N02 | 1 | 1 | rag/rag-delivery/04-pickup-completion-failure.md § 제4조 (전달 실패의 확정과 반려 |
| N03 | 1 | 1 | rag/rag-backoffice/04-personal-info-access.md § 제4조 (원문 열람 |
| N04 | 1 | 1 | rag/rag-backoffice/06-product-display-search.md § 제9조 (검색 동의어<br>rag/rag-commerce/04-search.md § 제4조 (동의어 |
| N05 | 1 | 1 | rag/rag-mfc/03-inbound-putaway-move.md § 제2조 (입고 수령 시간과 물량 |
| N06 | 1 | 1 | rag/rag-mfc/05-dispatch-handover.md § 제4조 (인계 허가 유효시간 |
| N07 | 1 | 1 | rag/rag-customer_app/16-reviews.md § 제3조 (작성 기한<br>rag/rag-commerce/05-reviews.md § 제2조 (리뷰를 쓸 수 있는 고객과 상품 |
| N08 | 2 | 4 | rag/rag-commerce/06-customer-notifications.md § 제5조 (수신 설정 |
| N09 | 1 | 1 | rag/rag-backoffice/01-staff-account-login.md § 제3조 (계정 발급<br>rag/rag-backoffice/08-hr-employee-management.md § 제5조 (계정 발급 |
| N10 | 1 | 1 | rag/rag-commerce/01-product-sku.md § 제7조 (카테고리 |

## 문서는 맞혔지만 절은 못 맞힌 질문 — 상위 5 근거
