# 답변 평가 질문별 상위 근거 (ollama:qwen3-embedding:0.6b@eval/1024, 임계값 0 — 생성 모델에 넘어가는 순서)

### A01 — 봉투값은 주문당 얼마이고 언제 환불되나요?
1. 0.675 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제7조 (봉투값)
2. 0.628 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제4조 (봉투값)
3. 0.557 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제6조 (주문 금액)
4. 0.536 [CONFIRMED] docs/rag/rag-docs/customer_app/14-refund.md — 제4조 (봉투값 환불)
5. 0.525 [CONFIRMED] docs/rag/rag-docs/customer_app/11-shortage.md — 제5조 (결품 환불 금액)

### A02 — 결제 후에 배송 주소나 연락처를 바꿀 수 있나요?
1. 0.622 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제9조 (주문 변경과 취소)
2. 0.617 [CONFIRMED] docs/rag/rag-docs/order_claim/02-payment-order-status.md — 제7조 (결제 후 주소·연락처·요청 사항)
3. 0.610 [CONFIRMED] docs/rag/rag-docs/customer_app/07-delivery-area-address.md — 제5조 (결제 뒤 주소·연락처 변경)
4. 0.516 [CONFIRMED] docs/rag/rag-docs/order_claim/02-payment-order-status.md — 제8조 (예약배달 시간 변경)
5. 0.455 [CONFIRMED] docs/rag/rag-docs/order_claim/07-misdelivery.md — 제5조 (실제 집 주소의 기록)

### A03 — 같은 기초상품의 구매 제한 기본 상한은 몇 개인가요?
1. 0.588 [CONFIRMED] docs/rag/rag-docs/commerce/01-product-sku.md — 제6조 (구매 수량 상한)
2. 0.552 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제3조 (구매 수량 상한)
3. 0.488 [CONFIRMED] docs/rag/rag-docs/commerce/01-product-sku.md — 제2조 (용어)
4. 0.487 [CONFIRMED] docs/rag/rag-docs/commerce/07-cart.md — 제4조 (담기와 수량 변경)
5. 0.453 [CONFIRMED] docs/rag/rag-docs/commerce/01-product-sku.md — 제7조 (카테고리)

### A04 — 배달 완료를 시간이 지나면 자동으로 완료 처리하나요?
1. 0.565 [CONFIRMED] docs/rag/rag-docs/rider_app/03-pickup-delivery.md — 제3조 (고객 도착과 전달 완료)
2. 0.536 [CONFIRMED] docs/rag/rag-docs/customer_app/09-delivery-status.md — 제6조 (배송 완료 표시)
3. 0.520 [CONFIRMED] docs/rag/rag-docs/delivery/04-pickup-completion-failure.md — 제3조 (전달 완료)
4. 0.488 [CONFIRMED] docs/rag/rag-docs/rider_app/04-earnings.md — 제3조 (수행료의 확정)
5. 0.487 [CONFIRMED] docs/rag/rag-docs/delivery/01-service-area.md — 제6조 (배달 서비스 시간)

### A05 — 분할 배송 기준 무게는 얼마인가요?
1. 0.521 [CONFIRMED] docs/rag/rag-docs/delivery/01-service-area.md — 제5조 (배송 조건)
2. 0.498 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제10조 (나눠 배송)
3. 0.485 [CONFIRMED] docs/rag/rag-docs/customer_app/09-delivery-status.md — 제7조 (나눠 보내는 배송)
4. 0.416 [CONFIRMED] docs/rag/rag-docs/delivery/05-incidents-help-recovery.md — 제1조 (목적)
5. 0.405 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제6조 (배송·운송)

### A06 — 주류 주문의 성인 인증은 어떻게 하나요?
1. 0.595 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제12조 (주류)
2. 0.530 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제8조 (주류 구매)
3. 0.409 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제8조 (결제)
4. 0.357 [CONFIRMED] docs/rag/rag-docs/backoffice/06-product-display-search.md — 제1조 (목적)
5. 0.352 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제1조 (목적)

### A08 — 주문 사가는 오케스트레이션인가요 코레오그래피인가요?
1. 0.368 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제5조 (주문·결제)
2. 0.365 [CONFIRMED] docs/rag/rag-docs/order_claim/02-payment-order-status.md — 제9조 (센터가 주문 작업을 받지 못할 때)
3. 0.361 [CONFIRMED] docs/rag/rag-docs/order_claim/02-payment-order-status.md — 제4조 (주문 확정)
4. 0.360 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제7조 (시간 기준)
5. 0.347 [CONFIRMED] docs/rag/rag-docs/mfc/07-supervisor-controls.md — 제7조 (관리자 판단 주문 취소)

