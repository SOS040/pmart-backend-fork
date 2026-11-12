# 2026-11-12 새 직원 토큰이 업무 서비스에서 500 — 다른 PC 컨테이너가 `employee-auth-service` 이름을 못 찾음(UnknownHost)

- 범위: ADR-0056 3단계(employee-auth-service, PC 5:8511) LAN 배치 직후. 발급자 표에 `staff-ea` 줄(공개키 주소 `http://employee-auth-service:8511/api/v1/internal/jwks`)을 더하고 앱을 재시작했는데, PC 2~4 업무 서비스가 새 직원 토큰을 받으면 500 을 냈다.
- 관련: ADR-0056, pmart-config `application-lan.yml`(발급자 표), `deploy/lan/render.sh`(extra_hosts), `deploy/lan/README.md` "주소 규칙", 작업 기록 `docs/worklog/2026-11-12.md`.

## 요약

| 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|
| 새 직원 토큰(`iss=http://employee-auth-service:8511`)으로 게이트웨이 경유 `GET /api/v1/centers`·`/approvals`·`/products` → **500**. 같은 계정의 Keycloak 토큰은 200, 게이트웨이·employee-auth 자신은 새 토큰 200 | PC 2~4 앱 컨테이너 안에서 이름 `employee-auth-service` 를 찾지 못함 → 공개키(JWKS) 조회 `java.net.UnknownHostException: employee-auth-service` → 토큰 검증 중 예외(`AuthenticationServiceException`) → 500 | PC 2~4 앱 컨테이너를 **다시 만듦**(`lan.sh deploy <pc> <앱…>`) — 새 compose 의 `extra_hosts` 에 `employee-auth-service:192.168.0.101` 이 들어감 | **해결** — 2026-11-12 22:0x LAN. 아래 §3 확인 |

## 1. 경과

1. employee-auth-service 를 PC 5 에 배치하고(`placement.conf` pc5 줄 끝에 추가), 게이트웨이(PC 4)를 새 설정으로 배포(컨테이너 다시 만듦)했다.
2. 업무 서비스가 발급자 표의 새 줄을 읽게 하려고 PC 2~5 앱 35개를 **`lan.sh restart`** 로 재시작했다. 37개 모두 health UP.
3. claude-dev 로 새 직원 토큰을 받아 업무 API 를 불렀더니 500. 같은 시각 Keycloak 토큰은 200, 위조 토큰은 401.
4. center-master-service(PC 4) 로그: `I/O error on GET request for "http://employee-auth-service:8511/api/v1/internal/jwks": employee-auth-service` — `UnknownHostException`.

## 2. 왜 그렇게 됐나

- LAN 은 PC 마다 Docker 네트워크가 따로다. 같은 PC 의 구성요소는 컨테이너 이름으로 바로 찾지만, **다른 PC 의 구성요소 이름은 각 컨테이너의 `extra_hosts`(이름 → 그 PC IP)** 로 찾는다(`render.sh` 가 `placement.conf` 로 모든 PC compose 에 넣는다).
- employee-auth-service 는 이번에 처음 생긴 이름이라, PC 2~4 의 **실행 중인 컨테이너**에는 이 줄이 없었다. `render.sh` 로 만든 새 compose 에는 들어 있었지만, 그 compose 는 PC 5·PC 4(게이트웨이)에만 배포했다.
- **`lan.sh restart`(= `docker compose restart`)는 컨테이너를 그대로 다시 켤 뿐 compose 변경(`extra_hosts` 등)을 반영하지 않는다.** 설정 서버의 새 발급자 표는 읽었지만(재시작 때 설정을 다시 받음) 그 공개키 주소의 이름은 찾을 수 없었다.
- PC 5 서비스는 같은 Docker 네트워크라 이름으로 바로 찾아 문제가 없었다. 게이트웨이(PC 4)는 다시 만들어서 새 `extra_hosts` 가 있었다 — 그래서 게이트웨이는 통과하고 그 뒤 서비스에서만 500 이 났다.
- 라이더 줄(`rider-auth-service`)은 같은 방식인데 문제가 없었던 이유: 그 이름은 그 뒤 전체 배포 때 이미 모든 PC compose 에 들어가 있었다.
- 500 이지 401 이 아닌 이유: 공개키를 못 받은 것은 "토큰이 틀림"이 아니라 "검증할 수 없음"(인증 서비스 예외)이라 Spring Security 가 500 으로 냈다.

## 3. 해결과 확인

- 다시 만들기 전에 원격 compose 와 새로 렌더한 compose 의 **이미지 줄을 비교 — PC 2·3·4 모두 차이 0**(다른 이미지가 함께 바뀌지 않게).
- `lan.sh deploy pc4|pc3|pc2 <앱…>`(`--no-deps`) — 앱 26개 다시 만듦. **부수 효과: `deploy` 는 그 PC postgres 를 먼저 `up --wait` 하므로 PC 2·3 의 postgres 도 다시 만들어졌다**(postgres 의 `extra_hosts` 도 바뀌어서). 데이터는 볼륨에 그대로, Debezium 커넥터 29개 모두 RUNNING 으로 확인.
- 확인(실행함):
  - 앱 컨테이너 37개 안에서 `getent hosts employee-auth-service` — PC 2(12)·PC 3(7)·PC 4(8) → `192.168.0.101`, PC 5(10) → `172.28.0.25`(같은 네트워크). 못 찾는 컨테이너 0.
  - 서비스 37개에 **직접**(게이트웨이 없이) 없는 경로 `GET /api/v1/zz-probe`: 새 직원 토큰 → 37개 모두 404(인증 통과), 위조 토큰 → 37개 모두 401.
  - 게이트웨이 경유: `GET /api/v1/centers`·`/approvals`·`/products?size=1`·`/employee-auth/sessions/current` 200, MFC(PC 3) `GET /api/v1/locations` 400 `Required parameter 'mfcId'`(인증 통과 뒤 입력 검사), 위조 토큰 401, Keycloak 토큰(전환 기간 줄) 200.

## 4. 다시 생기지 않게

- `deploy/lan/README.md` "주소 규칙"에 규칙을 적었다(`1745ff4`): **새 구성요소를 더하고 다른 PC 의 서비스가 그 이름을 부르면, 그 PC 들의 앱을 `lan.sh deploy <pc> <앱…>` 으로 다시 만든다. `restart` 로는 안 된다.**
- ADR-0056 5단계의 hr-service·mail-provisioner(PC 5)는 다른 PC 서비스가 이름으로 부르지 않는다(게이트웨이는 Eureka `lb://`, employee-auth 는 같은 PC). 그래도 배치할 때 이 규칙을 확인한다.
- 검토만 한 대안(하지 않음): 발급자 표 `jwk-set-uri` 를 이름 대신 PC IP 로 적으면 `extra_hosts` 에 기대지 않는다. 다만 서비스를 다른 PC 로 옮길 때 설정을 바꿔야 하고, 다른 줄(auth-service·rider)과 방식이 달라진다 — 이름 방식 유지.
