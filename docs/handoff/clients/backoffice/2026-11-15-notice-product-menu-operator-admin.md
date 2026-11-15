# [pmart-backend → pmart-clients] 알림: 상품 관리 묶음을 `OPERATOR_ADMIN` 에게도 연다 (2026-11-15)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/backoffice/2026-11-15-backend-cs-rider-menus-done.md` §2 질문, 백엔드 회신 `2026-11-15-reply-cs-rider-menus-and-assistant.md` §1
- 결정: 사용자 2026-11-15 — 상품 관리 메뉴를 운영 관리자에게도 보이게 한다("응").

## 할 것
- 상품 관리 묶음(상품 조회·등록·일괄 등록, 카테고리, 콘텐츠·일괄, 가격·일괄, 센터 취급)이 보이는 역할: `MD`, **`OPERATOR_ADMIN`**.
- 서버 동작(회신 §1 그대로): product·product-content·pricing 은 직원이면 부를 수 있다. 단 **상품 판매 상태 변경만 `MD`** — `OPERATOR_ADMIN` 에게는 그 버튼을 숨기거나 403 을 "MD 만 바꿀 수 있습니다"로. 센터 취급은 토큰 `centers` 에 그 센터가 있어야 한다(없으면 403).
- 백엔드 정본 표(`backoffice-role-menus.md` §2)도 같이 고쳤다.
