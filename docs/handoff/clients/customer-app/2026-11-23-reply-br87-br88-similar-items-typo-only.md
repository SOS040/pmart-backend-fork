# 회신 — BR-87 0건일 때 비슷한 상품 · BR-88 오타일 때만 제안

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-search-zero-similar-and-typo-only.md`
작성: pmart-backend, 2026-11-23 — 사용자 "추천대로 해주고 비슷한 이름 먼저로"
상태: 코드·시험 끝(search check 통과, 새 IT `SearchFlowIT.zeroResultsShowSimilarItems`), **LAN 배포 2026-11-23**(search 이미지 dfcff6d2 — 아보카도·없는 말 → 인기 20, 바나나킥 → 바나나맛우유, 닭가슴살샐러드 → 닭가슴살 15)

## BR-87 — search 0.12.0 `SearchResult.similarItems`
- Q1 답: 이름 `similarItems` 그대로.
- 첫 쪽(cursor 없음)이 0건이고 고친 검색어(correctedQuery)로도 0건일 때만 `SearchHit[]` 최대 20개, 다음 쪽 없음. 결과가 있으면 null.
- 고르는 법(서버): ① 검색어 형태소가 하나라도 맞거나 1~2글자 조각이 70% 이상 맞는 상품명, 관련도 순(예 "닭가슴살샐러드" → 닭가슴살 상품, "바나나킥" → 바나나맛우유, "아보카도" → ②로) ② 그것도 없으면 인기 상품(최근 판매 → 별점).
- 센터(mfcId) 취급 거르기·판매중지 제외는 검색과 같고, 카드에는 할인가·availability 도 붙는다.

## BR-88 — 정상 이름에는 제안 없음
- `suggestedQuery`(결과가 적을 때 "혹시 ○○?")는 원래 검색어가 상품명에 형태소로 그대로 맞으면 주지 않는다. 결과가 0건일 때의 자동 교정(`correctedQuery`)은 그대로.
- 참고: 교정은 원래도 상품명 사전에 없는 낱말만 고친다. LAN 에서 "서울우유"는 7건·제안 없음(1호점·목포점·센터 없음 모두)이라 재현되지 않았다 — 앱이 본 제안이 목 응답이었는지 확인 부탁.
