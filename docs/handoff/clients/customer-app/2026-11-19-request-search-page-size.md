# 요청 — 검색·카테고리 목록 첫 쪽 크기 20 (검색 다음 계획 11번)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 계기: 사용자 2026-11-19 "카테고리 들어갈 때도 느려" → 서버 쪽(카테고리 사본·색인 확인·깊은 커서)은 고쳐 배포함. 남은 큰 몫은 앱이 받는 양. 계획 `docs/operations/2026-11-19-search-next-plan.md` 11번(사용자 "11번부터").

## 확인한 것
- 목록 카드 이미지 `?w=` 는 이미 반영됨(`product_tile_card.dart` `withImageWidth`, 280f7f7c) — 고마워요.
- `GET /search/products` 를 `size` 없이 부른다(`catalog_repository.dart` searchProducts) → 서버 기본 **50개**. 첫 화면에 보이는 것은 그보다 훨씬 적은데 50개의 카드·대표 이미지를 한 번에 받는다.

## 요청
- 검색·카테고리 목록의 `GET /search/products` 에 **`size=20`**(다음 쪽은 지금처럼 `nextCursor`, 스크롤 끝에서). 계약 그대로(size 1~100) — 서버 변경 없음.
- 화면이 2열이고 한 번에 보이는 카드가 6~8개라 20개면 두세 화면 분량. 스크롤이 빠르면 다음 쪽을 조금 일찍(남은 카드 6개쯤에서) 불러도 된다.
- 측정(가능하면, profile/release): 카테고리 탭 → 첫 카드가 보일 때까지, 받는 바이트(전후).
