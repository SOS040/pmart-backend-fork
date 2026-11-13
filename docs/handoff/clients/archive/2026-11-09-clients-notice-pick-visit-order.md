# [공지] 피킹 방문 순서 — PDA 는 받은 순서대로 보여 주세요 (2026-11-09)

- 결정: ADR-0050·사용자 2026-11-09 — 피킹 칸 순서 = **냉장 → 냉동 → 상온 C~H 그룹 순**(MFC 문서 1장 8-3 원본 순서), 그룹 안은 걷는 거리 최단(mfc-capacity OR-Tools), 출발은 장바구니 보관 자리.
- 계약: mfc-fulfillment **0.4.10** `GET /tasks/{id}`(PICK, 시작 뒤)
  - `lines[]`·`lines[].picks[]` 가 **방문 순서대로** 온다. 화면은 다시 정렬하지 말고 받은 순서로.
  - `picks[].visitSeq`(1부터): 그 조각을 집는 칸(배정 칸 우선)의 순서. 같은 칸이면 같은 번호.
  - `routeMethod`: ROUTING(최적 경로) / NEAREST_FALLBACK(가까운 칸부터) / CODE_ORDER(순서를 못 받아 칸 코드 순 — 피킹은 그대로 가능). 표시가 필요하면 "순서 추천 없음" 정도로.
- 순서는 **피킹 시작 때** 정해진다. 시작 전 상세에는 visitSeq 가 칸 코드 순(CODE_ORDER)이다.
- mfc-capacity 다음 작업 받기 응답 `detail.lines` 는 시작 전에 만들어지므로 순서가 반영되지 않는다 — 시작 뒤에는 작업 상세를 다시 읽어 주세요.
