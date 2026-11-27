# 검색 평가 — 문서 경로 기준 (ollama:qwen3-embedding:0.6b@eval/1024)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **문서 경로 기준**: 상위 5 안에 정답 문서(경로 앞부분 일치)의 청크가 하나라도 있으면 적중. 그 청크가 답을 담은 절인지는 보지 않는다 — 절 기준은 retrieval-section.md.

- 색인: 파일 75 · 청크 642
- 색인 651.9초 · 질문당 검색 348 ms(임베딩 포함)
- 질문 24 · recall@5 **1.00** · MRR **0.889** · 임계값 통과 0건(NO_GROUNDING) 0

| id | 적중 순위 | 상위 근거(앞 3) |
|---|---|---|
| R1 | 1 | docs/rag/rag-docs/order_claim/01-order-amount.md<br>docs/rag/rag-docs/customer_app/06-order-payment-fees.md<br>docs/rag/rag-docs/common/02-business-overview.md |
| R2 | 1 | docs/rag/rag-docs/order_claim/03-cancel.md<br>docs/rag/rag-docs/mfc/05-dispatch-handover.md<br>docs/rag/rag-docs/order_claim/03-cancel.md |
| R3 | 1 | docs/rag/rag-docs/customer_app/08-order-cancel.md<br>docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/order_claim/03-cancel.md |
| R4 | 2 | docs/rag/rag-docs/delivery/01-service-area.md<br>docs/rag/rag-docs/order_claim/01-order-amount.md<br>docs/rag/rag-docs/customer_app/09-delivery-status.md |
| Q01 | 1 | docs/rag/rag-docs/order_claim/01-order-amount.md<br>docs/rag/rag-docs/customer_app/06-order-payment-fees.md<br>docs/rag/rag-docs/common/02-business-overview.md |
| Q02 | 2 | docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/order_claim/02-payment-order-status.md<br>docs/rag/rag-docs/customer_app/07-delivery-area-address.md |
| Q03 | 1 | docs/rag/rag-docs/order_claim/01-order-amount.md<br>docs/rag/rag-docs/commerce/01-product-sku.md<br>docs/rag/rag-docs/commerce/01-product-sku.md |
| Q04 | 1 | docs/rag/rag-docs/rider_app/03-pickup-delivery.md<br>docs/rag/rag-docs/customer_app/09-delivery-status.md<br>docs/rag/rag-docs/delivery/04-pickup-completion-failure.md |
| Q05 | 1 | docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/common/01-glossary.md<br>docs/rag/rag-docs/tms/04-arrival-handover-exceptions.md |
| Q06 | 1 | docs/rag/rag-docs/order_claim/01-order-amount.md<br>docs/rag/rag-docs/customer_app/06-order-payment-fees.md<br>docs/rag/rag-docs/common/02-business-overview.md |
| Q18 | 2 | docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/common/01-glossary.md<br>docs/rag/rag-docs/common/01-glossary.md |
| Q21 | 1 | docs/rag/rag-docs/customer_app/11-shortage.md<br>docs/rag/rag-docs/order_claim/04-shortage.md<br>docs/rag/rag-docs/mfc/04-picking-packing.md |
| Q22 | 1 | docs/rag/rag-docs/customer_app/08-order-cancel.md<br>docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/order_claim/03-cancel.md |
| Q23 | 3 | docs/rag/rag-docs/customer_app/10-not-received-misdelivery.md<br>docs/rag/rag-docs/rider_app/03-pickup-delivery.md<br>docs/rag/rag-docs/delivery/05-incidents-help-recovery.md |
| N01 | 1 | docs/rag/rag-docs/order_claim/06-return-exchange.md<br>docs/rag/rag-docs/customer_app/13-return-exchange.md<br>docs/rag/rag-docs/order_claim/06-return-exchange.md |
| N02 | 1 | docs/rag/rag-docs/delivery/04-pickup-completion-failure.md<br>docs/rag/rag-docs/rider_app/03-pickup-delivery.md<br>docs/rag/rag-docs/delivery/04-pickup-completion-failure.md |
| N03 | 1 | docs/rag/rag-docs/backoffice/04-personal-info-access.md<br>docs/rag/rag-docs/backoffice/04-personal-info-access.md<br>docs/rag/rag-docs/backoffice/04-personal-info-access.md |
| N04 | 1 | docs/rag/rag-docs/backoffice/06-product-display-search.md<br>docs/rag/rag-docs/commerce/04-search.md<br>docs/rag/rag-docs/commerce/04-search.md |
| N05 | 1 | docs/rag/rag-docs/mfc/03-inbound-putaway-move.md<br>docs/rag/rag-docs/scm/01-supply-principles.md<br>docs/rag/rag-docs/tms/02-dispatch-planning.md |
| N06 | 1 | docs/rag/rag-docs/mfc/05-dispatch-handover.md<br>docs/rag/rag-docs/mfc/05-dispatch-handover.md<br>docs/rag/rag-docs/order_claim/03-cancel.md |
| N07 | 1 | docs/rag/rag-docs/customer_app/16-reviews.md<br>docs/rag/rag-docs/common/02-business-overview.md<br>docs/rag/rag-docs/commerce/05-reviews.md |
| N08 | 2 | docs/rag/rag-docs/order_claim/09-consult-records-notifications.md<br>docs/rag/rag-docs/commerce/06-customer-notifications.md<br>docs/rag/rag-docs/customer_app/15-notifications.md |
| N09 | 1 | docs/rag/rag-docs/backoffice/01-staff-account-login.md<br>docs/rag/rag-docs/backoffice/08-hr-employee-management.md<br>docs/rag/rag-docs/backoffice/08-hr-employee-management.md |
| N10 | 1 | docs/rag/rag-docs/commerce/01-product-sku.md<br>docs/rag/rag-docs/commerce/04-search.md<br>docs/rag/rag-docs/cdc_wms/01-opening-location-sku-handling.md |

## 실패·저순위 사례 (적중 없음 또는 3위 밖) — 상위 5 근거
