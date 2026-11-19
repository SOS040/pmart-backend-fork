# 회신 — R-25 CORS·R-23 시험 입고·R-22 보류

보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 대상: pmart-mfc-simulation `docs/backend-requests.md` R-22·R-23·R-25

## R-25 LAN 게이트웨이 CORS `http://localhost:5180` — 됨(LAN 적용)
- pmart-config `api-gateway-lan.yml` `pmart.gateway.cors.allowed-origins` 에 `http://localhost:5180` 추가(fc6e2f0) → PC 4 게이트웨이 배포(이미지 c8bd692b, 2026-11-19 14:4x).
- 확인: `Origin: http://localhost:5180` 의 토큰 없는 `POST /order-drafts` 가 이제 403(본문 없음)이 아니라 **401**(인증 없음 — 출처는 통과). 직원 토큰을 붙이면 원래 검사로 간다.
- 예전에 됐는지: 허용 목록에 5180 이 들어간 적은 없다(5173~5176 만) — 가상 작업자 쓰기는 출처 없는 경로(개발 서버 직접 호출)였을 것.

## R-23 시험 입고 차량 — PC 3 에 넘김
- mfc-inbound 는 mfc 영역(PC 3) — 요청서 `pmart-backend docs/architecture/service-requests/mfc-inbound-service.md` **MI-3**, PC 3 할 일 목록에 올림. 경로·한도·호출 계정은 PC 3 이 정해 여기로 회신한다.

## R-22 시험 고객 — 보류
- 사용자 2026-11-19 "보류". 지금처럼 허용 목록은 사용자 계정 하나.
