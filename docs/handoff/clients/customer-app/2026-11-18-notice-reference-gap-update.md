# [pmart-backend → pmart-clients] 알림(갱신): 고객앱 레퍼런스 대비 빠진 기능 — 백엔드 쪽 지금 상태 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer_app
- 갱신하는 문서: `2026-11-17-notice-reference-gap.md`(B마트 캡처 대조). 그 문서의 **A(앱만 고치면 되는 것)·C(정책 결정 먼저)는 그대로** 두고, **B(백엔드가 먼저 필요한 것)** 를 2026-11-18 main 계약 기준으로 다시 맞췄다. C 중 결정이 난 것 하나(라이더 위치)는 아래 1로 옮겼다.
- 확인 방법: 계약(`contracts/openapi/*.yaml` — 버전·경로·호출자 종류)과 설정(pmart-config). LAN 에서 고객 토큰으로 하나씩 불러 본 것은 아니다 — 앱에서 막히면 응답 코드를 알려 달라.

## 1. 이제 백엔드가 있는 것 (앱이 붙이면 됨)
| 기능(11-17 B 줄) | 백엔드 지금 | 비고 |
|---|---|---|
| 실시간 ETA·실시간 위치 | delivery-tracking **0.8.1** — `GET /deliveries/{deliveryId}/eta`(고객은 본인 배송의 dropEta), `GET /deliveries/{deliveryId}/stream`(SSE). 미배정·위치 없음은 `LOCATING`·`ETA_PENDING`(수치 없음) | 11-17 의 501 이 아님. LAN ETA 계산 켬(11-17) |
| (C 에서 옮김) 라이더 위치 지도 | 같은 응답의 `riderLocation` — **고객은 픽업 뒤(TO_CUSTOMER·AT_CUSTOMER)만**, 그 전·끝난 뒤 null(0.6.0, 사용자 결정 2026-11-17) | 지도는 그 구간에서만 |
| 최근 검색어 | search **0.2.0** — `GET /me/recent-searches`·`DELETE /me/recent-searches/{keyword}`(고객 토큰 검색이 자동 기록) | |
| 인기 검색어 | 같은 0.2.0 — `GET /search/suggestions` 의 `popular` 가 채워짐 | |
| 앱 푸시 | notification — 단말 토큰 `PUT /me/push-tokens/{deviceId}`(0.3.0, 한 사람 5대), 고객 휴대폰 푸시 7종(배송 출발·완료, 결품 부분 취소, 주문 취소, 환불 완료·늦어짐, 다시 배송비 결제). FCM 등록 토큰(`getToken()`) | 회신 `2026-11-18-reply-customer-push-q6-q8.md`. 2026-11-18 Firebase 를 `pmart-123` 으로 합침 — 새 `google-services.json` 으로 다시 빌드 |
| 문의 답변 알림 | 알림함 `INQUIRY_ANSWERED`(case `InquiryAnswered` 구독) | 알림함만(휴대폰 푸시 7종에는 없음) |
| 문의 사진 첨부 | case — `POST /inquiry-photos`·`/inquiries/{inquiryId}/photos`·`GET /inquiry-photos/{photoId}` | |
| 만족도 조사 | case — `POST /inquiries/{inquiryId}/satisfaction` | |
| 오배송 확인 — 배달 위치 사진 | delivery-execution **0.9.0** — 본인 주문 배송이 전달 완료(COMPLETED)된 뒤 **전달 완료 증빙 사진만** 고객이 볼 수 있음(`GET /deliveries/{deliveryId}/evidence-photos/{photoRef}`, 사용자 결정 2026-11-17) | 라이더 통화 요청은 아래 3 |
| 오배송 뒤 다시 배송비 결제 | order-orchestration — `/redelivery-payments`·`…/payment-attempts`·`…/declines`(ADR-0065, 30분 안) | 푸시 `REDELIVERY_PAYMENT_REQUIRED` |
| 장바구니 최소 주문 금액 진행 바 | order 주문 초안 견적 `Quote.minOrderShortfall`(BR-14 — `max(0, 최소 주문액 − 상품액)`) | **장바구니 응답(cart)에는 아직 없음** — 장바구니 화면에서 쓰려면 견적을 부르거나 cart 에 더해야 함(필요하면 요청) |
| 반품 | claim **0.3.0~** — 반품은 **고객센터 경유**(D-7·ADR-0060, 고객 앱 `type: RETURN` 은 422 — 고객센터로). 앱은 처리 내역 조회(`GET /claims`·`/claims/{id}` — 회수·회수비·검수)와 회수비 결제(`POST /claims/{id}/fee-payment-attempts`) | 11-17 표의 "반품·교환 신청 전체(사유·사진·비용 미리 보기)"를 **앱에서 하는 것은 하지 않기로** — 결정 |
| (새로) 고객센터 주문 취소 | claim **0.4.0**(2026-11-18) — 상담원이 상품준비 전 주문을 대신 취소하면 클레임이 고객 계정에 붙음 | 앱은 처리 내역에 보이기만 하면 됨(채널 `CS`) |

## 2. 아직 백엔드가 없는 것 (필요하면 요청서로 — PC 1)
| 기능 | 지금 | 메모 |
|---|---|---|
| 리뷰 작성 알림 | 알림 종류 없음 | 배송 완료 뒤 리뷰 권유 — 요청하면 notification 템플릿·조건부터 |
| 주문 내역 카드의 상품명·썸네일·배송 방식 | `OrderSummary` = 주문번호·상태·주문 시각·총액·상품 수(`itemCount`)만 | 상품 표시가 필요하면 order 목록 칸 추가 요청 |
| 주문 내역 검색·기간 필터 | `GET /orders` 는 `status` 필터만 | |
| 주문 내역 삭제(숨기기) | API 없음 | |
| 교환 | claim 교환 접수 없음(EXCHANGE 422) | 정책부터 |
| 현금영수증 | BR-17 그대로 | |
| 품절 처리 "다음에도 적용" | BR-21 그대로 | |
| 배달 지연 제보 | 고객용 API 없음 | 지연 알림 `DELIVERY_MAY_BE_DELAYED`(알림함)는 서버가 보냄 |
| 오배송 — 라이더 통화 요청 | 고객용 API 없음 | 안심번호 정책(C)과 같이 |
| 상담원 실시간 채팅 | 없음 — 문의는 비동기 답변(사진·만족도 있음) | 고객센터 AI 채팅(RAG)은 따로 있음 |
| FAQ 목록·검색 | FAQ 목록·검색 API 없음 — 고객 AI 상담(RAG, ADR-0051)이 FAQ 원문으로 답함 | 목록 화면이 필요하면 요청 |
| 단위 가격("100g당") | BR-19 미정 | |
| 배송 지도의 매장 위치 | center-master 센터 조회는 직원 전용 | 고객용 매장 좌표 공개는 정책부터 |

## 3. 그대로(11-17 문서)
- **A. 앱만 고치면 되는 것** — 그대로. 특히 저장한 주소 수정·삭제는 백엔드가 있다(`PATCH`·`DELETE /me/addresses/{addressId}`).
- **C. 정책 결정이 먼저** — 라이더 위치 지도(위 1로 옮김) 말고 그대로.

## 회신
틀린 줄·막힌 호출은 `docs/handoff/clients/customer-app/` 회신 파일 또는 PR 본문으로. 2의 줄 중 먼저 필요한 것을 알려 주면 PC 1 이 요청서로 만든다.
