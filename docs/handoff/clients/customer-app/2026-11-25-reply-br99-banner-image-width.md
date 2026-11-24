# 회신 — BR-99 배너 그림 축소본

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-25-backend-banner-image-size.md`(R1~R3·Q1)
작성: pmart-backend, 2026-11-25 — 사용자 "클라이언트 br 99 확인해주고"
상태: **LAN 배포 끝**(2026-11-25, storefront f2881907, 계약 0.16.0) — 앱이 `w` 를 붙이면 된다

## 답
- **R1 — 쿼리 이름은 `w`**(요청서의 `width` 가 아니라 상품 사진 content-images `?w=` 와 같은 이름 — 앱의 `withImageWidth` 를 그대로 쓰면 된다). `GET /api/v1/storefront/banner-images/{imageId}?w=<화면 폭 × 화소 비율>`(1~4096).
  - content-images 와 달리 아무 값이나 받는다: **360·720·1080·1440 중 그 이상인 가장 작은 단계**로 올린다(1440 넘으면 1440). 예 411dp × 2.625 = 1079 → 1080.
- **R2 — 축소본은 JPEG**(품질 0.82). 원본 비율 그대로, 투명 배경은 흰색. WEBP 원본은 JDK 가 풀지 못해 **원본을 그대로** 준다(지금 LAN 배너는 PNG). 원본 폭이 그 단계 이하면 원본 크기 그대로 JPEG. 원본은 그대로 보관한다.
- **R3 — 이미 올라간 배너도** 첫 요청 때 만들어 버킷에 저장하고(`banner-variants/w<단계>/<sha256>.jpg`) 다음부터 그것을 준다. 첫 요청만 조금 늦다(2MB PNG 풀기). 올리기 규격(5MB)은 그대로.
- **Q1 — 캐시 헤더 같음**: `Cache-Control: public, max-age=31536000, immutable`, `Content-Type: image/jpeg`, ETag `"<sha256>-w<단계>"`(원본은 `"<sha256>"`).
- 배너 목록·홈 응답의 `imageUrl` 은 그대로(`w` 없음) — 앱이 붙인다.

## LAN 확인(2026-11-25, 게이트웨이 :8080)
| 배너 | 원본 PNG | `?w=1080` JPEG | `?w=720` JPEG |
|---|---|---|---|
| `BIMG-40f4e8c3-…` | 2,313,526B | 97,676B(첫 요청 0.46초, 다음 0.03초) | 51,000B |
| `BIMG-850983d5-…` | 2,229,461B | 112,273B | 59,012B |
| `BIMG-64d9ddfe-…` | 2,103,108B | 109,103B | 57,339B |

약 20~45분의 1. Content-Type `image/jpeg`, ETag `…-w1080`·`…-w720`.

## 앱
- 배너 그림 주소에 `w` 를 붙인다(LAN 에 이미 들어가 있음).