### A10 — 컨테이너 이미지는 어느 레지스트리에 올리나요?
1. 0.350 [CONFIRMED] docs/rag/rag-docs/backoffice/06-product-display-search.md — 제7조 (고객용 상품 콘텐츠)
2. 0.328 [CONFIRMED] docs/rag/rag-docs/mfc/08-center-zones-throughput.md — 제2조 (센터 구역)
3. 0.325 [CONFIRMED] docs/rag/rag-docs/commerce/03-home-display.md — 제4조 (배너)
4. 0.317 [CONFIRMED] docs/rag/rag-docs/mfc/03-inbound-putaway-move.md — 제6조 (적치)
5. 0.301 [CONFIRMED] docs/rag/rag-docs/mfc/07-supervisor-controls.md — 제9조 (센터 설정 관리)

### A11 — 서비스 설정의 정본은 어디에 두나요?
1. 0.328 [CONFIRMED] docs/rag/rag-docs/customer_app/01-signup-login.md — 제1조 (목적)
2. 0.325 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제2조 (회사·시설)
3. 0.323 [CONFIRMED] docs/rag/rag-docs/customer_app/01-signup-login.md — 제2조 (정의)
4. 0.318 [CONFIRMED] docs/rag/rag-docs/customer_app/04-consents-privacy.md — 제4조 (위치 정보)
5. 0.315 [CONFIRMED] docs/rag/rag-docs/delivery/01-service-area.md — 제1조 (목적)

### A12 — 서비스 간 내부 호출에도 인증 토큰이 필요한가요?
1. 0.337 [CONFIRMED] docs/rag/rag-docs/customer_app/04-consents-privacy.md — 제6조 (휴대폰 번호 확인 정보)
2. 0.322 [CONFIRMED] docs/rag/rag-docs/tms/01-transfer-request-intake.md — 제5조 (이관요청서 자동 검증)
3. 0.310 [CONFIRMED] docs/rag/rag-docs/customer_app/01-signup-login.md — 제1조 (목적)
4. 0.303 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제12조 (주류)
5. 0.302 [CONFIRMED] docs/rag/rag-docs/customer_app/01-signup-login.md — 제2조 (정의)

### A13 — 피킹 중에 결품이 나면 주문은 어떻게 처리되나요?
1. 0.527 [CONFIRMED] docs/rag/rag-docs/mfc/04-picking-packing.md — 제2조 (피킹 작업)
2. 0.515 [CONFIRMED] docs/rag/rag-docs/order_claim/04-shortage.md — 제1조 (목적)
3. 0.506 [CONFIRMED] docs/rag/rag-docs/order_claim/04-shortage.md — 제2조 (결품의 범위)
4. 0.482 [CONFIRMED] docs/rag/rag-docs/mfc/04-picking-packing.md — 제1조 (목적)
5. 0.472 [CONFIRMED] docs/rag/rag-docs/mfc/04-picking-packing.md — 제8조 (주문 취소 뒤 복귀)

### A14 — MFC 작업 배정 추천의 최적화 목표 우선순위는 어떻게 되나요?
1. 0.448 [CONFIRMED] docs/rag/rag-docs/mfc/02-task-assignment.md — 제7조 (배정 추천과 작업 시작)
2. 0.385 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제3조 (사람·화면)
3. 0.372 [CONFIRMED] docs/rag/rag-docs/mfc/02-task-assignment.md — PMart MFC 작업 배정 정책
4. 0.364 [CONFIRMED] docs/rag/rag-docs/cdc_wms/03-replenishment-transfer-departure.md — 제3조 (한 요청은 한 번만 출발)
5. 0.361 [CONFIRMED] docs/rag/rag-docs/mfc/02-task-assignment.md — 제2조 (배정 원칙)

### A15 — 보충 물량은 어느 CDC에서 몇 곳의 MFC로 운송하나요?
1. 0.493 [CONFIRMED] docs/rag/rag-docs/cdc_wms/03-replenishment-transfer-departure.md — 제1조 (목적)
2. 0.479 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제3조 (두 가지 물류 흐름)
3. 0.461 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제4조 (상품·재고)
4. 0.461 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제6조 (배송·운송)
5. 0.445 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제2조 (회사·시설)

