# CLAUDE.md (Root)

<!-- [ ] 표시는 프로젝트에 맞게 수정. 이 파일은 매 세션 로드되므로 "전체 공통"만 둔다.
     서비스 내부 규칙은 services/{name}/CLAUDE.md 에 둔다. -->

## 시스템 개요
- [시스템 한 줄 설명]
- 전체 비즈니스 흐름: @docs/business/overview.md
- 서비스 책임 경계: @docs/architecture/service-map.md
- 상세 문서(필요 시 읽을 것):
  - `docs/business/flows/` — 서비스 간 유스케이스, saga, 보상 트랜잭션
  - `docs/business/glossary.md` — 공통 용어
  - `docs/architecture/events.md` — 이벤트 카탈로그 (발행자 → 구독자)
  - `docs/adr/` — 아키텍처 결정 기록

## 서비스 목록
PMart의 목표 구성을 **업무 서비스 42개 + 플랫폼·지원 서비스 8개**로 제안합니다. 다음은 확장 설계이며, 현행 문서의 범위를 넓히는 부분도 포함합니다.

**1. Commerce — 9개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `product-service` | 승인 상품·SKU·바코드·단위·카테고리·취급 속성 |
| `product-content-service` | 상품 설명·이미지·상세 콘텐츠 |
| `customer-service` | 고객 프로필·주소록·고객 설정 |
| `cart-service` | 장바구니·찜·구매 전 선택 정보 |
| `review-service` | 리뷰·평점·신고·노출 관리 |
| `search-service` | 검색 색인·키워드 검색·필터 |
| `storefront-service` | 전시관·배너·진열 순서 |
| `pricing-service` | 기본 판매가격·기간별 가격·가격 견적 |
| `promotion-service` | 할인 규칙·쿠폰 발급·확보·사용·복구 |

가격과 할인 정책의 소유권을 구분합니다. `pricing-service`가 최종 가격 견적을 구성하고, 할인·쿠폰의 적용 판단과 사용 권리는 `promotion-service`가 확정합니다.

**2. Order & Payment — 5개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `order-service` | 주문·주문행·주문 시점 금액·고객 약속·주문 상태 |
| `order-orchestration-service` | 주문 Saga·단계별 실행·시간 초과·보상 진행 |
| `fulfillment-routing-service` | 이행 후보 MFC 평가·배정 계획·재배정 계획 |
| `claim-service` | 취소·반품·교환 심사·승인 범위·환불 의도 |
| `payment-service` | 결제·승인 취소·환불 실거래·콜백·PG 대사 |

주문 상태의 정본은 `order-service`입니다. 오케스트레이터는 **업무 진행 상태**를 소유하며, 주문·결제·재고 성공을 대신 확정하지 않습니다.

**3. SCM — 4개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `supplier-service` | 공급사·거래 조건·공급 가능 상품 |
| `procurement-service` | 구매발주·변경·납품 예정·발주 이행 |
| `demand-planning-service` | 수요 예측·예측 버전·계획 입력 |
| `replenishment-service` | 센터별 보충 필요량·배분·사업승인·공급 이행 추적 |

`Supplier`와 `Procurement`는 현행 SCM 문서의 범위 밖이므로 **이번 목표 아키텍처에서 추가하는 영역**입니다. 실제 CDC 입고 확정은 SCM이 담당하지 않습니다.

**4. CDC WMS — 5개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `cdc-inventory-service` | CDC 현물·예약·LOT별 잔량·수불원장·출고 전기·Transit 대사 |
| `cdc-inbound-service` | 실수령·검수·입고 차이·적치 작업 |
| `cdc-location-service` | 로케이션 정의·보관 조건·공간 용량·공간 예약 |
| `cdc-outbound-service` | Wave·피킹·검품·포장·상차·출고 작업 |
| `cdc-quality-service` | 품질 사건·격리 판단·해제·폐기 승인 |

재고 수량을 실제로 변경하는 주체는 **`cdc-inventory-service` 하나**입니다. 입고·출고·품질 서비스는 자기 업무를 확정한 뒤 재고 반영을 요청하고, 반영 결과를 추적합니다.

**5. MFC — 6개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `mfc-inventory-service` | 센터별 현물·예약·LOT·위치별 잔량·수불원장·인계 전기 |
| `mfc-inbound-service` | TMS 화물 실수령·검수·적치·수령 차이 |
| `mfc-location-service` | 보관 위치·선반·공간 제약·공간 예약 |
| `mfc-fulfillment-service` | 피킹·포장·취소 작업 중지·실제 인계 절차 |
| `mfc-capacity-service` | 작업자 가용성·처리 여력·작업 슬롯 확보 |
| `mfc-quality-service` | 품질 차단·반품 검수·재판매 판정·폐기 승인 |

