# 백오피스 고객센터·라이더 운영 메뉴용 직원 API (delivery 영역에 요청)

- 보낸 영역: platform(PC 1), 2026-11-15
- 받는 서비스: **rider-service**, **delivery-execution-service**, **delivery-dispatch-service**(모두 delivery 영역 — PC 5)
- 왜: 사용자 결정(2026-11-15) — 기존 백오피스(`web/backoffice`)에 **고객센터**·**라이더 운영** 메뉴 묶음을 붙인다(MFC 는 따로 앱). PC 1 이 API 를 점검해 보니 처리 API(제한·검토·실패 확정·배차 취소)는 있지만 **화면 입구가 되는 목록·검색이 없다**(배송 ID·라이더 ID 를 미리 알아야만 쓸 수 있음).
- 같이 하는 일(PC 1, platform): employee-auth 역할 목록에 **`RIDER_OPS`** 추가(pmart-config), case-service R-5(상담원 라이더 컴플레인 기록 → `RiderComplaintRecorded`), order-service 직원용 주문 검색(O-6), 백오피스 화면 요청서. 시험 계정(`CS_AGENT`+`RIDER_OPS`)은 PC 1 이 LAN 에 만든다.
- 진행: 이 파일대로 구현해 PR(브랜치 예 `delivery/backoffice-staff-apis`). 계약 버전을 올리고 게이트웨이 허용 목록 생성 파일을 다시 만든다. 아래 경로·이름은 제안 — 기존 계약 관례와 다르면 관례를 따르고 PR 에 적는다.

## 역할 (사용자 결정 2026-11-15)

| 역할 | 뜻 |
|---|---|
| `RIDER_OPS`(새) | 라이더 운영 — "라이더 운영 전용 역할을 만든다"(사용자 "응"). 지금 OPERATOR_ADMIN 만 할 수 있는 라이더 운영 일을 이 역할도 한다 |
| `CS_AGENT` | 고객센터 상담원 — **배차 상태 조회를 연다**(사용자 "응"). 고객 문의("라이더 배정됐나요")에 답하려고 |
| `OPERATOR_ADMIN` | 지금처럼 전부 |

## BOD-1. 라이더 운영 API 에 `RIDER_OPS` 권한 (rider·execution·dispatch)

지금 코드 주석에 "지원센터·배송 역할이 생기면 더한다"고 적힌 곳에 `RIDER_OPS` 를 더한다.

| 서비스 | 곳 | 지금 | 바꿀 것 |
|---|---|---|---|
| rider | `RiderService` 제한 걸기·바꾸기·풀기·이력, 제한 검토 목록·결정(≈398–415행 `OPERATOR_ADMIN` 검사) | OPERATOR_ADMIN 만 | + `RIDER_OPS` |
| execution | `Access.operatorCanSee`(배송·도움요청 조회), `Access.operatorCanDecide`(전달 실패 확정·반려) | OPERATOR_ADMIN·CS_AGENT·그 센터(조회)/MFC_SUPERVISOR(결정) | + `RIDER_OPS` (둘 다) |
| dispatch | `DeliveryQueries`(배차 상태 조회), `CancellationService`(픽업 전 배차 취소·결과 조회) | 그 센터 직원·OPERATOR_ADMIN(취소 조회는 CS_AGENT 도) | + `RIDER_OPS`(조회·취소). 취소 사유가 OPERATOR 면 approvalRef 필수는 그대로 |

## BOD-2. `CS_AGENT` 배차 상태 조회 (dispatch)

- `GET /api/v1/deliveries/{id}/dispatch`(`DeliveryQueries`) 에 `CS_AGENT` 를 더한다(조회만 — 취소는 아님. ADR-0060 "고객센터 취소 못함"과 같은 방향).

## BOD-3. 직원용 라이더 목록·검색·상세 (rider)

- 지금: OPERATOR 용 `/riders`, `/riders/{id}` 없음(내부용만) → 라이더 운영 화면에서 riderId 를 찾을 수 없다.
- 제안: `GET /api/v1/riders`(직원) — 필터 `phone`(**정확히 일치만**, 부분 검색 금지), `name`(앞부분 일치 정도는 정할 것), `restriction`(NORMAL·DISPATCH_RESTRICTED·SUSPENDED·TERMINATED), `operatingStatus`, `operatingMfcId`, `vehicleType`, 페이지(최대 50), 정렬 최근 가입·최근 상태 변경. 목록 응답의 전화번호는 가린다(예 `010-****-1234`).
- `GET /api/v1/riders/{riderId}`(직원) — 기본 정보(이름·전화 전체·수단·운행 상태·신규 배차·운행 MFC·제한 상태·사유·기한·가입일·마지막 heartbeat). **계좌·거주지는 내보내지 않는다**(정산 화면이 생길 때 따로 정함).
- 권한: `RIDER_OPS`·`OPERATOR_ADMIN`·`CS_AGENT`(상담원이 컴플레인 기록 때 라이더 확인). 개인정보 로그 금지.
- 경로가 라이더 본인 API(`/riders/me` 등)와 겹치지 않게 — 게이트웨이 허용 목록은 사용자 종류별이라 같은 경로에 RIDER·OPERATOR 가 섞이면 정리해서 적는다.

