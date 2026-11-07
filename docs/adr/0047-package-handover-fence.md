# ADR-0047 라이더 포장 인계(슬라이스 6c) — fence 는 mfc-fulfillment, 배정은 배송 사건 투영, 유효시간은 센터 설정(기본 없음)

- 상태: 승인 (2026-11-08, 사용자: "관련 비즈니스로직 전부 확인하고 MFC부터 만든 후 다른 서비스도 참조할 수 있는 md파일 작성", fence 유효시간 "설정값, 기본 없음")
- 관련: ADR-0002(재고 단일 쓰기·인계 전기 한 사건), ADR-0006(fence — 제안), ADR-0043(6b), ADR-0046(집품 뒤 취소). 계약 정본 `docs/business/flows/package-handover-contract.md`

## 배경
6b 까지 출고는 READY 에서 멈췄다. 인계(WK-08·IV-08)는 MFC 문서에서 "공통 주문 판정"(Q-01 미결)으로 남아 있고, 배차·라이더·배송 실행 서비스는 껍데기(501)다. ADR-0006 은 fence 를 mfc-fulfillment 에 두는 안을 냈다(제안).

## 결정
1. **fence 는 mfc-fulfillment**: 포장마다 인계 허가(handoverAuthorizationId·fenceVersion·만료) → 인계 확정. 중지 판정과 허가·확정은 주문 advisory lock 으로 한 줄(먼저 확정한 쪽 기준).
2. **배정은 배송 사건 투영**(`delivery.delivery.v1`: DeliveryCreated·DeliveryAssigned·DeliveryAssignmentChanged·DeliveryCancelledBeforePickup), 배송 ID 한 행·더 새 버전만. 배정이 바뀌면 확정 요청 전 fence 는 끝낸다.
3. **유효시간은 센터 설정 `handover-fence-ttl`, 기본 없음** — 없으면 허가 422 CONFIG_REQUIRED(미설정을 0·무제한으로 대체하지 않음). LAN 은 시험값 PT2M.
4. **만료**: 확정 요청 전 fence 는 시각이 지나면 끝(다시 찍기·중지 진행). 확정 요청(인계 사실)이 생기면 결과를 알 때까지 살아 있다 — 결과 모름은 같은 handoverId 로 sweep 재시도, 재고 거절은 REJECTED 로 멈춤(재인계·취소 모두 막음, 대사).
5. **재고 인계 전기**(mfc-inventory 0.8.0): 포장 위치 NORMAL·PACKED −q, 줄·LOT 확정 조각 R−q(handed_qty, CONSUMED) 한 확정 단위, 같은 packageId 한 번. PackageHandedOverPosted.
6. **취소와의 경합**: 인계된 포장이 있으면 전체 중지는 거절(RESOLVED + HANDED_OVER, 작업 유지 — 부분 중지·회수는 아직 없음). 살아 있는 fence 가 있으면 중지는 PENDING(HANDOVER_IN_PROGRESS)으로 대기.
7. **DISPATCH 작업·가게 도착 전제·제한 라이더 검사는 두지 않음** — 정의 또는 생산자가 없다.

## 대안
- 공통 판정을 별도 서비스로: Q-01 미결, 2 hop 규칙과 지연. MFC 안에 둔다(ADR-0006 안).
- 만료 = 무조건 끝: 확정 요청 뒤 결과 모름에서 실물 이중 인계 위험(MFC 1553행). 확정 요청 여부로 나눴다.
- 배정 확인을 배차 서비스 동기 조회로: 배차 서비스가 없고, 라이더 스캔마다 2 hop 이 된다. 투영으로.

## 결과
- 계약: fulfillment 0.4.0(허가·확정 응답 코드, 202, 판정 READY), inventory 0.8.0(인계 전기 요청 필드). 표: fulfillment V6(`delivery_assignment`, package fence 열, `package_handover`), inventory V9(`reservation_part.handed_qty`, `package_handover_posting`).
- 설정: `mfc-fulfillment-service.yml` 바인딩 `deliveryEvents-in-0`, `-lan.yml` `handover-fence-ttl`.
- 남은 것: 배차·라이더·배송 실행·주문의 인계 소비(계약 문서 §5), 부분 인계 뒤 부분 취소·회수(S6), 재포장, 운영자 대사 화면, 열린 질문(계약 문서 §7).