이 구성은 기존의 **MFC 단일 배포 경계를 여러 서비스로 변경하는 제안**입니다. 업무 전체는 여전히 MFC 도메인에 속하며, 외부에 별도 MFC WMS 정본을 추가하는 방식은 아닙니다.

`mfc-inventory-service`는 재고와 예약을 함께 소유합니다. `stock-lock-service`를 별도 원장으로 만들지는 않습니다.

**6. Middle-mile TMS — 5개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `transport-planning-service` | 수송 요청·운행 계획·정차·적재 계획 |
| `transport-routing-service` | 간선 경로·시간창·예상 이동시간 계산 |
| `fleet-service` | 차량·간선기사·자격·가용성·배정 점유 |
| `transport-execution-service` | 실제 운행·출발·도착·인계 증빙·운송 사고 |
| `cold-chain-service` | 온도 데이터·허용 범위 감시·이탈 사건 |

`cold-chain-service`가 온도 이탈을 감지하더라도 재고를 직접 폐기하거나 품질 정상 여부를 확정하지 않습니다. 해당 센터의 품질 서비스에 판단 근거를 전달합니다.

**7. Last-mile Delivery — 6개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `delivery-serviceability-service` | 배송 권역·담당 MFC 매핑·배송 가능 조건 |
| `delivery-capacity-service` | 시간대별 배송 여력·배송 슬롯 확보 |
| `rider-service` | 라이더 프로필·자격·근무 가능 상태 |
| `delivery-dispatch-service` | 라이더 후보·배정 제안·수락·배정 점유·재배차 |
| `delivery-execution-service` | 픽업·배송·회수·반납·실행 상태·증빙 |
| `delivery-tracking-service` | 위치 수집·이동 이력·추적 조회·ETA 계산 |

라이더 등록 정보는 `rider-service`, 특정 배송의 라이더 배정 권리는 `delivery-dispatch-service`, 실제 배송 상태는 `delivery-execution-service`가 소유합니다.

**8. Platform & Support — 8개**

| 마이크로서비스 | 독립 책임 |
|---|---|
| `api-gateway` | API 라우팅·요청 제한·인증 토큰 검증 |
| `auth-service` | 인증·토큰·서비스 신원·역할/권한 정보 |
| `config-service` | 환경별 애플리케이션 설정 배포 |
| `discovery-service` | 서비스 등록·인스턴스 조회 |
| `backoffice-bff` | 관리자 화면별 조회 조합·도메인 명령 전달 |
| `notification-service` | 고객·운영 업무 알림·발송 이력·재시도 |
| `audit-service` | 감사 사건 수집·조회·보존 |
| `ai-assistant-service` | RAG·정책 설명·권한 있는 도메인 API 호출 |

## Tech Stack (공통)

## MSA 핵심 규칙
**서비스 경계**
- 다른 서비스의 DB에 직접 접근하지 않는다. 동기 조회는 API, 상태 전파는 이벤트로만.
- 서비스 간 domain 클래스를 공유하지 않는다.
- 로직이 어느 서비스 책임인지 불명확하면 `service-map.md`를 확인하고, 그래도 모호하면 질문한다.

**통신**
- 동기 호출(Feign)에는 timeout, retry, circuit breaker를 반드시 설정한다.
- 동기 호출 체인은 최대 2 hop. 그 이상이면 이벤트 기반으로 설계한다.
- 이벤트 발행은 Outbox 패턴으로 (DB 트랜잭션과 발행의 원자성 보장).
- 이벤트 consumer는 멱등하게 구현한다 (eventId 기반 중복 처리).

**분산 트랜잭션**
- 2PC 사용 금지. Saga(choreography 기본)와 보상 트랜잭션으로 처리한다.
- 새 saga를 만들거나 수정하면 `docs/business/flows/`를 같이 갱신한다.

**계약 변경**
- API/이벤트 스키마 변경은 `contracts/`를 먼저 수정한다.
- 하위 호환 유지: 필드 추가만 허용, 삭제/타입 변경/rename은 새 버전으로 (`/v2`, `OrderCreatedV2`).
- 계약을 바꾸면 영향받는 모든 consumer 서비스를 찾아서 목록으로 보고한다.

## 작업 완료 기준
1. 변경한 서비스의 빌드/테스트 통과
2. 계약 변경 시 `contractTest` 통과 + 영향 서비스 목록 보고
3. 비즈니스 규칙/흐름이 바뀌었다면 관련 docs를 같은 커밋에서 갱신
