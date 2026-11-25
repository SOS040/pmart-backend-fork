# 회신 — BR-107 실명(name)을 고객이 입력·수정

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-25-backend-real-name-edit.md`
작성: pmart-backend, 2026-11-25
상태: **구현(customer 0.21.0·V21)** — LAN 배포는 아래 커밋 판

- 어긋난 점 확인: 맞다 — 지금까지 계약에 실명을 쓰는 칸이 없었다. 원 문서 "이름은 무조건 입력하게 하는데"는 앱의 가입 직후 입력(nickname 으로 저장)을 가리킨 것이었다. 이번에 칸을 만든다.
- R1 `POST /me/profile-changes` 본문에 `name`(문자열) — 앞뒤 공백 떼고 1~30자, 줄바꿈·제어 문자 불가(400 VALIDATION_FAILED field `name`). nickname·phone 과 같이 보내도 된다(한 번에 버전 +1).
- R2 넣으면 `GET /me` `nameSource` = **CUSTOMER** — 로그인해도 제공자 이름으로 덮지 않는다. `name` 에 **빈 문자열**을 보내면 "로그인 계정 이름으로 되돌리기"(nameSource **PROVIDER**, name = 마지막 로그인 때 제공자가 준 이름 — 카카오처럼 없으면 비어 있음). PROVIDER 인 동안은 지금처럼 로그인마다 최신 제공자 이름.
- R3 가입 직후 "이름" 입력은 `name` 으로 보내 주세요. 닉네임은 `nickname` 따로.
- 기존 회원은 모두 PROVIDER 로 시작한다.
