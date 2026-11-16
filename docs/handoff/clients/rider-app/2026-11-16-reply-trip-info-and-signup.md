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
| 10 | 배송별 내 도움요청 목록 `GET /api/v1/deliveries/{id}/help-requests` → `items[]`(단건과 같은 모양, 본인 것만) | execution 0.7.0 |
| 11-3 | 도움요청 대화(앞서 회신 `2026-11-16-reply-help-request-messages.md`) | execution 0.6.0 |

## 아직(결정·다른 영역)
- §7 가입 뒤 수단을 오토바이·자동차·킥보드로 바꿀 때 면허 필요 여부 — 사용자 결정 대기(그 전까지 PATCH 는 면허를 묻지 않는다).
- §1 주문 요약(주문번호 8자리·지점 이름·품목·금액·요청사항), §2 지점 목록(center-master), §5 포장 라벨 숫자(mfc), §8 가입 승인, §9 계좌 실명 확인 — 사용자 결정 또는 다른 영역(platform·mfc) 몫이라 정리해 묻는 중.
