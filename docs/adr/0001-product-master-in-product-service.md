# ADR-0001 상품 정본은 `product-service`(Commerce 그룹)가 소유한다

- 상태: 제안 (2026-10-23)
- 관련: CLAUDE.md §1 Commerce, Commerce 문서 §1.5·§6.1·§F.1, WMS v8.1 L36·L108·L129·SEP-05, BackOffice BO-03·BO-Q03, MFC IF-06/IF-07

## 배경
Commerce 문서와 WMS 문서 일부는 "상품 관리 주체는 WMS"라고 쓴다. 그러나 WMS v8.1은 동시에 "공통 SKU 발급 서비스의 배치는 미확정(SEP-05)"이며 "WMS는 승인 버전을 소비한다"고 쓰고, BackOffice BO-Q03도 발급 서비스를 미결정으로 둔다. 2026-10-23 CLAUDE.md는 `product-service`(승인 상품·SKU·바코드·단위·카테고리·취급 속성)를 Commerce 그룹에 두었다.

## 결정
- 기초상품·상품·SKU(E-/B- 8자리 발급)·구성 낱개 수·카테고리·바코드·물류 규격(BOX/PLT 환산, Ti/Hi)·전사 판매 상태의 정본은 `product-service`.
- 승인 워크플로(등록 초안·심사·승인)는 `approval-service`, 발급·버전 확정은 `product-service`가 `ApprovalDecided`를 받아 수행하고 `SkuIssued`/`ProductVersionPublished`로 전파.
- 센터별 취급 ON/OFF·현물은 `cdc-inventory`/`mfc-inventory`가 각자 보유(센터 자체 코드 발급 금지).
- Commerce 문서의 "WMS 상품 정본" 서술은 이 ADR로 대체한다.

## 대안
1. CDC WMS 내부에 상품 마스터 — 문서 자체가 미확정이라 했고, MFC·Commerce·SCM이 모두 소비하므로 물류 서비스에 두면 의존 방향이 역전된다.
2. 별도 `catalog-master-service`(플랫폼) — CLAUDE.md 목표 구성에 없음. 필요하면 `product-service`를 Commerce 밖으로 옮기는 것으로 대응.

## 결과
- `product-service`는 Commerce 그룹에 있지만 **전 도메인 공통 마스터**다. 고객 전시 로직(product-content/storefront)과 섞지 않는다.
- 결정 필요: BOX/PLT 환산 정본을 product-service에 둘지 cdc-inventory에 둘지(WMS F-006). 본 설계는 product-service로 둔다.
