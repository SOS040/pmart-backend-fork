# 경로별 사용자 종류 — 추정 표시 검토 목록 (2026-11-10)

계약(`contracts/openapi/*.yaml`)의 외부 작업 400개에 `x-pmart-user-types` 를 붙였다(ADR-0054 §6.1, 허용 목록 정본). 아래는 근거(서비스 코드의 종류 검사)가 없어 **추정**으로 정한 것 — `x-pmart-user-types-note` 가 붙은 작업. 게이트웨이는 지금 RECORD 모드(막지 않고 기록)라 틀려도 요청이 막히지 않는다. ENFORCE 로 바꾸기 전에 이 목록을 확정한다.

총 94개. 고치는 법: 계약의 `x-pmart-user-types` 를 고치고(확정되면 note 삭제) `PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'`.

| 계약 | 메서드 | 경로 | operationId | 정한 종류 | 요약 | 추정 이유 |
|---|---|---|---|---|---|---|
| case-service | GET | /help-requests/{intakeId} | getHelpRequest | RIDER,OPERATOR | 도움요청 조회 | 구현 없음. 요청한 라이더 본인과 처리하는 운영자가 볼 것으로 봄 |
| cdc-inbound-service | POST | /putaway-tasks | createPutawayTask | OPERATOR | 적치 지시 생성 (cdc-location 후보 산출 → 공간 선점 RESERVED) | 구현 없음. CDC 센터 직원(관리자·작업자) 업무로 봄 |
| cdc-inbound-service | GET | /putaway-tasks/{taskId} | getPutawayTask | OPERATOR | 적치 지시 조회 (Q = C + U + K 진행 수량, 선점·전기 참조) | 구현 없음. CDC 센터 직원(관리자·작업자) 업무로 봄 |
| cdc-inbound-service | POST | /putaway-tasks/{taskId}/cancellations | cancelPutawayTask | OPERATOR | 적치 지시 취소 (미완료 잔량 U → K, 선점 해제). 완료분 C는 되돌리지 않음 | 구현 없음. CDC 센터 직원(관리자·작업자) 업무로 봄 |
| cdc-inbound-service | POST | /replenishment-moves | createReplenishmentMove | OPERATOR | 피킹 위치 보충 MOV 생성 (최소 이하 자동 또는 수동) → ReplenishmentMoveCreated | 구현 없음. 수동 생성은 CDC 센터 직원(자동 생성은 서비스 안에서)으로 봄 |
| cdc-inbound-service | GET | /replenishment-moves/{moveId} | getReplenishmentMove | OPERATOR | 보충 MOV 조회 (산식 입력값·연결 적치 지시·진행) | 구현 없음. CDC 센터 직원(관리자·작업자) 업무로 봄 |
| cdc-inbound-service | POST | /asn | createAsn | OPERATOR | ASN 등록 (공급사 입고 예정) → 실수령·검수·입고 차이 | 3단계 자리표시자. 공급사 사용자 종류가 없어 직원 대리 등록으로 봄 |
| cdc-inbound-service | GET | /asn/{asnId} | getAsn | OPERATOR | ASN 조회 | 3단계 자리표시자. CDC 센터 직원 조회로 봄 |
| cdc-location-service | GET | /locations | listLocations | OPERATOR | LOC 목록 조회 (센터·온도·구역·상태 필터) | 구현 없음. CDC 위치 기준정보는 센터 직원 업무로 봄 |
| cdc-location-service | POST | /locations | createLocation | OPERATOR | LOC 등록 (DRAFT 생성, 센터 기준정보 BO-06 승인 후 현장 설정) | 구현 없음. CDC 위치 기준정보는 센터 직원 업무로 봄 |
| cdc-location-service | GET | /locations/{locationCode} | getLocation | OPERATOR | LOC 상세 (상태·용량·점유·미완료 예약) | 구현 없음. CDC 위치 기준정보는 센터 직원 업무로 봄 |
| cdc-location-service | POST | /locations/{locationCode}/state-changes | changeLocationState | OPERATOR | LOC 상태 전환 (ACTIVATE / BLOCK / UNBLOCK / DEACTIVATE / RETIRE) → LocStateChanged | 구현 없음. CDC 위치 기준정보는 센터 직원 업무로 봄 |
| cdc-outbound-service | POST | /waves | createWave | OPERATOR | Wave 생성 (여러 TRF 묶음, 출발 창·차량 유형 기준) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | GET | /waves/{waveId} | getWave | OPERATOR | Wave 조회 (상태·TRF·작업 요약) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /waves/{waveId}/releases | releaseWave | OPERATOR | Wave 릴리스 (PLANNED→RELEASED, 피킹 Task 생성) → WaveStateChanged | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | GET | /tasks | listTasks | OPERATOR | 작업 목록 (Wave·상태·작업자 기준) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | GET | /tasks/{taskId} | getTask | OPERATOR | 작업 상세 (피킹 결과·부족 포함) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /tasks/{taskId}/pick-results | reportPickResult | OPERATOR | 실피킹 결과 등록 (부족 보고 → PickShortageReported → 재할당 또는 축소) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /sorting-results | reportSortingResult | OPERATOR | 분류 결과 등록 (BOX SEALED / PLT READY, LPN 구성·계보) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /inspection-results | reportInspectionResult | OPERATOR | 검품 결과 등록 (LPN 단위 합격/불합격, 온도·봉인 확인) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /loading-results | reportLoadingResult | OPERATOR | 가상 상차 결과 등록 (LOADED ≠ 출발, 차량·구획별 LPN) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | GET | /work-results/{workResultId} | getWorkResult | OPERATOR | 분류/검품/상차 결과 조회 (명령 짝 조회) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | POST | /transfers/{trfId}/pre-departure-cancellations | cancelBeforeDeparture | OPERATOR | 미출발 취소·축소 (실물 하차·복귀, cdc-inventory 할당 해제, TMS 배정 대사) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| cdc-outbound-service | GET | /transfers/{trfId}/pre-departure-cancellations/{requestId} | getPreDepartureCancellation | OPERATOR | 미출발 취소 요청 결과 조회 (PENDING/APPLIED/REJECTED/UNKNOWN) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| center-master-service | GET | /centers | listCenters | OPERATOR | 센터 목록 (유형·상태·온도대 필터) | 코드에 종류 검사 없음, 백오피스만 호출(고객·라이더 화면 사용처 없음) |
| center-master-service | GET | /centers/{centerId} | getCenter | OPERATOR | 센터 조회 (버전 포함; version 쿼리로 과거 버전) | 코드에 종류 검사 없음, 백오피스만 호출(고객·라이더 화면 사용처 없음) |
| center-master-service | GET | /centers/{centerId}/calendar | getCenterCalendar | OPERATOR | 영업 캘린더 조회 (일자·시간표 종류; 휴일·임시 제한·버전·승인자) | 코드에 종류 검사 없음, 백오피스만 호출(고객·라이더 화면 사용처 없음) |
| claim-service | GET | /claims/{claimId}/decisions/{requestId} | getClaimDecision | OPERATOR | 심사 결정 결과 조회 | 미구현, 심사 결정(운영자 BO-14)을 낸 쪽의 결과 조회로 봄 |
| claim-service | GET | /claims/{claimId}/withdrawals/{requestId} | getClaimWithdrawal | CUSTOMER | 철회 요청 결과 조회 | 미구현, 철회(고객)를 요청한 쪽의 결과 조회로 봄 |
| cold-chain-service | GET | /excursions | listExcursions | OPERATOR | 이탈 사건 목록 (tripId 또는 centerId 중 하나 필수) | 호출자 언급 없음, 품질·관제 운영 화면 조회로 봄 |
| cold-chain-service | GET | /excursions/{excursionId} | getExcursion | OPERATOR | 이탈 사건 상세 (구획·기간·영향 화물·측정 이력) | 호출자 언급 없음, 품질·관제 운영 화면 조회로 봄 |
| delivery-dispatch-service | GET | /dispatch-requests/{requestId} | getDispatchRequest | OPERATOR | 배차 요청 결과 조회 (시도·제안·응답 이력) | 미구현, 배차를 요청한 점원의 조회로 봄 |
| delivery-dispatch-service | GET | /deliveries/{deliveryId}/assignment-cancellations/{requestId} | getAssignmentCancellation | RIDER,OPERATOR | 배차 취소 결과 조회 | 미구현, 취소를 요청한 쪽(라이더·점원·운영자)의 조회로 봄 |
| delivery-execution-service | POST | /deliveries/{deliveryId}/help-requests | createHelpRequest | RIDER | 도움요청 (→ case-service 지원센터) | 미구현, 라이더 앱 도움요청으로 봄 |
| delivery-execution-service | GET | /deliveries/{deliveryId}/help-requests/{requestId} | getHelpRequest | RIDER | 도움요청 상태 조회 | 미구현, 도움요청을 낸 라이더의 조회로 봄 |
| delivery-serviceability-service | GET | /delivery-conditions | getDeliveryConditions | CUSTOMER,GUEST | 배송 가능 조건 조회 (방식·수단·중량·부피·보냉·시간 제한) | 코드에 종류 검사 없음, 게이트웨이 비회원 경로·주문 화면용(운영자 화면 여부 불명) |
| demand-planning-service | GET | /forecasts | getForecasts | OPERATOR | 예측 조회 (버킷·기준·신뢰도·품절 구간 플래그) | 주 호출자는 replenishment(서비스, 게이트웨이 밖), 사람은 운영자 화면으로 봄 |
| fleet-service | GET | /vehicles | listVehicles | OPERATOR | 실차 목록 (상태·차종·온도대·거점) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| fleet-service | GET | /vehicles/{vehicleId} | getVehicle | OPERATOR | 실차 상세 | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| fleet-service | GET | /vehicles/{vehicleId}/next-availability | getVehicleNextAvailability | OPERATOR | 다음 가용 시각 (복귀 + 준비 시간; 기사 가용은 별도) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| fleet-service | GET | /drivers | listDrivers | OPERATOR | 간선기사 목록 (자격·가용 상태) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| fleet-service | GET | /drivers/{driverId} | getDriver | OPERATOR | 간선기사 상세 | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| mfc-fulfillment-service | GET | /packages/{packageId}/handover-resolution | getHandoverResolution | OPERATOR,RIDER | QueryHandoverResolution — 포장 인계 판정 조회 (stopRequest·fence·inventory commit 결합) | 코드에 호출자 종류 검사 없음, 인계 판정은 라이더 앱·점원 단말 둘 다 조회 |
| notification-service | GET | /me/notifications | listMyNotifications | CUSTOMER,RIDER | 알림함 목록 (본인만. 발송 문구는 당시 사실 보존, 최신 상태는 목적지에서 조회) | 서비스 지도는 고객 알림함, Recipient.principalType 에 RIDER 도 있어 포함(운영자 제외), 구현 없음 |
| notification-service | GET | /me/notifications/{notificationId} | getMyNotification | CUSTOMER,RIDER | 알림 상세 (목적지 참조 포함. 접근 불가 주문은 내용 미노출) | 서비스 지도는 고객 알림함, Recipient.principalType 에 RIDER 도 있어 포함(운영자 제외), 구현 없음 |
| notification-service | POST | /me/notifications/{notificationId}/reads | markNotificationRead | CUSTOMER,RIDER | 읽음 처리 (NOTICE_UNREAD → NOTICE_READ. 배송 수령·거래 확정 근거가 아님) | 서비스 지도는 고객 알림함, Recipient.principalType 에 RIDER 도 있어 포함(운영자 제외), 구현 없음 |
| notification-service | GET | /me/notification-settings | getMyNotificationSettings | CUSTOMER,RIDER | 앱 수신 설정 조회 (채널·카테고리별. 마케팅 동의·OS 권한 상태는 참조만) | 앱 수신 설정(고객 앱 중심), 라이더 앱도 같은 설정을 쓴다고 봄, 구현 없음 |
| notification-service | POST | /me/notification-setting-changes | changeMyNotificationSettings | CUSTOMER,RIDER | 앱 수신 설정 변경 (거래 안내는 끌 수 없음. 마케팅은 customer 동의와 함께 확인) | 앱 수신 설정(고객 앱 중심), 라이더 앱도 같은 설정을 쓴다고 봄, 구현 없음 |
| notification-service | GET | /me/notification-setting-changes/{requestId} | getMyNotificationSettingChange | CUSTOMER,RIDER | 수신 설정 변경 결과 조회 | 앱 수신 설정(고객 앱 중심), 라이더 앱도 같은 설정을 쓴다고 봄, 구현 없음 |
| order-service | POST | /orders/{orderId}/reservation-time-changes | requestReservationTimeChange | CUSTOMER | 예약배달 시간 변경 (상품준비 진입 전만) → S4 | 미구현, 본인 주문의 예약 시간 변경으로 봄 |
| order-service | GET | /orders/{orderId}/reservation-time-changes/{requestId} | getReservationTimeChange | CUSTOMER | 시간 변경 요청 결과 조회 (PENDING/CONFIRMED/REJECTED/UNKNOWN) | 미구현, 변경을 요청한 고객의 조회로 봄 |
| pricing-service | GET | /price-versions/{sku} | listPriceVersions | OPERATOR | SKU 가격 버전 이력 (효력일 순) | 코드에 호출자 검사 없음. 가격 버전 이력은 백오피스 화면용으로 봄 |
| procurement-service | POST | /purchase-orders | createPurchaseOrder | OPERATOR | 발주 생성 → PurchaseOrderIssued | 3단계 자리표시자. 발주는 본사 직원 업무로 봄 |
| procurement-service | GET | /purchase-orders/{purchaseOrderId} | getPurchaseOrder | OPERATOR | 발주 상세 조회 | 3단계 자리표시자. 발주는 본사 직원 업무로 봄 |
| procurement-service | POST | /purchase-orders/{purchaseOrderId}/cancellations | cancelPurchaseOrder | OPERATOR | 발주 취소 요청 | 3단계 자리표시자. 발주는 본사 직원 업무로 봄 |
| product-content-service | GET | /product-contents/{productId}/rating-summary | getRatingSummary | CUSTOMER,OPERATOR | 평점·리뷰 수 집계 (review 이벤트 투영) | 미구현, 고객 상품 화면 평점 집계(비회원 경로 아님)·운영자 조회 |
| product-content-service | POST | /product-contents/{productId}/publications | publishProductContent | OPERATOR | 콘텐츠 게시 (approval BO-05 승인 결과 참조) | 미구현(501), 승인 결과를 게시하는 운영 명령 |
| product-content-service | GET | /product-contents/{productId}/publications/{requestId} | getPublication | OPERATOR | 게시 요청 결과 조회 | 미구현(501), 게시 명령의 짝 조회 |
| product-service | GET | /products | listProducts | OPERATOR | 상품 목록 조회 (카테고리·판매 상태 필터) | 코드에 종류 검사 없음, 고객은 product-content·search 를 씀(백오피스 상품 목록) |
| product-service | GET | /products/{productId} | getProduct | OPERATOR | 상품 상세 조회 (승인 버전·SKU 목록·규격 포함) | 코드에 종류 검사 없음, 고객 상세는 product-content(백오피스용) |
| product-service | GET | /skus/{sku} | getSku | OPERATOR | SKU 조회 (구성 낱개 수·중량·판매 단위·판매 상태) | 코드에 종류 검사 없음, 백오피스 SKU 조회 |
| product-service | GET | /barcodes/{barcode} | resolveBarcode | OPERATOR | 바코드 해석 (barcode → SKU·상품) | 코드에 종류 검사 없음, 백오피스 상품 목록·현장 스캔용 |
| review-service | DELETE | /reviews/{reviewId} | deleteReview | CUSTOMER | 리뷰 삭제 (삭제 후 재작성 불가. ReviewDeleted 발행) | 작성자 본인 삭제로 봄(운영 측 차단은 content/case 경로), 서비스 미구현 |
| rider-service | POST | /riders/signups | createRiderSignup | RIDER | 라이더 가입 신청 (전화 본인확인 후, 면허·주민번호 중복 차단) | 가입 전 라이더 auth(Google 로그인) 토큰으로 호출한다고 봄, 구현 코드 없음 |
| rider-service | GET | /riders/signups/{requestId} | getRiderSignup | RIDER | 가입 신청 결과 조회 | 가입 신청자(라이더 토큰) 조회로 봄, 구현 코드 없음 |
| storefront-service | POST | /storefront/layout-publications | publishLayout | OPERATOR | 전시 구성 발효 (approval BO-05 승인 결과 참조) | 미구현, 승인(BO-05) 결과를 반영하는 백오피스 작업으로 봄 |
| storefront-service | GET | /storefront/layout-publications/{requestId} | getLayoutPublication | OPERATOR | 전시 발효 결과 조회 (발효 layoutVersion·제외된 상품) | 미구현, 발효 요청자(운영자)의 결과 조회로 봄 |
| supplier-service | GET | /suppliers | listSuppliers | OPERATOR | 공급사 목록 조회 | 3단계 자리표시자(코드 없음), 공급사 마스터는 직원 관리 |
| supplier-service | POST | /suppliers | registerSupplier | OPERATOR | 공급사 등록 → SupplierRegistered | 3단계 자리표시자(코드 없음), 공급사 마스터는 직원 관리 |
| supplier-service | GET | /suppliers/{supplierId} | getSupplier | OPERATOR | 공급사 상세 조회 | 3단계 자리표시자(코드 없음), 공급사 마스터는 직원 관리 |
| supplier-service | POST | /suppliers/{supplierId}/contracts | createSupplierContract | OPERATOR | 공급 계약·납품 조건 등록 | 3단계 자리표시자(코드 없음), 공급사 마스터는 직원 관리 |
| transport-execution-service | GET | /trips/{tripId} | getTrip | OPERATOR | 운행 조회 (상태·정차·출발 사실·사건·ETA 요약) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/pre-departure-checks | recordPreDepartureCheck | OPERATOR | 출발 전 실상차 대조 등록 (타 MFC 혼입·누락·봉인·구획) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/departures | recordDeparture | OPERATOR | 실제 출발 사실 등록 → TruckActuallyDeparted (departureId 발급) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/stops/{stopId}/arrivals | recordStopArrival | OPERATOR | 정차 도착 등록 → StopArrived (수량 증거 아님) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/stops/{stopId}/unloading-starts | recordUnloadingStart | OPERATOR | 하차 시작 등록 (하차면 배정 확인) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/stops/{stopId}/handovers | recordHandover | OPERATOR | 인계 증빙 등록 → TransportHandoverEvidenceRecorded (TIN 아님) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | GET | /trips/{tripId}/stops/{stopId}/handovers/{handoverId} | getHandover | OPERATOR | 인계 증빙 조회 | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/stops/{stopId}/departures | recordStopDeparture | OPERATOR | 정차 출차 등록 (인계 기록 후에만) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/incidents | raiseTransportIncident | OPERATOR | 운행 사건 등록 → TransportIncidentRaised (환적·재출발 자동 없음) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | GET | /trips/{tripId}/incidents | listTransportIncidents | OPERATOR | 운행 사건 목록 | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | GET | /trips/{tripId}/incidents/{incidentId} | getTransportIncident | OPERATOR | 운행 사건 조회 | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | GET | /trips/{tripId}/eta | getTripEta | OPERATOR | 최신 ETA 조회 (최초 약속 originalPromise 보존) | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | POST | /trips/{tripId}/physical-ends | recordPhysicalEnd | OPERATOR | 운행 물리적 종료 등록 (마지막 정차 후 복귀) → TripPhysicallyEnded | 미구현, 간선 기사용 사용자 종류 없음(현장 담당자·운영자 기준) |
| transport-execution-service | GET | /trips/{tripId}/cost-basis | getTripCostBasis | OPERATOR | 비용 근거 조회 (요율 버전·대기·추가 정차; 요율 미등록이면 NOT_COMPUTABLE) | 미구현, 비용 근거는 운영자 조회로 봄 |
| transport-planning-service | GET | /transport-requests/{transportRequestId} | getTransportRequest | OPERATOR | 운송 요청 조회 (상태·보류 사유·현재 배정) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | POST | /plan-candidates | computePlanCandidates | OPERATOR | 운송 후보 계산 (직송/순회/회차; routing 후보 계산 호출) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | GET | /plan-candidates/{candidateSetId} | getPlanCandidateSet | OPERATOR | 후보 집합 조회 (계산 요청의 짝 조회) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | POST | /assignments | createAssignment | OPERATOR | 후보 채택 → 배정 생성 및 S8 자원 예약 시작 | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | GET | /assignments/{assignmentId} | getAssignment | OPERATOR | 배정 조회 (상태·버전·자원 예약별 결과) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | POST | /assignments/{assignmentId}/changes | requestAssignmentChange | OPERATOR | 출발 전 배정 변경 (TMS-D06; 실제 출발 사실 선확인) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | GET | /assignments/{assignmentId}/changes/{requestId} | getAssignmentChange | OPERATOR | 배정 변경 요청 결과 조회 | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
| transport-planning-service | GET | /trips/{tripId}/instructions | getTripInstructions | OPERATOR | 기사 지시서 조회 (최신 버전만 유효; SUPERSEDED 버전은 무효) | 서비스 코드 없음, 간선기사는 사용자 종류가 없음(RIDER 는 배송 라이더)이라 운영자 조회로 둠 |
| transport-planning-service | GET | /unplanned | listUnplanned | OPERATOR | 미편성 운송 요청 목록 (일자별, 사유 분류) | 서비스 코드 없음, 센터·운송 운영 업무라 직원 화면용 |
