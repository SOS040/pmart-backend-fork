# 인증 참고 원문

사용자가 넣은 외부 자료(추천 글·검토 글)의 **원본**을 날짜별로 둔다.

- 원문은 고치지 않는다. 받은 파일 이름이 깨져 있으면 원문 제목으로 저장하고 아래 표에 적는다.
- 원문을 읽고 확인한 것은 [`../`](../) 에 `날짜-주제-review.md` 로 쓴다.
- 원문의 내용은 참고 의견이다. 결정은 ADR(0011·0018·0019·0025·0026 등)과 사용자 결정이 정본이다.

| 날짜 | 파일 | 무엇 | 확인 |
|---|---|---|---|
| 2026-11-10 | [`2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`](2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md) (받은 이름 `PMart_API_Gateway______JWT______.md`, 내용 그대로) | 게이트웨이 1개, URL 경로별 발급자·대상·권한 검증(위 Keycloak 글 전제) | [`../2026-11-10-gateway-url-jwt-review.md`](../2026-11-10-gateway-url-jwt-review.md) — ADR-0054 뒤 §5 |
| 2026-11-12 | [`2026-11-12/pmart-keycloak-guide.html`](2026-11-12/pmart-keycloak-guide.html) | 강의 설계(ChatGPT 대화 2개 정리)를 PMart 에 맞춘 Keycloak 직원 인증 적용안 — Employee Auth Service 가 Keycloak 기밀 클라이언트·PMart 직원 JWT 발급, HR Service 가 Admin API 로 계정 발급 | [`../employee-auth-structure.md`](../employee-auth-structure.md) — 사용자 "이 설계를 따른다"(2026-11-12) |
| 2026-11-12 | [`2026-11-12/baropick_mail_iam_guide.html`](2026-11-12/baropick_mail_iam_guide.html) | 사내 메일 서버(docker-mailserver + Roundcube)와 Keycloak 인증 메일 연동 가이드(원본 2종 통합본, 예시 도메인 `baropick.test`) | [`../employee-auth-structure.md`](../employee-auth-structure.md) §9 — 도메인은 `@pmart.com`(사용자 결정) |
| 2026-11-12 | [`2026-11-12/PMart_직원_IAM_아키텍처.html`](2026-11-12/PMart_직원_IAM_아키텍처.html) | 직원 IAM 설계 검토 자료(C4·책임·생명주기·신뢰 경계·충돌 U-01~U-15, 저장소 미열람 상태로 작성) | [`../employee-auth-structure.md`](../employee-auth-structure.md) §6-1 — HR Admin API 자격(`hr-provisioner`·역할 3종·Client Credentials) 반영. 토큰 보관 R-1 추천은 채택 안 함(사용자 "R-3 그대로", ADR-0056 대안), OTP(N-01)는 D10 미정 |
