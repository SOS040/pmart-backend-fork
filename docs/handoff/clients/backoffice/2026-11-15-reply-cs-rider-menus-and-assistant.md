# [pmart-backend → pmart-clients] 회신: 메뉴 묶음·고객센터·라이더 운영 반영 + 업무 도우미·문의 답변 질문 (2026-11-15)

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 원 문서: pmart-clients `docs/handoff/backoffice/2026-11-15-backend-cs-rider-menus-done.md`, `…/2026-11-15-backend-assistant-connected.md`
- 근거: 2026-11-15 PC 1 코드 확인(파일·줄은 PC 1 일지)

## 0. 빌드 올림
- `index-Dyq2y9wh.js`·`index-BA51wX7k.css` 를 `https://192.168.0.40` 에 올렸다(`lan.sh publish-backoffice`, 2026-11-15). 인사팀(8444)·MFC 백오피스 안내 페이지(8445)는 그대로.
- 브라우저 확인은 아직 — `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS) 비밀번호는 PC 1 파일로.

## 1. cs-rider-menus-done §2·§3 질문

| # | 질문 | 답 |
|---|---|---|
| §2 | `OPERATOR_ADMIN` 이 상품·콘텐츠·가격·센터 취급 API 를 부를 수 있나 | **부를 수 있다** — product·product-content·pricing 은 직원(OPERATOR)이면 역할을 보지 않는다. 예외: 상품 **판매 상태 변경만 `MD`**(설정 `sale-state-change-role`). 센터 취급(`sku-handlings`)은 역할 대신 **토큰 `centers` 에 그 센터가 있어야** 한다(OPERATOR_ADMIN 이어도 센터가 없으면 403). → 상품 관리 묶음을 `OPERATOR_ADMIN` 에게 열지는 **사용자 결정 대기**(업무상 관리자가 상품 업무를 하는지). 정해지면 알림 |
| BR-56 | 주문·문의에서 배송 번호 찾기 | **지금 없다** — order 는 deliveryId 를 저장하지 않고(배송 사건을 받지 않음), dispatch·execution 에도 orderId 로 찾는 직원 API 가 없다. 배송은 delivery 영역이라 **PC 5 요청에 더했다**(`service-requests/delivery-backoffice-staff-apis.md` BOD-5: `GET /deliveries?orderId=` 직원 조회). 생기면 알림. 그 전까지 지금처럼 배송 번호 직접 입력 |
| §3-4 | 개인정보 원문 열람 기록 | API 없음 — 열람 기록을 남길지(audit-service) **사용자 결정 대기**. 지금 "보기로 열기(기록 없음)" 그대로 |
| §3-5 | 클레임 조회 역할 | 역할 검사 없음 — **직원이면 모두** 조회(`RIDER_OPS` 도). 결정(`/decisions`)은 아직 501 |
| §3-5 | `GET /deliveries/{id}`(execution) 역할 | `OPERATOR_ADMIN`·`CS_AGENT`·그 배송 센터(`centers`) 직원. `RIDER_OPS` 는 PC 5 반영(BOD-1) 뒤. 권한 없으면 **404**(403 아님 — 배송이 있는지도 알리지 않음) |
| §3-6 | 계정 제한 `X-Expected-Version` | 비교 대상은 **라이더 행 version**(제한 이력 version 아님). 프로필·운행 상태 변경도 이 version 을 올려서 **최근 이력의 version 은 낡았을 수 있다** → 409 `VERSION_CONFLICT`. 헤더는 선택 — **지금은 보내지 않는 것을 권한다**. 라이더 version 을 조회 응답에 싣는 것을 PC 5 요청에 더했다(BOD-6). 생기면 그 값을 보낸다 |
| §3-7 | 제한 검토 `RESTRICTED` 를 먼저 막나 | 막는다 — 라이더가 지금 제한 상태가 아니면 409 **`INVALID_STATE`**("먼저 계정 제한을 거세요"). 이미 닫힌 검토도 409 `INVALID_STATE`. 목 동작과 같다 |

## 2. assistant-connected §1-1 — 문의 답변 재전송
- **같은 키·같은 본문 → 답변이 두 번 붙지 않는다**(`RequestIdempotency`, 키 = 출처·Idempotency-Key·상담원). 응답은 지금 문의 상태를 다시 읽은 값.
- 같은 키·**다른 본문**(또는 다른 문의) → 409 `IDEMPOTENCY_CONFLICT`. `X-Expected-Version` 은 비교 대상에 들어가지 않는다.
