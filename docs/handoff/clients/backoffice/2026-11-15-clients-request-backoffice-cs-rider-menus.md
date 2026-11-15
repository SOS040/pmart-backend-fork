# [pmart-backend → pmart-clients] 요청: 백오피스 메뉴 묶음 + 고객센터·라이더 운영 메뉴 (2026-11-15)

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 대상 앱: `web/backoffice`(기본 백오피스)
- 앞 문서: `2026-11-09-clients-request-backoffice-role-menus.md`(역할별 메뉴), `../all/2026-11-09-clients-request-customer-inquiry-page.md`(문의 답변)
- 백엔드 정본: `pmart-backend/docs/architecture/backoffice-role-menus.md`(역할 → 메뉴 표)

## 0. 사용자 결정 (2026-11-15)

1. **MFC 는 기본 백오피스에서 떼어 따로 앱으로 만든다.** 11-09 요청서의 "MFC 메뉴도 같은 백오피스 안에"(3장)는 **이 문서로 바뀐다** — 기본 백오피스에는 MFC 메뉴를 넣지 않는다. MFC 백오피스 앱 요청은 따로 보낸다(앱 이름·주소·로그인 설정이 정해지면).
2. **고객센터·라이더 운영 메뉴는 기본 백오피스에 붙인다.**
3. **메뉴를 상위-하위로 묶는다**(사용자 "메뉴 하나하나 다 있으면 너무 길어질 것 같아서"). 왼쪽 메뉴를 접고 펼 수 있는 묶음으로.
4. 새 직원 역할 **`RIDER_OPS`(라이더 운영)**. 상담원 `CS_AGENT` 는 배차 상태 조회도 볼 수 있게 됨(백엔드 PC 5 작업 중 — 4장).

## 1. 메뉴 묶음 (제안 — 이름·순서는 클라이언트가 정해도 됨)

| 묶음 | 하위 메뉴 | 보이는 역할 |
|---|---|---|
| 상품 관리 | 상품(등록·일괄·상세), 카테고리, 콘텐츠, 가격, 센터 취급 | `MD` |
| 승인 | 승인함, 승인 목록 | `*_APPROVER`, `OPERATOR_ADMIN` |
| **고객센터** | 주문 조회, 문의 답변, 라이더 컴플레인, 클레임 조회, 배송 확인 | `CS_AGENT`, `OPERATOR_ADMIN` |
| **라이더 운영** | 라이더 찾기·계정 제한, 제한 검토, 전달 실패·도움요청 대기, 배송·배차 확인, 라이더 컴플레인 | `RIDER_OPS`, `OPERATOR_ADMIN` |
| 업무 도우미 | (지금 그대로) | 모든 직원 |

- 묶음 안 하위 메뉴가 하나도 안 보이면 묶음도 숨긴다. 여러 역할이면 합쳐서(합집합).
- 같은 화면(예: 라이더 컴플레인 목록, 배송 확인)이 두 묶음에 있어도 된다 — 같은 화면을 두 메뉴에서 연다.
- 서버가 권한을 최종 판정한다(403). 메뉴 숨김은 편의.

## 2. 고객센터 묶음 — API

모두 게이트웨이 `https://192.168.0.40:8443/api/v1`, 직원 토큰, 명령은 `Idempotency-Key`·`X-Source-System: backoffice-web`.

