# 게이트웨이 허용 목록 실제 차단·`/internal` 호출자 확인 — 하면 어떻게 되나 (2026-11-21)

사용자가 찾아보려고 정리한 참고 문서다(결정 문서·ADR 아님). 할 일 줄: `docs/operations/pc1-todo.md` §3.

## 1. 게이트웨이 사용자 종류 허용 목록 — RECORD → ENFORCE

### 지금
- api-gateway `UserTypeAllowlistFilter`, 설정 `pmart.gateway.user-type-allowlist.mode`(pmart-config `api-gateway.yml`) = **RECORD**.
- 목록은 계약 `x-pmart-user-types` 로 만든 생성 파일 `platform-services/api-gateway/src/main/resources/access/user-type-allowlist.txt`(`METHOD 경로 사용자종류…`). 다시 만들기: `PMART_WRITE_ALLOWLIST=1 ./gradlew.bat :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'`.
- RECORD 는 막지 않고 어긋난 요청을 경고 로그(`user-type allowlist NOT_LISTED|TYPE_NOT_ALLOWED method=… path=… userType=…`)와 지표 `pmart_gateway_user_type_allowlist_total` 로만 남긴다.

### 바꾸면
- 앱이 게이트웨이를 거쳐 보내는 **모든 요청**을 "이 경로를 이 사용자 종류(CUSTOMER·GUEST·OPERATOR·RIDER…)가 불러도 되나"로 본다.
  - 목록에 없는 경로(NOT_LISTED) → **403 `USER_TYPE_NOT_ALLOWED`**
  - 목록에는 있지만 그 사용자 종류가 아님(TYPE_NOT_ALLOWED) → **403**
- 영향 받는 곳: 고객 앱, 라이더 앱, 백오피스 웹 4개(통합·HR·MFC·라이더), MFC 앱·시뮬레이터, LAN 점검 스크립트 — 게이트웨이를 거치는 모든 것.
- 영향 없는 곳: 서비스끼리 직접 부르는 호출(Eureka·서비스 DNS, 게이트웨이를 지나지 않음).

### 위험
1. **새 경로 누락**: 계약에 경로를 넣고 허용 목록을 다시 만들지 않으면 그 기능이 바로 403. 지금까지 실제로 있었다(#314 — 운영자 RAG 채팅 세션 3줄, f59c60bd — 새 GET 목록). PR 처리용 백엔드 세션이 병합 때 확인하기로 했다(2026-11-21).
2. **직원 토큰으로 고객 경로를 부르는 도구**: 로그(11-18·19)에 OPERATOR 토큰으로 `POST /order-drafts`·`GET /orders`·`GET /carts/{id}`·`GET /me/order-preferences`·`POST /orders/{id}/hides`·`GET /customer-assistant/faqs`, RIDER 토큰으로 `GET /return-handovers` 를 부른 기록이 있다 — 정상 앱 흐름이 아니라 점검 스크립트·시뮬레이터로 보인다. 그런 도구는 고객·라이더 토큰으로 바꿔야 한다.
3. **라우트에 없는 경로**: `/control/**`·`/bff/**`·`/rider-inquiries/**`·`/tracking/**`·`/staff/**`·`/checkout-drafts/**`·`/delivery-executions/**` 같은 기록은 게이트웨이 라우트·계약에 없는 경로 — 지금도 404 라 403 으로 바뀌어도 기능 영향은 없다(오래된 앱 코드·잘못 쓴 주소일 수 있음).

### 지금까지 근거(2026-11-21 09:04 확인)
- es-logs(`pmart-logs-*`, 3일치 보관) 53건. 실제 경로의 누락은 그 뒤 허용 목록 재생성(마지막 4c5fdec4, 2026-11-21 00:26)으로 모두 고쳐졌다.
- 마지막 재생성 뒤(2026-11-20 15:27Z 이후) 경고 **0건** — 다만 대부분 밤 시간이다.
- 확인 방법: `curl http://192.168.0.40:9200/pmart-logs-*/_search` 에 `{"query":{"bool":{"must":[{"match_phrase":{"message":"user-type allowlist"}},{"range":{"@timestamp":{"gte":"<시각>"}}}]}}}`.

### 되돌리기
- pmart-config `api-gateway.yml` 의 `mode: ENFORCE` → `RECORD` → `lan.sh config-push` → `lan.sh restart pc4 api-gateway`. 몇 분.
- 전환·되돌림 때 PR 처리용 백엔드 세션에 시각·설정 커밋을 알린다(그 세션 요청).

## 2. `/internal` 호출자 확인

### 지금 — 결정(ADR-0026)대로
- ADR-0026 "서비스 간 호출은 인증하지 않는다 — 신뢰 경계는 private subnet 과 API Gateway"(승인, ADR-0011 서비스 토큰을 일부 대체).
- 게이트웨이는 밖에서 오는 `/internal/**`·`/api/*/internal/**` 를 **모두 거부**한다(`GatewaySecurityConfig` denyAll). 서비스 토큰(typ=SERVICE)을 바깥 경로에 쓰면 그것도 거부.
- 그래서 `/internal` 은 **내부망 안에서만** 부를 수 있다. 내부망 안의 누구든(다른 서비스·LAN PC 의 사람) 부를 수 있다는 것이 남은 빈틈이다. 예: orchestration manual-actions 의 operatorId 를 본문으로 믿음, order 내부 배송지 조회(`/internal/orders/{id}/delivery-destination`)는 토큰 없이 주소를 준다.

### 하려면 바뀌는 것
- **승인된 ADR-0026 을 뒤집는 결정** — 새 ADR 이 먼저.
- `/internal` 을 부르는 쪽·받는 쪽 거의 모든 서비스(40개 안팎)가 서비스 토큰을 받고(auth-service client_credentials)·붙이고·검사하도록 고친다. 공통 라이브러리(common-web·common-security) 변경 → 모든 서비스 재빌드·재시험·재배포.
- auth-service(토큰 발급)가 멈추면 서비스끼리의 호출도 멈춘다(토큰 캐시로 줄일 수는 있음). 호출마다 토큰 검사 지연.
- 주문·결제·재고·배송 흐름 전체 다시 시험.

### 막아 주는 것
- 내부망에 이미 들어온 공격자·잘못된 서비스·LAN PC 에서 직접 부르는 사람. 지금 LAN 개발 단계에서 얻는 것은 적다.

### 선택지(정하지 않음)
- (가) 지금처럼(ADR-0026 유지) — Kubernetes 로 갈 때 NetworkPolicy 로 서비스별 호출 허용을 좁힌다.
- (나) 민감한 내부 경로만 서비스 토큰(배송지·수동 조치·환불 실행 등) — 범위가 작다.
- (다) 모든 `/internal` 에 서비스 토큰 — ADR-0011 로 돌아감, 가장 큼.
- (라) Kubernetes 에서 서비스 메시 상호 TLS(mTLS)로 호출자 확인 — 코드 변경 적음, 인프라 결정 필요.
