# 회신 — 주문자 이름, 검색 첫 화면 인기 검색어, 새 처리방침 발효

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20
- 답하는 것: 고객 앱 세션 질문 3개(사용자 전달) — 사용자 "가로 해줘", "같이 켜주고 그렇게 회신해줘"

## 1. 주문서 "주문자 이름" — 따로 보여 주거나 받지 않는다
- 주문자 = 로그인한 고객 계정 자체다. 주문(order-service) 에 주문자 이름 칸은 없다.
- 주문서에 보여 줄 이름은 **받는 분**(`ShippingAddress.receiverName` / `DropInfo.receiverName`) 하나다. customer 가 주소 → 프로필 순으로 채우며 null 일 수 있다(R4 — 비어 있으면 칸을 숨기거나 "이름 없음").
- 연락처도 주문서에서 따로 받지 않는다(없으면 CONTACT_REQUIRED → `POST /me/profile-changes {phone}` 뒤 초안 다시 만들기, R3 (가)).
- 선물처럼 주문자와 받는 분이 다른 경우의 표시는 1차 범위 밖(업무 규칙 없음).

## 2. 인기 검색어 — `GET /search/suggestions` q 선택(search 0.5.2)
- 검색 첫 화면에서 `GET /api/v1/search/suggestions`(q 없이, `limit` 은 그대로 선택) → `keywords: []`, `popular: [...]`.
- q 가 공백뿐이어도 같다. q 가 있으면 전과 같다(자동완성 + popular).
- popular 는 최근 7일·검색자 2명 이상·결과 1건 이상 검색어 상위 10개(몇 분 사본). 집계가 없으면 빈 목록 — 화면은 빈 칸을 숨긴다.

## 3. 새 개인정보 처리방침 발효 — 휴대폰 확인 스위치와 같은 날(2026-11-20)
- auth `require-identity: true`(pmart-config 4749e22) 와 customer `consents.versions.privacy-policy: '2026-11-20'` 를 같은 날 켰다.
- 그래서 기존 회원은 다음 로그인에서 **동의 화면(AUTH_CONSENT, 처리방침 새 판) → 휴대폰 확인(AUTH_IDENTITY)** 을 한 번씩 거친다. 본문은 `GET /api/v1/consent-terms/PRIVACY_POLICY/2026-11-20`.

## 배포 상태
- **LAN 배포 2026-11-20 11:15 KST**: search-service 6a4f6e4e(0.5.2 — 게이트웨이 경유 `GET /api/v1/search/suggestions` → `{"keywords":[],"popular":["쌀"]}` 확인), customer-service 재시작(pmart-config 089d637 — 처리방침 2026-11-20), auth-service require-identity(4749e22, 10:46).