### A16 — 고객 분류 카테고리는 몇 단계로 구성하나요?
1. 0.400 [CONFIRMED] docs/rag/rag-docs/commerce/04-search.md — 제7조 (카테고리와 필터)
2. 0.387 [CONFIRMED] docs/rag/rag-docs/commerce/01-product-sku.md — 제7조 (카테고리)
3. 0.376 [CONFIRMED] docs/rag/rag-docs/order_claim/09-consult-records-notifications.md — 제5조 (상담 분류)
4. 0.340 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제1조 (목적)
5. 0.339 [CONFIRMED] docs/rag/rag-docs/commerce/01-product-sku.md — 제2조 (용어)

### A17 — 최소 주문금액은 얼마이고 무엇을 기준으로 판정하나요?
1. 0.540 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제4조 (최소 주문금액)
2. 0.531 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제2조 (최소 주문금액)
3. 0.489 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제6조 (주문 금액)
4. 0.474 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제1조 (목적)
5. 0.465 [CONFIRMED] docs/rag/rag-docs/commerce/02-pricing-promotions.md — 제6조 (할인 계산 순서)

### A18 — 첫 구매 고객의 배달팁은 어떻게 되나요?
1. 0.623 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제5조 (배달팁)
2. 0.566 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제6조 (주문 금액)
3. 0.559 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제3조 (배달팁)
4. 0.530 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제6조 (첫 구매)
5. 0.525 [CONFIRMED] docs/rag/rag-docs/customer_app/06-order-payment-fees.md — 제5조 (첫 주문 혜택)

### A19 — MFC 피킹 방문 순서는 온도대별로 어떻게 정해지나요?
1. 0.494 [CONFIRMED] docs/rag/rag-docs/tms/02-dispatch-planning.md — 제7조 (여러 센터 방문)
2. 0.440 [CONFIRMED] docs/rag/rag-docs/mfc/04-picking-packing.md — 제2조 (피킹 작업)
3. 0.412 [CONFIRMED] docs/rag/rag-docs/order_claim/04-shortage.md — 제5조 (중복 환불 금지)
4. 0.411 [CONFIRMED] docs/rag/rag-docs/order_claim/04-shortage.md — 제2조 (결품의 범위)
5. 0.398 [CONFIRMED] docs/rag/rag-docs/tms/04-arrival-handover-exceptions.md — 제2조 (하차 절차)

### B01 — 라이더 수행료는 언제 지급되고, 영업일은 어떻게 세나요?
1. 0.541 [CONFIRMED] docs/rag/rag-docs/delivery/07-earnings-operations.md — 제1조 (목적)
2. 0.535 [CONFIRMED] docs/rag/rag-docs/delivery/07-earnings-operations.md — 제8조 (지급 결과)
3. 0.518 [CONFIRMED] docs/rag/rag-docs/order_claim/07-misdelivery.md — 제7조 (라이더 수행료와의 구분)
4. 0.517 [CONFIRMED] docs/rag/rag-docs/delivery/07-earnings-operations.md — 제7조 (이의 인정 보상금)
5. 0.511 [CONFIRMED] docs/rag/rag-docs/rider_app/04-earnings.md — 제2조 (수행료의 결정)

### B02 — MFC 재고 정정이나 폐기는 언제 본사 승인이 필요한가요?
1. 0.444 [CONFIRMED] docs/rag/rag-docs/backoffice/03-approval.md — 제2조 (승인이 필요한 변경과 승인자)
2. 0.437 [CONFIRMED] docs/rag/rag-docs/mfc/06-inventory-quality.md — 제6조 (본사 승인 기준)
3. 0.435 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제3조 (두 가지 물류 흐름)
4. 0.429 [CONFIRMED] docs/rag/rag-docs/scm/01-supply-principles.md — 제8조 (관련 정책)
5. 0.413 [CONFIRMED] docs/rag/rag-docs/backoffice/02-roles-permissions.md — 제3조 (역할)

### B03 — 직원 사번은 어떤 형식인가요?
1. 0.567 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제4조 (사번)
2. 0.439 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제3조 (계정 발급)
3. 0.434 [CONFIRMED] docs/rag/rag-docs/commerce/08-display-numbers.md — 제3조 (화면 번호 형식)
4. 0.433 [CONFIRMED] docs/rag/rag-docs/mfc/01-worker-qualification-shift.md — 제9조 (작업자 정보 열람)
5. 0.431 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제1조 (목적)

