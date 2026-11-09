# 검색 평가 — 절 기준 (local-hash-ngram@1/384)

> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).
> **절 기준**: 상위 5 안에 정답 절(questions.yml `sections` — 원문을 읽어 정함, 검색 결과로 넓히지 않음)의 청크가 있어야 적중.

- 질문 25 · 절 기준 recall@5 **0.52** · MRR **0.289** (같은 실행의 문서 경로 기준: recall@5 0.68 · MRR 0.533)

| id | 문서 기준 순위 | 절 기준 순위 | 정답 절 |
|---|---|---|---|
| R1 | 1 | 2 | business/OMS-business-logic.md § 5.2 부대비용과 혜택<br>business/Commerce-business-logic.md § 8.3 기존 배달팁 정책·봉투값의 유지<br>business/Commerce-business-logic.md § 2.5 정책·용어의 적용 우선순위와 공식 기준 |
| R2 | — | — | business/flows/cancel-vs-handover.md § 0. 원칙<br>business/flows/cancel-vs-handover.md § 2. 흐름<br>business/flows/cancel-vs-handover.md § 3. 경합 사례 |
| R3 | 1 | 2 | adr/0026- § 결정<br>adr/0026- § ADR-0026 |
| R4 | 3 | 3 | business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위<br>business/OMS-business-logic.md § 9. 분할배송과 배송 결과 |
| R5 | 1 | 1 | business/Commerce-business-logic.md § 6.6 노출과 구매 가능성<br>adr/0015- § 결정<br>adr/0015- § ADR-0015 |
| Q01 | 1 | — | business/OMS-business-logic.md § 5.2 부대비용과 혜택<br>business/Commerce-business-logic.md § 8.3 기존 배달팁 정책·봉투값의 유지<br>business/Commerce-business-logic.md § 2.5 정책·용어의 적용 우선순위와 공식 기준 |
| Q02 | 1 | 1 | business/OMS-business-logic.md § 4. 주문 초안과 접수 검증<br>business/Commerce-business-logic.md § 2.5 정책·용어의 적용 우선순위와 공식 기준 |
| Q03 | 1 | 4 | business/OMS-business-logic.md § 4. 주문 초안과 접수 검증<br>business/Commerce-business-logic.md § 2.5 정책·용어의 적용 우선순위와 공식 기준<br>business/Commerce-business-logic.md § 2.1 상품·SKU·판매 옵션·구매 수량의 차이 |
| Q04 | 1 | — | business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위<br>business/OMS-business-logic.md § A.2.6 Delivery ↔ OMS |
| Q05 | — | — | business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위<br>business/MFC-business-logic.md § IV-08 — 인계·취소 직렬 판정 |
| Q06 | 2 | 2 | business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위<br>business/OMS-business-logic.md § 6. MFC 배정·예약·시간대 확보<br>business/Commerce-business-logic.md § 4.3 인증 검증과 동의 |
| Q07 | 4 | 5 | adr/0029- § 결정<br>adr/0029- § ADR-0029<br>adr/0027- § 결정<br>adr/0027- § ADR-0027 |
| Q08 | 4 | 4 | adr/0031- § 결정<br>adr/0031- § ADR-0031<br>adr/0021- § 결정<br>adr/0021- § ADR-0021 |
| Q09 | — | — | adr/0001- § 결정<br>adr/0001- § ADR-0001<br>business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위<br>architecture/service-map.md § product-service |
| Q10 | — | — | adr/0002- § 결정<br>adr/0002- § ADR-0002<br>business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위 |
| Q11 | 1 | — | adr/0008- § 결정<br>adr/0008- § ADR-0008<br>business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위 |
| Q12 | — | — | business/flows/order-checkout-saga.md § 1.1 타이머 규칙 |
| Q13 | — | — | business/flows/return-exchange-refund.md § 1. 흐름 |
| Q14 | — | — | business/flows/replenishment-transfer.md § 5. 출고·실제 출발<br>business/flows/replenishment-transfer.md § 6. 운송·도착·인계<br>business/flows/replenishment-transfer.md § 7. 수령·TIN·Transit 대사 |
| Q15 | — | — | adr/0010- § 결정<br>adr/0010- § ADR-0010 |
| Q16 | 2 | 2 | adr/0009- § 결정<br>adr/0009- § ADR-0009 |
| Q17 | 1 | 1 | adr/0003- § 결정<br>adr/0003- § ADR-0003<br>business/domain-map.md § 3. 문서 간 충돌과 적용 우선순위 |
| Q18 | 1 | 1 | business/TMS-business-logic.md § 4.1 운송망<br>business/TMS-business-logic.md § TMS 비즈니스 로직<br>business/overview.md § 1. 한 줄 요약 |
| Q19 | 1 | 5 | adr/0018- § 결정<br>adr/0018- § ADR-0018 |
| Q20 | 2 | — | adr/0012- § 결정<br>adr/0012- § ADR-0012 |

