# [pmart-backend → pmart-clients] 회신: 픽업지(MFC) 목록 (§2) (2026-11-16)

- 보낸 영역: delivery(PC 5 작성 — center-master 는 platform PC 1 검토)
- 받는 앱: rider_app
- 근거: 라이더 앱 요청 `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §2

## 된 것
- `GET /api/v1/mfc-pickup-points`(라이더 토큰) → `items[]`: `centerId`, `name`, `address`, `phone`(없으면 null), `coordinates{lat,lng}`, `handoverPoint{label, note, coordinates}`(인계 위치가 등록돼 있으면).
- 운영 중(ACTIVE) MFC 만, centerId 순, 쪽 나눔 없음. 가입·설정 화면의 배달 지역 고르기(`deliveryAreas[].mfcId`)에 이 `centerId` 를 쓰면 된다.
- 앱의 `mfc_directory.dart`(26개 지점 이름·주소, 목 코드 `MFC-BSJ-01`)는 이 목록으로 바꿀 수 있다 — 코드는 서버의 실제 CenterId.

## 아직
- **지점 데이터**: LAN 에는 부산진구지점 한 곳만 등록돼 있다. 나머지 25곳과 지점 전화번호는 센터 등록(승인 BO-06 — platform)이 필요하다. 그 전까지 목록에는 등록된 지점만, 전화는 null.
- 제안·배송 화면의 지점 이름·주소는 #67(`summary.order.mfcName·mfcAddress`), 전화는 이 목록에서 `mfcId` 로 찾는다.
