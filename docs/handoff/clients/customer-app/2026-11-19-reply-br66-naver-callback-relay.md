# 회신 — BR-66 네이버 로그인 콜백 중계 (auth-service 0.5.0)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/customer-app/2026-11-19-backend-naver-oauth-callback-relay.md`
- 상태: **LAN 배포됨 2026-11-19 21:5x(이미지 32ae443b — PC 4 auth-service 0.5.0·api-gateway, pmart-config cf50142)**. 게이트웨이 경유 확인: 두 경로 모두 302, Location = 앱 주소 + 쿼리 그대로, no-store

## R1·R2 경로(앱마다 고정)
| 앱 | 네이버 콘솔 Callback URL (= 앱의 `redirect_uri`) | 서버가 넘기는 곳(302) |
|---|---|---|
| 고객 | `http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer` | `com.pmart.customer://oauth/naver?<받은 쿼리 그대로>` |
| 라이더 | `http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/rider` | `com.pmart.rider://oauth/naver?<받은 쿼리 그대로>` |
- 쿼리 원문(code·state 또는 error·error_description)을 다시 인코딩하지 않고 그대로 붙인다. 목적지는 코드에 고정 — 쿼리로 받지 않는다. 응답 `Cache-Control: no-store`. 쿼리 4096자 넘으면 400.
- 서버는 코드를 토큰으로 바꾸지 않고 저장하지 않는다 — 교환은 앱이 PKCE verifier 로 직접(`redirect_uri` 는 위 http 주소 그대로).
- 라이더 앱은 매니페스트에 scheme `com.pmart.rider`(host `oauth`, path `/naver`)를 등록해야 한다(지금 라이더 앱에 없음).

## R3 기록
- 인가 코드·state 를 읽지도 로그하지도 않는다. 서비스 지표·추적의 경로는 템플릿(쿼리 없음), 게이트웨이도 쿼리를 추적에 남기지 않는다(`TraceUriWithoutQuery`). 접근 로그는 켜져 있지 않다.

## R4 주소 고른 이유
- **http(8080)**: https(8443)면 폰 브라우저가 LAN 사설 CA 를 믿어야 해서 폰마다 CA 설치가 필요하다. 시험용이라 http 로.
- 네이버 콘솔이 IP·포트·http 주소를 받는지는 사용자가 넣어 보고 알려 달라(못 받으면 다른 방법을 정한다).
- 사내 와이파이의 폰에서만 된다(Tailscale·LTE 에서는 이 IP 가 닿지 않음). 운영은 도메인이 생기면 다시.

## R5 키
- 서버 허용 aud = 네이버 Client ID — 사용자가 백엔드에 아직 주지 않았다(받으면 설정만 넣음, 그 전에는 NAVER 로그인 422). 중계에는 secret 불필요.
