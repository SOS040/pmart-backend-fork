# 알림 — FAQ 답 의견 집계 API(customer-assistant 0.4.0, BR-91)

보낸 영역: platform
받는 앱: backoffice(PC 2)
작성: pmart-backend, 2026-11-23 — 고객앱 BR-91 "집계는 운영(백오피스) 용"
상태: **LAN 배포 2026-11-23**(이미지 eaa73751), claude-dev 로 집계 200 확인. 화면은 급하지 않음(만들지 여부는 PC 2·사용자 판단)

- `GET /customer-assistant/faq-feedback` (CS_AGENT·OPERATOR_ADMIN, 그 밖 403) → `{items: [{faqId, category, question, helpfulCount, notHelpfulCount, lastAt}]}` — 수정 필요 많은 순. category·question 은 지금 색인 기준(질문 문구가 바뀌어 없어진 FAQ 면 null). 고객마다 마지막 값만 센다. 글은 받지 않는다.
