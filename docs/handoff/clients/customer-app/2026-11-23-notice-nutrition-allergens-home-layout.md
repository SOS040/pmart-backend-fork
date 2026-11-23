# 알림 — 상품 영양·알레르기(상세·카드·검색) · 홈 편성 8(타이머 두 모양·중간 배너·보라 로딩)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend, 2026-11-23 — 사용자 "영양 넣어줘"·"알레르기는 필터 빼고 표시·검색만"·"홈 편성도 넣어줘"
상태: **LAN 반영 끝**(product-content 0.6.0·search 0.11.0 이미지 1d984946, products-v9, 콘텐츠 승인 3건·편성 승인 1건 — 사용자 승인)

## 1. 영양·알레르기 — product-content 0.6.0 · search 0.11.0
- 상세(`GET /product-contents/{id}`)·카드(`GET /product-contents?productIds=`)·검색 결과(`SearchHit`)에 `nutrition`·`allergens`.
  - `nutrition`: 100g(`basisUnit` G) 또는 100mL(ML) 기준 `energyKcal`·`sodiumMg`·`sugarsG`·`proteinG`·`fatG` — 표시사항에서 읽은 값만, 모르는 칸 null.
  - `allergens`: `contains`(함유)·`mayContain`(같은 제조시설에서 제조)·`text`(원문). 이름은 법정 표시 이름(알류·우유·땅콩·대두·밀·…).
  - **null·빈 목록은 "모름"이다 — "없음"으로 표시하지 말 것**(예: "알레르기 정보 없음 — 표시사항을 확인해 주세요").
- LAN 504개 중 영양 239·알레르기 300.
- 검색 필터: `GET /search/filters` 에 key `nutrition`(라벨 "영양") 값 저당·저나트륨·고단백 — `filters=nutrition.저당` 처럼, 같은 키 여러 값은 **모두 만족**(AND). LAN 건수 저당 53·저나트륨 52·고단백 34.
  기준: 저당 당류 100g 5g·100mL 2.5g 미만, 저나트륨 120mg 미만, 고단백 100g 11g·100mL 5.5g 이상.
- 알레르기는 필터 없음. "땅콩 함유"·"계란 알레르기"처럼 묻는 검색어면 그 성분을 **함유**한 상품을 같이 찾는다(같은 시설 제조는 빼고). "땅콩"만 치면 지금과 같다.

## 2. 홈 편성 8
- 타이머 테마 두 모양(BR-84 `timerStyle`): "오늘 밤 떡볶이 야식" LABEL(오늘 자정 끝), "이번 주 아이스크림 모음" BAND(일요일 자정 끝).
- 중간 배너 묶음 `G-MID-1`(kind BANNER, HOME_INLINE) — 랭킹과 아이스크림 테마 사이.
- 로딩(SPLASH) `B-SPLASH` 이미지가 보라 계열·**941×2039(9:19.5)** 로 바뀜 — 긴 화면에서 contain 으로 그려도 위아래 여백이 거의 없다. 같은 bannerId·이미지 주소가 바뀌어 다음 실행에 새로 받고 그다음 실행에 보인다.

## 앱이 할 것
- 생성 모델 갱신(product-content 0.6.0·search 0.11.0) → 상세에 영양(100g/100mL 기준 표기)·알레르기(함유 / 같은 시설) 칸, 검색·카테고리 필터에 "영양" 줄(서버가 주는 그대로라 화면 변경이 없을 수도 있음).
- 실기기: 홈 타이머 두 모양·중간 배너·보라 로딩(두 번 실행 뒤).