| 화면 | API | 상태 |
|---|---|---|
| **주문 조회**(목록) | `GET /orders/lookups` — 조건 `orderId`·`customerId`(완전 일치), `status`, `mfcId`, `receiverPhone`(숫자만 완전 일치 — 부분 검색 없음, 9~11자리 아니면 400), `createdFrom`(포함)·`createdTo`(미포함, 주문 확정 시각). **orderId·customerId 가 없으면 기간 두 값 필수·31일 이하**(아니면 400). `page`(0~)·`size`(1~50, 기본 20) → `items[]`, `page.hasNext`(전체 건수 없음). 목록의 연락처는 `receiverPhoneMasked`(예 `010-****-1234`), 이름·주소 없음 | **LAN 배포 2026-11-15**(order 0.3.0) |
| 주문 조회(상세) | `GET /orders/lookups/{orderId}` — 주문행·금액·결제 번호·**배송지 전체(수령인·연락처·주소·출입)**·이행 확인 필요 기록. 상태 이력·클레임은 없다(클레임은 아래 `GET /claims?orderId=`) | 같음 |
| 문의 답변 | `GET /inquiries`, `GET /inquiries/{id}`, `POST /inquiries/{id}/replies` | 있음(case 0.2.0~) — 클라이언트 화면 진행 중으로 보임 |
| **라이더 컴플레인** 기록 | `POST /rider-complaints` — `{deliveryId, complaintType: REPORT|MISDELIVERY|DAMAGE, orderId?, note?(≤1000, 내부 메모), inquiryId?(문의에서 왔으면)}`. 서버가 배송의 배정 라이더를 찾는다 — 없으면 422 `RIDER_NOT_ASSIGNED`, 배송이 없으면 422 `DELIVERY_NOT_FOUND`, 없는 문의면 422 `INQUIRY_NOT_FOUND`. 같은 종류 7일 안 3건이면 라이더가 "제한 검토 대상"에 오른다(자동 제재 없음) | **LAN 배포 2026-11-15**(case 0.3.0) — 기록: `CS_AGENT`·`OPERATOR_ADMIN` |
| 라이더 컴플레인 목록·상세 | `GET /rider-complaints?riderId=&deliveryId=&complaintType=&from=&to=&size=&cursor=`, `GET /rider-complaints/{complaintId}`(메모는 상세에만) | 같음 — 조회: + `RIDER_OPS` |
| 클레임 조회 | `GET /claims`, `GET /claims/{id}`, `GET /claims/{id}/refunds` | 있음(claim 0.1.0). 클레임 결정(`POST …/decisions`)은 아직 501 — 화면에 넣지 않는다 |
| 배송 확인 | `GET /deliveries/{deliveryId}`(배송 수행), `GET /deliveries/{deliveryId}/dispatch`(배차 — `CS_AGENT` 는 PC 5 반영 뒤 열림) | 있음 |
| 전달 실패 확정·반려 | `POST /deliveries/{deliveryId}/failure-decisions`(CONFIRM·REJECT) — `CS_AGENT`·`OPERATOR_ADMIN`·그 센터 `MFC_SUPERVISOR`(+ `RIDER_OPS` PC 5 반영 뒤) | 있음(execution 0.3.2) |

- **하지 않는 것**: 상품준비 뒤 주문 취소(ADR-0060 — 고객센터는 취소하지 않음). 화면에 취소 버튼을 두지 않는다.

## 3. 라이더 운영 묶음 — API

| 화면 | API | 상태 |
|---|---|---|
| 라이더 계정 제한 걸기·바꾸기·풀기, 이력 | `POST /riders/{riderId}/restrictions`, `GET /riders/{riderId}/restrictions` | 있음(rider 0.3.0) — 지금 `OPERATOR_ADMIN` 만, `RIDER_OPS` 는 PC 5 반영 뒤 |
| 제한 검토(컴플레인 누적) | `GET /riders/restriction-reviews`, `POST /riders/restriction-reviews/{reviewId}/decisions` | 있음 — 같음. 2026-11-15 부터 컴플레인 기록이 들어오면 실제로 대상이 생긴다 |
| 배차 확인·픽업 전 배차 취소 | `GET /deliveries/{id}/dispatch`, `POST /deliveries/{id}/assignment-cancellations`(+ `GET …/{requestId}`) — 픽업 뒤면 422 `PICKED_UP_CASE_REQUIRED` | 있음(dispatch 0.3.1) — 그 센터 직원·`OPERATOR_ADMIN`, `RIDER_OPS` 는 PC 5 반영 뒤 |
| 전달 실패 확정·반려 | 2장과 같음 | 있음 |
| **라이더 찾기**(목록·검색·상세) | 제안 `GET /riders`(전화번호 완전 일치·제한 상태·운행 상태·센터·수단), `GET /riders/{riderId}`(계좌·거주지 없음) | **PC 5 작업 예정**(요청 BOD-3) — 경로 확정 뒤 이 문서를 고친다 |
| **도움요청·전달 실패 대기 목록** | 제안 `GET /delivery-help-requests`(state·category·mfcId·riderId·기간, 오래된 것 먼저) + 처리함 닫기 | **PC 5 작업 예정**(요청 BOD-4) — 같음 |

## 4. 시험 계정

- LAN `test-cs-rider@pmart.com`(이름 "시험 상담·라이더운영", 역할 `CS_AGENT`·`RIDER_OPS`) — 인사 등록·권한 승인 흐름으로 만듦(2026-11-15). 비밀번호는 PC 1 에 요청(파일로 전달 — 채팅·로그에 붙이지 않는다).
- `OPERATOR_ADMIN` 메뉴 확인은 기존 시험 계정으로.

## 5. 회신해 줄 것

- 메뉴 묶음 이름·순서를 바꿨으면 알려 주세요(백엔드 정본 표를 맞춘다).
- 화면에 필요한데 API 에 없는 칸·조건이 있으면 목록으로.
- 11-09 요청서의 MFC 메뉴 작업을 이미 시작했다면 어디까지인지 — MFC 앱으로 옮길 때 참고.
