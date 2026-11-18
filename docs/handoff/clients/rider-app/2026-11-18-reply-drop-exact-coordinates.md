# [pmart-backend → pmart-clients] 회신: 전달지 정확 좌표 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: rider_app(PC 5), rider-backoffice 관제(drop-location)
- 대상: pmart-clients `docs/handoff/rider-app/2026-11-18-backend-drop-exact-coordinates.md`

## 진단 — 맞다
- 정확 좌표는 서버에 이미 있다: delivery-dispatch 가 배차할 때 도로명주소를 serviceability 지오코딩(카카오, ADR-0023)으로 바꿔 `drop_lat`·`drop_lng` 에 둔다.
- 그런데 `drop-info`(라이더)·`drop-location`(직원)은 주문 사본 좌표만 준다 — 고객이 핀 없이 저장한 주소는 null.

## 요청 1 — PC 5 요청서로 올림(급함)
- `docs/architecture/service-requests/delivery-execution-service.md` **DE-5**: 주문 사본에 좌표가 없으면 dispatch 지오코딩 좌표를 같은 lat·lng 칸으로, `coordinateSource`(ORDER_PIN / ADDRESS_GEOCODE) 추가. 앱 변경 없음(칸 그대로).

## 요청 2 — PC 1 이 만듦: customer-service **0.5.0**
- 배송지 추가·수정(회원 주소록·비회원 배송지)에서 `geo`(지도 핀)가 없으면 서버가 도로명주소를 지오코딩(배차와 같은 변환)해 좌표를 채운다. 응답 `Address.geoSource`: `PIN`(고객 핀)·`GEOCODE`(서버가 채움). 변환 실패면 예전처럼 좌표 없이 저장(저장은 안 막음).
- 효과: 새로 저장·수정한 주소부터 주문 사본에 좌표가 생긴다. **옛 주소·옛 주문은 요청 1(DE-5)로 고쳐진다.**
- 시험: `CustomerAddressFlowIT.addressWithoutPinGetsGeocodedCoordinates` — customer 단위 18·통합 25 통과. LAN 배포는 PC 1 일지에 적는다.
- 고객 앱: 바꿀 것 없음(요청 모양 그대로). `geoSource` 는 보여 줄 필요 없음 — 필요하면 "지도에서 위치 확인" 안내에 쓸 수 있다.
