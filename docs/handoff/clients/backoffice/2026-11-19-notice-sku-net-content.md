# 알림 — 상품 SKU 내용량 `netContent` 입력 칸(product-service 0.4.0, 고객 앱 BR-19 "100g당 가격")

보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 까닭: 고객 앱 카드·상세의 "100g당 999원"(BR-19) — 서버가 내용량으로 계산한다. 내용량은 상품 승인 값이라 **백오피스 상품 등록·수정 화면에서 넣어야** 한다.
- 상태: 백엔드 main 반영, LAN 배포는 사용자 승인 뒤(배포되면 이 파일 아래에 적는다)

## 바뀐 것 — 상품 초안 `skus[0].netContent`(선택)
```json
"netContent": { "amount": 1000, "unit": "G" }   // 1kg → 1000 G, 1.5L → 1500 ML
```
- **판매 단위 전체에 든 양**(g·mL 정수, 1~1,000,000). 500g×2입 묶음(B)은 1000 G.
- 포장 무게 `weightG`(물류·포장 한도용)와 **다른 칸**이다. 용량 `volumeMl` 과도 따로.
- 개수로만 파는 상품(2입 등)은 비운다 → 고객 화면에 단위 가격이 안 나온다.
- 양·단위 중 하나만 넣거나 범위·단위(G·ML 말고)가 틀리면 초안 검증 `NET_CONTENT_INVALID`(field `skus[0].netContent…`).
- 상품 정보 수정(`POST /products/{id}/edit-drafts`) 초안에 현재 값이 옮겨 담긴다. **재승인은 초안 값으로 바꾸므로 지우면 내용량이 없어진다**(치수·소비기한 보장과 같다).
- 조회: `GET /skus/{sku}`·상품 버전 조회에 `netContent`.

## 화면 제안
- 상품 등록·수정 화면 SKU 영역에 "내용량" 숫자 + 단위 선택(g / mL). kg·L 로 입력받으면 ×1000 해서 보낸다.
- 표시 문구(옵션 `capacityLabel` "500g")와 같은 값이어야 하지만 서버가 문구에서 읽어 채우지 않는다.

## 회신
막힌 것은 `docs/handoff/clients/backoffice/` 회신 파일 또는 PR 본문으로.
