# 요청 — 진열 편성에 상품페이지 "이렇게 관리해요" 카드 편집(BR-102)

보낸 영역: platform
받는 앱: backoffice (통합 백오피스 웹 — 진열 편성 화면)
작성: pmart-backend, 2026-11-25 — 고객 앱 BR-102("이미지가 없어 — 백오피스에서 관리하게 만들어줘")의 백오피스 몫
계약: **storefront-service 0.20.0**
상태: 백엔드 LAN 배포 끝(2026-11-25 fa190338). 남은 것 = 이 화면

## 바뀐 것
- 편성 초안 본문에 `careCards {title, items: [{cardId, imageId, title, body, sortOrder}]}`(모든 상품 공통 한 묶음). 배너·테마와 같은 초안 → 제출 → DISPLAY 승인 → 게시. 비우면(null) 구획 없음.
- 그림은 **배너 그림 올리기**(`POST /storefront/banner-images`)를 그대로 쓴다 → 받은 imageId 를 카드에. 규격 `GET /storefront/banner-image-specs` 의 `CARE_CARD`(16:9, 권장 1080×608·최소 720×405) — 경고만(초안 `warnings[]` field `careCards.items[i].imageId`).
- 제출 검사(422 LAYOUT_INVALID details): `careCards.title` INVALID_TITLE(1~30자)·PROMOTION_NOT_ALLOWED, `careCards.items` INVALID_CARD_COUNT(1~10장), `items[i].cardId` INVALID_CARD_ID(`C-` 로 시작)·DUPLICATE_ID, `.imageId` IMAGE_NOT_FOUND, `.title` INVALID_TITLE(1~30), `.body` INVALID_BODY(1~80, 줄바꿈 그대로).
- 같은 판: 상품페이지 배너(PRODUCT_DETAIL) 규격이 4.5:1(1960×436)로 바뀜 — 서버 값이라 화면 수정 없음.

## 화면
- 편성 화면에 "상품페이지 관리 카드" 구획: 제목 한 칸 + 카드(그림 올리기·제목·두 줄 설명·순서) 추가·삭제·순서 바꾸기. 미리보기는 고객 앱 칸 268.6×152dp 그림 + 제목 + 설명 두 줄.
