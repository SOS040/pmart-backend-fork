# [pmart-backend → pmart-clients] 회신: LAN 게이트웨이 CORS 에 웹 개발 서버 출처 넣음 (2026-11-16)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/web-common/2026-11-16-backend-lan-cors-dev-origins.md`

## 1. 반영
- 설정 저장소 `api-gateway-lan.yml`(2d05239, LAN 한정 — 운영 설정은 안 바꿈): `allowed-origins`·`credentialed-origins` 에 `http://localhost:5173`·`5174`·`5175`. 게이트웨이 다시 띄움.
- 확인: `https://192.168.0.40:8443` 사전 요청 — `Origin: http://localhost:5175` 200, refresh 경로에 `Access-Control-Allow-Origin: http://localhost:5173`·`Allow-Credentials: true`, 목록에 없는 출처 403.
- 직원 로그인 복귀 주소(`http://localhost:517x/auth/callback`)는 원래 허용돼 있다.

## 2. 질문 답 — refresh 쿠키는 localhost 에서 실리지 않는다
- refresh 쿠키(`__Secure-pmart_ea_rt`)·CSRF 쿠키는 `Secure`·**`SameSite=Strict`**(ADR-0059). `http://localhost:517x` 페이지 → `https://192.168.0.40:8443` 은 **다른 사이트**라 브라우저가 이 쿠키를 저장·전송하지 않는다(포트만 다른 `https://192.168.0.40`·`:8444`·`:8445` 는 같은 사이트라 지금처럼 된다).
- 그래서 `dev:lan` 에서는: 로그인·API 호출은 되고(access token 은 응답 본문), **조용한 갱신·새로 고침 뒤 세션 이어 쓰기는 안 된다** — access token 15분이 지나거나 페이지를 새로 고치면 로그인 화면으로 간다. Keycloak 로그인 세션이 살아 있으면 비밀번호 없이 다시 들어올 가능성이 높다(확인은 아직).
- 쿠키를 `SameSite=None` 으로 풀면 되지만 ADR-0059 보안 결정을 바꾸는 일이라 하지 않는다. 개발 서버를 HTTPS 로 띄워도 사이트(호스트)가 달라 같다.
- 화면 확인은 이대로(15분 안에 보거나 다시 로그인) 하고, 갱신·새로 고침 동작은 LAN 빌드(443·8444·8445)에서 본다.

## 3. 함께
- case 0.5.0(사가·결제 이상·배차 실패, 주문으로 문의 찾기)·backoffice-bff 0.3.0(통합 조회·실시간 배송 현황) LAN 배포됨 — 새 backoffice 빌드를 `build:lan` 해서 알려 주면 올린다.
