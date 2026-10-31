# ADR-0026 서비스 간 호출은 인증하지 않는다 — 신뢰 경계는 private subnet 과 API Gateway

- 상태: 승인 (2026-10-31, 사용자 결정 "서비스간 통신할때 서비스 인증은 필요하지 않다")
- 대체: ADR-0011 의 결정 3(서비스 토큰 발급)·5(호출 측 서비스 토큰)와 "`/internal/**` 는 TYPE_SERVICE 토큰" 규칙. ADR-0011 의 결정 1(RS256 서명키)·2(JWKS)는 사용자 토큰 검증용으로 유지한다.
- 관련: ADR-0011, `.claude/rules/security-config.md`, `libraries/common-security`

## 배경
- 운영 배치: 외부와 통신하는 것은 API Gateway 하나뿐이고, 나머지 서비스는 모두 AWS VPC 의 private subnet 에서 실행된다. 게이트웨이는 요청을 처리할 서비스의 공개 API 로만 라우팅하고 `/internal/**` 는 막는다(denyAll).
- ADR-0011 은 서비스 간 호출마다 auth-service 가 발급한 서비스 토큰(client_credentials, 5분)을 요구했다. 그래서 서비스마다 secret 을 발급·주입·교체해야 했고(LAN `init-secrets.sh`·`render.sh`, 운영 Secret Manager), 내부 호출을 새로 하는 서비스가 생길 때마다 등록부·비밀값을 추가해야 했다(예: 주문 취소의 claim-service).
- 사용자 결정: 위 배치에서는 네트워크 경계로 충분하므로 서비스 간 인증을 두지 않는다.

## 결정
1. **`/internal/**` 는 토큰 없이 받는다.** 받는 쪽(common-security `InternalCallerFilter`)은 토큰이 없는 내부 요청을 `X-Source-System`(호출 서비스 이름) 주체의 TYPE_SERVICE 로 채운다. 이 이름은 호출자 식별(멱등 기록 actor·로그)용이며 권한 증명이 아니다(자기 신고). 헤더가 없으면 `internal`.
2. **사용자 토큰으로 내부 경로를 부르면 403** 이다(고객·운영자·비회원 토큰). 토큰이 붙어 오면 지금처럼 검증하므로 위조 토큰은 401.
3. **호출 측은 토큰을 붙이지 않는다.** RestClient 공통 인터셉터는 `/internal/` 호출에 `Authorization` 을 넣지 않고 `X-Source-System`·`X-Correlation-Id` 만 붙인다. 내부가 아닌 호출은 지금처럼 사용자 토큰을 전파한다.
4. **auth-service 서비스 토큰 발급(`POST /internal/service-tokens`)과 서비스 클라이언트 등록부(`pmart.auth.service-clients`)를 없앤다.** 계약에서도 경로·스키마를 지운다. 서명키·JWKS·사용자 토큰(고객·운영자·비회원)은 그대로다.
5. **비밀값 정리**: `PMART_SERVICE_CLIENT_SECRET`, `pmart.security.service-token.*`, LAN `.secrets/service-clients/` 생성·주입을 없앤다. 이미 만든 파일은 쓰이지 않으므로 지워도 된다.
6. **사용자 토큰 검증은 그대로**: 게이트웨이와 각 서비스가 공개 API 의 사용자 토큰(서명·만료·typ·역할·센터)을 계속 검증한다.

## 대안
- **ADR-0011 유지(서비스 토큰)**: 내부 서비스 하나가 악용될 때 다른 서비스로 번지는 것을 막는 두 번째 방어선이지만, 이 배치에서는 필요성이 낮다고 판단했다(사용자 결정).
- **mTLS·서비스 메시·VPC Lattice IAM**: 플랫폼 도입이 필요하다. 나중에 필요해지면 새 ADR 로 다시 본다.

## 결과
- 영향: `libraries/common-security`(모든 서비스), auth-service(발급 경로·등록부 제거), 서비스 토큰을 쓰던 서비스 설정(cart·claim·delivery-capacity·mfc-inbound·order-orchestration·order·product-content), LAN 배포 스크립트.
- 서비스 코드가 보던 "호출자가 서비스인가"(`PrincipalType.SERVICE`)는 그대로 성립한다(내부 경로는 서비스 주체로 채워짐). 다만 그것이 인증된 사실은 아니다 — 서비스별 호출 권한 제한(ADR-0011 남은 일)은 하지 않는다.
- 전제가 바뀌면(다른 워크로드와 VPC 공유, 내부 경로 외부 노출 등) 이 결정을 다시 본다.
- 개발 LAN 은 서비스 포트가 PC 에 열려 있어 같은 LAN 에서 내부 API 를 직접 부를 수 있다. 개발 환경 전용이며 운영 전제(private subnet)와 다르다.