## 문서는 맞혔지만 절은 못 맞힌 질문 — 상위 5 근거

### Q01 — 한집배달 배달팁은 얼마이고 언제 0원이 되나요?
1. 0.217 docs/business/Commerce-business-logic.md — 8. 고객에게 표시하는 기본가격·배달팁·최종 견적 (OMS 확정액 참조) > 8.8 첫 구매 무료배달의 동시 주문·실패/취소 복원 — 고객앱 표시와 OMS 점유 분리 [중요]
2. 0.219 docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.4 주문서 금액의 표시 사례
3. 0.192 docs/business/Commerce-business-logic.md — 8. 고객에게 표시하는 기본가격·배달팁·최종 견적 (OMS 확정액 참조) > 8.6 1차 계산·실패 분기 사례

### Q04 — 배달 완료를 시간이 지나면 자동으로 완료 처리하나요?
1. 0.351 docs/business/Delivery-business-logic.md — 6. 배송 완료 > 6.4 완료 누락·분할배송
2. 0.278 docs/business/Delivery-business-logic.md — 6. 배송 완료 > 6.1 전달 완료·연동
3. 0.249 docs/business/Delivery-business-logic.md — 5. 배송 중 > 5.1 배송 화면·길찾기
4. 0.234 docs/business/Commerce-business-logic.md — 13. 고객의 취소·반품·교환 신청과 환불 결과 조회 > 13.3 반품·교환 신청부터 종료까지
5. 0.234 docs/business/Delivery-business-logic.md — 7. 배달료 > 7.2 수익·정산

### Q11 — 고객 배송 슬롯 정원과 MFC 작업 여력은 누가 관리하나요?
1. 0.369 docs/adr/0008-slot-capacity-ownership.md — 배경
2. 0.280 docs/architecture/service-map.md — 7. Last-mile Delivery (6) > delivery-capacity-service `[1단계]`
3. 0.277 docs/business/BackOffice-business-logic.md — BO-07. 고객 배송 권역·담당 MFC 매핑 승인 > 1) 매핑 버전의 필수 내용
4. 0.260 docs/business/OMS-business-logic.md — 6. MFC 배정·예약·시간대 확보 > 6.1 예약 슬롯과 미설정 조건
5. 0.284 docs/business/Commerce-business-logic.md — 10. 고객 주문서·견적 동의·OMS 초안 요청/결과 > 10.1 장바구니·주문서·OMS 주문의 책임

### Q20 — 컨테이너 이미지는 어느 레지스트리에 올리나요?
1. 0.241 docs/adr/0028-dev-base-image-temurin-jdk.md — ADR-0028 개발 단계 컨테이너 베이스는 eclipse-temurin:21-jdk — 운영은 GraalVM Native 로 따로 정한다
2. 0.263 docs/adr/0012-image-registry-docker-hub-public.md — 배경
3. 0.201 docs/adr/0051-rag-customer-expansion-split-services-ollama.md — 결정 > 4
4. 0.234 docs/adr/0012-image-registry-docker-hub-public.md — 결과
5. 0.184 docs/adr/0029-config-repo-as-source-of-truth.md — 배경
