# 회신 — BR-91 FAQ 답 의견([도움이 되었어요]/[수정이 필요해요])

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-23-backend-faq-feedback.md`
작성: pmart-backend, 2026-11-23 — 사용자 "2번으로"(customer-assistant 는 PC 4 영역이지만 PC 1 이 넣음, PC 4 확인), Q1 "추천대로"
상태: 코드·시험 끝(customer-assistant 단위 139·통합 30), **LAN 배포는 사용자 "배포" 뒤**

## R1 — customer-assistant 0.4.0 `POST /customer-assistant/faqs/{faqId}/feedback` (회원, Idempotency-Key·X-Source-System)
- 본문 `{helpful: boolean}` — true 도움이 되었어요, false 수정이 필요해요.
- 200 `{faqId, helpful}`(지금 내 값). 한 고객 한 FAQ 하나 — 다시 누르면 마지막 값으로 바뀐다. 같은 요청을 다시 보내도 결과가 같아 결과 불명이면 그냥 다시 보내면 된다.
- 지금 색인에 없는 faqId 404, 비회원·운영자 403, FAQ 색인이 없으면 422 CONFIG_REQUIRED.
- `GET /customer-assistant/faqs`·`/faqs/{faqId}` 항목에 `myFeedback`(true·false, 안 눌렀으면 null) — 눌린 모양을 이것으로.
- **Q1 답: 글은 받지 않는다**(사용자 "추천대로" — 글에 개인정보가 섞일 수 있어 보관·삭제 규칙이 따로 필요). 고칠 내용을 알리고 싶은 고객은 고객센터 문의로.
- 집계는 운영용(`GET /customer-assistant/faq-feedback`, CS_AGENT·OPERATOR_ADMIN) — 고객 화면엔 숫자 없음.
- faqId 는 같은 문서·같은 질문이면 재색인해도 같아 누른 값이 이어진다(질문 문구를 고치면 새 faqId 라 다시 안 누른 상태).
