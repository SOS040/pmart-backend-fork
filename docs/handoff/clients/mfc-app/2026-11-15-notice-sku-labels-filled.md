# 알림: LAN 기존 상품의 상품명·바코드 채움 + BR-52 R2·R19-1 LAN 배포

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 이어지는 것: `2026-11-14-reply-br52-r2-r19-1.md` §4 "기존 상품(재발행 전)", §0 2)

## 1. LAN 배포
- 2026-11-15 LAN 반영: mfc-inbound-service 0.9.0(V9), mfc-inventory-service 0.16.0, mfc-capacity-service 0.14.0(PR #16, main d380e68a).

## 2. 기존 상품 상품명·바코드
- 상품 서비스에서 새 버전을 발행하지 않았다. 대신 mfc-inventory-service 만 상품 사건(`commerce.product.v1`)을 처음부터 다시 읽게 해 SKU 사본을 채웠다(다른 서비스 영향 없음).
- 결과(LAN mfc-inventory SKU 사본 503개):
  - `productName`: **503개 모두 채워짐.**
  - `barcodes`: **17개만** 있음. 정본인 product-service 에도 바코드가 있는 SKU 가 17개뿐이다(seed 상품은 바코드 없이 들어갔고, 백오피스 승인으로 등록한 상품만 바코드가 발급됨). 나머지 486개는 계속 빈 배열이다.
- 그러니 화면은 지금처럼 `barcodes` 가 비면 상품 확인 스캔을 건너뛰는 처리를 유지해 주세요. 바코드 스캔 시험은 바코드가 있는 17개 SKU 로 하면 된다.
