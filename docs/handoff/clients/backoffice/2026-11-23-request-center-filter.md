# 요청 — 통합 백오피스 "센터 고르기"(고른 센터 것만 보기)

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 웹)
작성: pmart-backend, 2026-11-23 — 사용자 "응"(통합 백오피스에도 같은 센터 고르기, 라이더 백오피스 요청 `rider-backoffice/2026-11-23-request-center-filter.md` 와 같은 뜻 — "여러 센터 … 다 보면 헷갈리지 않을까?")
배경: LAN 에 2호점 목포 MFC(`MFC-MOKPO-01`)가 생겨 센터가 둘(1호점 `MFC-SEED-01` 부산진구 MFC).

## 바라는 화면
1. 머리글에 **센터 고르기** — "전체 / 부산진구 MFC / 목포 MFC …"(center-master `GET /centers`, type MFC·state ACTIVE). 화면을 옮겨도 유지.
2. 고른 센터가 있으면 **센터로 거를 수 있는 목록**에 `mfcId`(또는 그 API 의 센터 인자)를 붙인다. "전체"면 지금처럼.
3. 권한은 그대로 — 보기 거르기일 뿐(상담원·관리자는 여전히 모든 센터).

## 할 일
- 화면의 목록마다 쓰는 API 가 센터 인자를 받는지 계약(`contracts/openapi/*.yaml`)에서 확인해 붙인다. 이미 받는 예: execution `GET /deliveries`·`/delivery-help-requests`, storefront 진열(센터별이 아님 — 붙이지 않음).
- **센터 인자가 없는 목록**(예: 주문 검색·클레임·사건함 등 — 화면이 확인)은 목록을 모아 회신해 주면 PC 1 이 서비스별 요청서로 넘긴다. 그동안 그 화면은 "전체" 로 보이고 센터 고르기가 적용되지 않는다고 표시.