## BOD-4. 전달 실패·도움요청 대기 목록 (execution)

- 지금: 단건 조회만(`GET /deliveries/{id}`, `GET /deliveries/{id}/help-requests/{rid}`) → 지원센터가 무엇을 처리해야 하는지 볼 곳이 없다(case-service R-4 사건함은 아직 — 1차에서 뺌, PC 1 추천 2026-11-15. 사건함을 넣게 되면 이 목록은 그 사건이 정본).
- 제안
  - `GET /api/v1/delivery-help-requests`(직원 — **`/api/v1/help-requests/**` 는 게이트웨이에서 이미 case-service 로 간다**(계약만 있는 case 도움요청 사건), 그래서 다른 이름. 새 앞머리라 PC 1 이 pmart-config `api-gateway.yml` 라우트에 더한다 — PR 에 적어 달라) — 필터 `state`(OPEN·IN_PROGRESS·RESOLVED, 기본 OPEN), `category`(DELIVERY_FAILURE 포함), `mfcId`, `riderId`, `from`/`to`, 페이지(최대 50), 오래된 것 먼저(대기 큐). 행: requestId·deliveryId·orderId·mfcId·riderId·category·stage·state·createdAt·사진 수. `detail`(라이더 글)은 목록에서 빼고 단건에서만.
  - `GET /api/v1/delivery-failures?state=REPORTED`(또는 위 목록의 `category=DELIVERY_FAILURE` 로 대신 — 하나면 충분, PC 5 가 고름) — 운영자 확정·반려 대기(FAILURE_REPORTED) 배송.
  - 지금 case 가 없으니 **처리 상태를 execution 이 갖는다**: 운영자가 도움요청을 "처리함"으로 닫는 `POST /api/v1/delivery-help-requests/{deliveryId}:{requestId}/resolutions`(또는 `/api/v1/deliveries/{id}/help-requests/{rid}/resolutions` — 기존 단건 조회 경로 아래, 라우트 추가 없음)(메모 선택) 정도 — R-4 로 case 가 생기면 그 사건이 정본이 되고 이 API 는 정리(지금 state 칸 OPEN·IN_PROGRESS·RESOLVED 가 있음). **필요 없다고 보면 PR 에 이유를 적는다**.
  - 권한: 조회 `Access.operatorCanSee`, 닫기·실패 결정 `operatorCanDecide`(BOD-1 반영 뒤).

## 백오피스 화면 쪽

- PC 1 이 화면 요청서(`docs/handoff/clients/backoffice/`)에 이 API 들을 적는다. 경로·필드가 위 제안과 달라지면 PR 설명에 적어 주면 요청서를 맞춘다.
- 정할 것(사용자 결정 필요 시 PC 5 가 사용자에게): 라이더 이름 검색 방식, 도움요청 "처리함" 닫기 API 를 둘지.

## BOD-5. 주문 ID 로 배송 찾기 (2026-11-15 추가 — 클라이언트 BR-56)
- 지금: order 는 deliveryId 를 저장하지 않고, dispatch·execution·tracking 계약에 orderId 로 찾는 직원 조회가 없다 → 상담원·라이더 운영이 주문 조회 뒤 배송 번호를 직접 넣는다.
- 제안: dispatch `GET /api/v1/deliveries?orderId=`(직원 — 배송 요약 목록: deliveryId·유형·상태·배정 라이더·mfcId). dispatch `delivery` 표에 order_id 가 이미 있다. 권한은 BOD-1·2 와 같게(`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`·그 센터).

## BOD-6. 라이더 version 을 조회 응답에 (2026-11-15 추가 — 클라이언트 질문)
- `POST /riders/{riderId}/restrictions` 의 `X-Expected-Version` 은 라이더 행 version 과 비교하는데, 화면이 그 값을 얻을 곳이 없다(`GET …/restrictions` 의 이력 version 은 프로필·운행 변경으로 낡을 수 있다). `GET /riders/{riderId}/restrictions` 응답(또는 BOD-3 직원 상세)에 지금 라이더 `version` 을 싣는다.