### B04 — 내가 요청한 변경을 내가 승인할 수 있나요?
1. 0.651 [CONFIRMED] docs/rag/rag-docs/backoffice/03-approval.md — 제3조 (자기 승인 금지)
2. 0.593 [CONFIRMED] docs/rag/rag-docs/backoffice/03-approval.md — 제2조 (승인이 필요한 변경과 승인자)
3. 0.539 [CONFIRMED] docs/rag/rag-docs/backoffice/02-roles-permissions.md — 제6조 (권한 승인과 반영)
4. 0.513 [CONFIRMED] docs/rag/rag-docs/backoffice/03-approval.md — 제4조 (요청과 제출)
5. 0.505 [CONFIRMED] docs/rag/rag-docs/backoffice/06-product-display-search.md — 제9조 (검색 동의어)

### B05 — CDC 에서 소비기한 잔여일수는 어떤 날짜 기준으로 계산하나요?
1. 0.589 [CONFIRMED] docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md — 제3조 (총 소비기간과 잔여일수)
2. 0.472 [CONFIRMED] docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md — 제4조 (최소 잔여일수)
3. 0.454 [CONFIRMED] docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md — 제1조 (목적)
4. 0.443 [CONFIRMED] docs/rag/rag-docs/mfc/06-inventory-quality.md — 제2조 (LOT 과 소비기한)
5. 0.426 [CONFIRMED] docs/rag/rag-docs/cdc_wms/03-replenishment-transfer-departure.md — 제4조 (출발하지 않은 잔량)

### B06 — 주문서를 만든 뒤 가격은 얼마 동안 유지되나요?
1. 0.606 [CONFIRMED] docs/rag/rag-docs/order_claim/01-order-amount.md — 제9조 (가격 유효시간)
2. 0.603 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제7조 (시간 기준)
3. 0.510 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제5조 (주문·결제)
4. 0.450 [CONFIRMED] docs/rag/rag-docs/backoffice/06-product-display-search.md — 제6조 (가격 등록과 적용)
5. 0.431 [CONFIRMED] docs/rag/rag-docs/customer_app/03-wishlist.md — 제6조 (가격과 재고)

### B07 — 라이더 배차 방식에는 어떤 것이 있나요?
1. 0.545 [CONFIRMED] docs/rag/rag-docs/rider_app/02-operation-dispatch.md — 제3조 (배차 방식)
2. 0.529 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제4조 (제안받을 수 있는 라이더)
3. 0.521 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제1조 (목적)
4. 0.506 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제3조 (배차 방식)
5. 0.495 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제7조 (배차 취소)

### A20 — 직원 휴가 신청은 어떻게 하나요?
1. 0.376 [CONFIRMED] docs/rag/rag-docs/mfc/01-worker-qualification-shift.md — 제8조 (휴게)
2. 0.370 [CONFIRMED] docs/rag/rag-docs/backoffice/02-roles-permissions.md — 제5조 (권한 요청)
3. 0.370 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제9조 (정지·복귀·퇴사)
4. 0.367 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제8조 (정지·퇴사)
5. 0.359 [CONFIRMED] docs/rag/rag-docs/backoffice/07-rider-operations.md — 제5조 (소명·이의 신청 결정)

### N01 — 연차는 1년에 며칠 쓸 수 있나요?
1. 0.451 [CONFIRMED] docs/rag/rag-docs/cdc_wms/02-lot-shelf-life.md — 제3조 (총 소비기간과 잔여일수)
2. 0.406 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제6조 (로그인 유지)
3. 0.388 [CONFIRMED] docs/rag/rag-docs/mfc/01-worker-qualification-shift.md — 제4조 (고용 형태)
4. 0.362 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제10조 (인사 현황)
5. 0.356 [CONFIRMED] docs/rag/rag-docs/backoffice/02-roles-permissions.md — 제2조 (권한의 원칙)

### N02 — 회식비는 어떻게 정산하나요?
1. 0.363 [CONFIRMED] docs/rag/rag-docs/customer_app/13-return-exchange.md — 제3조 (비용)
2. 0.353 [CONFIRMED] docs/rag/rag-docs/backoffice/06-product-display-search.md — 제1조 (목적)
3. 0.353 [CONFIRMED] docs/rag/rag-docs/order_claim/06-return-exchange.md — 제4조 (회수비)
4. 0.349 [CONFIRMED] docs/rag/rag-docs/tms/05-completion-settlement.md — 제4조 (운임 계산)
5. 0.347 [CONFIRMED] docs/rag/rag-docs/commerce/02-pricing-promotions.md — 제1조 (목적)

