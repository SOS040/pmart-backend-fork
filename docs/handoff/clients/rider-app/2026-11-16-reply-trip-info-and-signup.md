# [pmart-backend → pmart-clients] 회신: 라이더앱 주문정보·지도·수단·면허 요청 (2026-11-16)

- 보낸 영역: delivery
- 받는 앱: rider_app
- 근거: pmart-clients `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md`

## 된 것
| § | 내용 | 계약 |
|---|---|---|
| 3 | 제안 요약 `summary.dropEtaSeconds`(픽업지 → 전달지 예상 소요, 도로), `summary.pickupPoint`(픽업지 좌표 — MFC 는 정확, 오배송 회수·다시 배송은 대략), `summary.dropAreaPoint`(전달지 **대략** 좌표 — 소수 둘째 자리 ≈ 1km 격자). 정확한 주소·핀은 지금처럼 픽업 뒤 drop-info. 수락 뒤 예정 시각은 수락 시각 + pickupEtaSeconds / + dropEtaSeconds 로 계산해 주세요(서버가 따로 내려 주는 값은 아직 없음) | dispatch 0.4.5 |
| 6 | `VehicleType` 에 `E_BIKE`(전기자전거)·`KICKBOARD`(전동킥보드). 수행료는 자전거와 같다(원문 §7.1 "위와 동일" — 서버 표가 도보만 따로) | rider 0.x, tracking·serviceability enum |
| 7 | 가입 `licenseNumber`(12자리, 하이픈 선택). 오토바이·자동차·킥보드는 필수(422 `LICENSE_REQUIRED`), 같은 번호 409 `DUPLICATE_LICENSE`. 가입 전 중복확인 `POST /api/v1/riders/license-checks {licenseNumber}` → `{available}`. 프로필 `licenseNumberMasked`(11-**-******-12). 서버는 원문을 저장하지 않는다 | rider-service |
| 7(수단 변경) | 사용자 결정 2026-11-16 "받는다": `PATCH /riders/me` 로 오토바이·자동차·킥보드로 바꾸면서 등록된 면허가 없으면 `licenseNumber` 필수(422 `LICENSE_REQUIRED`, 다른 라이더와 같으면 409 `DUPLICATE_LICENSE`) | rider-service |
| 10 | 배송별 내 도움요청 목록 `GET /api/v1/deliveries/{id}/help-requests` → `items[]`(단건과 같은 모양, 본인 것만) | execution 0.7.0 |
| 11-3 | 도움요청 대화(앞서 회신 `2026-11-16-reply-help-request-messages.md`) | execution 0.6.0 |

## 아직(결정·다른 영역)
- §8 가입 승인: 사용자 결정 2026-11-16 "즉시 승인 유지"(원문 §1.2) — 바꾸지 않는다.
- §9 계좌 실명 확인: 사용자 결정 2026-11-16 "나중에" — 지금은 앱의 이름 일치 확인만.
- §1 주문 요약: 사용자 결정 2026-11-16 "앱 요청대로 전부 수락 전부터" — 이어서 만든다(order 내부 요약 → 제안·배송 조회). 주문번호 8자리는 all 요청(order, platform)과 함께.
- §2 지점 목록(center-master, platform)·§5 포장 라벨 숫자(mfc) — 다른 영역 몫.
