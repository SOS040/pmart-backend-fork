# 회신 — 목록 카드 품절 표시(availability) · 고객용 배너 기간(endsAt)

보낸 영역: platform
받는 앱: customer-app
원 문서:
- 백엔드 요청 `docs/handoff/clients/customer-app/2026-11-20-request-availability-display.md` §2(앱 반영 알림 `pmart-clients docs/handoff/customer-app/2026-11-20-backend-availability-display-applied.md` "§2 는 계약 대기")
- 앱 회신 `pmart-clients docs/handoff/customer-app/2026-11-21-backend-splash-popup-display-reply.md` R1 "막아야 하면 `Banner.endsAt` 를 응답에"(선택)
작성: pmart-backend, 2026-11-23 — 사용자 "모든 옵션 품절일 때만"·"조회 때 바로 묻기", endsAt "응 넣어주고"
상태: **코드·시험 끝, LAN 배포 전**(배포하면 여기에 날짜를 적는다)

## 1. 목록 카드 `availability` — search 0.10.0 · storefront 0.13.0
- 칸: `SearchHit.availability`(`GET /search/products`), `DisplayItem.availability`(홈·탭·MD 탭·`/storefront/sections/{id}/items`).
- 값: `AVAILABLE`·`SOLD_OUT`·`UNKNOWN`, **mfcId 를 줬을 때만**(없으면 null).
- 판정: 상품 상세와 같은 센터 가용 사본(product-content 카드 → mfc-inventory)을 옵션마다 보고,
  - 그 센터가 취급하는 옵션 중 하나라도 팔 수 있으면 `AVAILABLE`
  - 취급하는 옵션이 **모두** 품절이면 `SOLD_OUT`
  - 그 밖(취급 옵션 없음·모름·미게시·조회 실패·1초 넘음)은 `UNKNOWN` — 품절이라고 하지 않는다.
- 목록은 거르지도 줄 세우지도 않는다(품절 상품도 그대로 나온다). 구매·예약 보장이 아니다.
- 앱(요청서 §2 그대로): `SOLD_OUT` → 카드 "품절" 표시·담기 숨김, `UNKNOWN`·null → 표시 없음, `saleState == TEMPORARILY_SOLD_OUT` 은 지금처럼.
- 참고: 지금 LAN 상품은 모두 옵션 하나라 "대표 옵션 품절"과 결과가 같다.

## 2. 고객용 배너 `startsAt`·`endsAt` — storefront 0.13.0
- `Banner`(홈 `banners`·BANNER 영역 `banners`·`GET /storefront/banners?placement=`)에 편성의 시작·끝 시각(UTC, 없으면 null = 끝 없음).
- 응답에는 지금 기간 안 배너만 오는 것은 그대로다. 저장해 둔 SPLASH 는 `endsAt` 가 지났으면 그리지 않으면 된다(회신 R1 의 "한 실행" 문제).

## 앱이 할 것
- 생성 모델 갱신(search 0.10.0·storefront 0.13.0) → 카드 품절 표시, SPLASH 저장분에 endsAt 저장·비교.
- LAN 배포 알림을 받은 뒤 실기기 확인.