### N03 — 사내 주차 등록은 어디서 신청하나요?
1. 0.365 [CONFIRMED] docs/rag/rag-docs/rider_app/01-signup.md — 제4조 (가입 때 등록하는 정보)
2. 0.318 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제3조 (직원 등록)
3. 0.314 [CONFIRMED] docs/rag/rag-docs/tms/03-loading-departure-monitoring.md — 제4조 (이벤트 기반 관제)
4. 0.314 [CONFIRMED] docs/rag/rag-docs/tms/02-dispatch-planning.md — 제3조 (차량 기준)
5. 0.313 [CONFIRMED] docs/rag/rag-docs/rider_app/05-restriction-withdrawal.md — 제4조 (이의 신청)

### N04 — 사무실 와이파이 비밀번호가 뭐예요?
1. 0.417 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제4조 (회사 메일)
2. 0.407 [CONFIRMED] docs/rag/rag-docs/customer_app/07-delivery-area-address.md — 제4조 (공동현관 비밀번호와 요청 사항)
3. 0.404 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제6조 (로그인 유지)
4. 0.399 [CONFIRMED] docs/rag/rag-docs/backoffice/08-hr-employee-management.md — 제6조 (메일함 처음 비밀번호)
5. 0.383 [CONFIRMED] docs/rag/rag-docs/backoffice/01-staff-account-login.md — 제3조 (계정 발급)

### N05 — 건강검진은 언제 받나요?
1. 0.290 [CONFIRMED] docs/rag/rag-docs/mfc/05-dispatch-handover.md — 제5조 (직원 확인)
2. 0.288 [CONFIRMED] docs/rag/rag-docs/mfc/03-inbound-putaway-move.md — 제4조 (품질검사)
3. 0.286 [CONFIRMED] docs/rag/rag-docs/mfc/03-inbound-putaway-move.md — 제2조 (입고 수령 시간과 물량)
4. 0.283 [CONFIRMED] docs/rag/rag-docs/backoffice/02-roles-permissions.md — 제9조 (기록)
5. 0.283 [CONFIRMED] docs/rag/rag-docs/mfc/02-task-assignment.md — 제8조 (배정 이력)

### N06 — 출장비 정산 기준은 어떻게 되나요?
1. 0.387 [CONFIRMED] docs/rag/rag-docs/tms/05-completion-settlement.md — 제4조 (운임 계산)
2. 0.367 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제1조 (목적)
3. 0.364 [CONFIRMED] docs/rag/rag-docs/commerce/02-pricing-promotions.md — 제1조 (목적)
4. 0.362 [CONFIRMED] docs/rag/rag-docs/delivery/07-earnings-operations.md — 제1조 (목적)
5. 0.358 [CONFIRMED] docs/rag/rag-docs/backoffice/07-rider-operations.md — 제1조 (목적)

### N07 — 라이더 유니폼과 헬멧은 어떤 기준으로 지급하나요?
1. 0.445 [CONFIRMED] docs/rag/rag-docs/delivery/07-earnings-operations.md — 제1조 (목적)
2. 0.430 [CONFIRMED] docs/rag/rag-docs/backoffice/07-rider-operations.md — 제1조 (목적)
3. 0.422 [CONFIRMED] docs/rag/rag-docs/rider_app/01-signup.md — 제2조 (정의)
4. 0.402 [CONFIRMED] docs/rag/rag-docs/delivery/03-dispatch.md — 제1조 (목적)
5. 0.401 [CONFIRMED] docs/rag/rag-docs/delivery/04-pickup-completion-failure.md — 제1조 (목적)

### N08 — MFC 냉장고가 고장 나면 어느 수리업체에 연락하나요?
1. 0.382 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제2조 (회사·시설)
2. 0.358 [CONFIRMED] docs/rag/rag-docs/common/02-business-overview.md — 제3조 (두 가지 물류 흐름)
3. 0.356 [CONFIRMED] docs/rag/rag-docs/common/01-glossary.md — 제4조 (상품·재고)
4. 0.339 [CONFIRMED] docs/rag/rag-docs/customer_app/12-missing-damaged-item.md — 제6조 (냉장·냉동 상품의 품질 문제)
5. 0.337 [CONFIRMED] docs/rag/rag-docs/scm/01-supply-principles.md — 제6조 (고객 판매와의 관계)
