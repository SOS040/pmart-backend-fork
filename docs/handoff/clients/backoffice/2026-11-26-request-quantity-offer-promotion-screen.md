# 요청 — 행사 만들기에 1+1·2+1(수량 행사) 종류 (ADR-0090, promotion 0.5.0)

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 행사 화면 — PC 2)
작성: pmart-backend PC 1, 2026-11-26 — 사용자 "1+1 띄우게"·"추천대로"
상태: 백엔드 **LAN 배포**(promotion 0.5.0)

## 바뀐 것 (promotion `contracts/openapi/promotion-service.yaml` 0.5.0)
- 행사 종류 `kind` 에 **`QUANTITY_OFFER`**(수량 행사 1+1·2+1) 추가.
- 입력 `offer{buyQuantity 1~10, freeQuantity 1~buyQuantity}` — **`discount` 는 두지 않는다**(이 종류만, 응답도 null). 다른 종류는 지금처럼 discount 필수.
- 대상(`targets` SKU·CATEGORY·THEME)·시작/끝·제출·승인(PROMOTION — MARKETING·OPERATOR_ADMIN, 본인 승인 불가)·조기 종료는 **기간 할인과 똑같다**.
- 검사 problems: `offer`(없음)·`offer.buyQuantity`·`offer.freeQuantity`·`discount`(값을 같이 줌)·`targets`.

## 화면 요청
- B-1 행사 만들기·고치기에서 종류 "1+1·2+1" 선택 → 할인 입력 대신 "N개 사면 M개 더" 두 칸(+ 대상 고르기는 기간 할인 화면 재사용).
- B-2 목록·상세에서 종류 표시 "수량 행사 1+1", 할인 칸 대신 `offer`.
- B-3 승인함 요약은 서버가 "수량 행사 <이름> — N+M" 으로 준다(화면 변경 없음).

## 알아 둘 것
- 같은 상품에 기간 할인과 1+1 이 같이 걸리면 고객은 **1+1 만** 받는다(기간 할인 없음, ADR-0090 A5). 기간 할인 화면에 이 점을 안내하면 좋다.
- 공용 표에 줄을 둘 필요가 있으면 PC 1 이 `service-requests/README.md` 에 더한다.
