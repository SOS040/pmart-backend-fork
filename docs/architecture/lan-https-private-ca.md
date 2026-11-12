# LAN 개발용 HTTPS — 사설 CA 계획

- 작성: 2026-11-12. **상태: 계획(결정 전, 구현 전)** — 사용자 "개발용 CA 로 할 수 있는 거 알아? … 정리해줘"(2026-11-12). 정할 것(§8)이 정해지면 ADR 로 올린다.
- 왜: 지금 LAN 은 전부 HTTP 다. 직원 로그인(ADR-0056)에서 비밀번호·인가 코드·로그인 티켓·Refresh 가 평문으로 오가고, OTP(D10, 보류) 등록 QR 의 비밀 값도 평문으로 간다. 참고 자료(`auth/references/2026-11-12/PMart_직원_IAM_아키텍처.html` §02-4)도 "Keycloak 은 사내 CA HTTPS 필요"로 적었다. 도메인이 없어도 **우리만 쓰는 인증기관(CA)** 으로 LAN IP·PC 이름용 인증서를 발급하면 HTTPS 를 쓸 수 있다.
- 범위: LAN 개발·시연 전용. 운영은 공인 인증서(Let's Encrypt·클라우드 인증서)로 따로 정한다.

## 1. 원리

1. PC 1 에서 **루트 CA**(우리 인증기관의 도장 — 키 + 인증서)를 하나 만든다.
2. 루트 CA 로 **서버 인증서**를 발급한다. 도메인 대신 SAN(주체 대체 이름)에 **IP 와 PC 이름**을 넣는다(예: `IP:192.168.0.40`, `DNS:d603-16`).
3. 접속하는 기기(PC 브라우저·휴대폰·PDA·스크립트)에 **루트 CA 인증서를 "신뢰함"으로 설치**한다 → 경고 없이 자물쇠.

루트 CA **인증서**는 공개해도 되는 값(기기에 설치하는 것)이고, 루트 CA **개인키**는 비밀값이다.

## 2. 지금 LAN 의 입구 (2026-11-12 확인)

| PC | 입구 | 지금 | 사람/앱이 직접 쓰나 |
|---|---|---|---|
| PC 4 | api-gateway | `http://192.168.0.40:8080` | 예 — 모든 앱 API |
| PC 4 | backoffice-web(Nginx) | `http://192.168.0.40`(80) | 예 — 백오피스 화면 |
| PC 4 | api-docs(Swagger UI) | `:8090` | 개발자 |
| PC 4 | Grafana `:3000`, Kibana `:5601`, Prometheus `:9090`, Alertmanager `:9093`, Eureka `:8761`, config-server `:8888` | HTTP | 개발자·운영 도구 |
| PC 5 | Keycloak | `http://192.168.0.101:8180` | 예 — 직원 로그인 화면(백오피스가 이 주소를 씀) |
| PC 5 | Roundcube(계획, ADR-0056) | `:8190` | 예 — 웹메일 |
| PC 5 | mailserver(계획) | LAN 포트 없음 | 아니오 — 내부 TLS 는 자체 서명 예정이었음 |
| 모든 PC | 서비스끼리, DB·Kafka·Redis | HTTP·평문 TCP(LAN 안) | 아니오 |

클라이언트(pmart-clients, 읽기만 확인): 백오피스·앱이 `http://192.168.0.40:8080`, `http://192.168.0.40`, `http://192.168.0.101:8180` 을 쓴다. 고객 앱은 debug 매니페스트에서 평문 HTTP 를 허용하고, `network_security_config.xml` 은 없다.

## 3. 범위 — "입구만 HTTPS"

| 단계 | 대상 | 이유 |
|---|---|---|
| **1단계(필수)** | api-gateway, backoffice-web, Keycloak, Roundcube | 비밀번호·토큰·티켓이 오가는 곳. 사람·앱이 직접 닿는 곳 |
| 2단계(선택) | Grafana, Kibana, api-docs | 로그인 화면이 있는 도구(관리자 비밀번호) |
| 하지 않음(LAN 개발) | 서비스끼리 HTTP, Eureka, config-server, Prometheus, DB·Kafka·Redis | 53개 서비스의 신뢰 저장소(truststore)·클라이언트 설정을 모두 바꿔야 함. 운영(Kubernetes)에서 서비스 메시·내부 TLS 로 따로 정한다 |

- mailserver 내부 TLS 는 자체 서명 대신 **같은 CA 로 발급**한다(ADR-0056 결정 9 의 "내부 자체 서명 인증서"를 대체 — Roundcube 가 확인을 끄지 않아도 됨).
- 서비스 → Keycloak(`/token`·certs), 서비스 → employee-auth JWKS 는 내부 HTTP 그대로. 단 **토큰의 `iss` 는 브라우저가 본 주소**라 `https://…` 로 바뀐다(§5).

## 4. 만드는 것

**도구**: `openssl`(이미 `render.sh`·`init-secrets.sh` 에서 씀 — 새 도구 없음). `mkcert` 가 더 간단하지만 CA 를 그 PC 저장소에 자동 설치하는 방식이라 여러 PC·휴대폰에 나눠 주는 우리 구성에는 openssl 이 맞다.

| 파일 | 내용 | 위치 |
|---|---|---|
| 루트 CA 개인키 | 비밀값, 유효 10년 | `deploy/lan/.secrets/tls/pmart-lan-root-ca.key`(git 제외) |
| 루트 CA 인증서 | 공개값 — 기기에 설치·앱에 넣음 | `deploy/lan/tls/pmart-lan-root-ca.crt`(**저장소에 커밋** — 클라이언트가 가져다 쓴다) |
| PC 별 서버 인증서·키 | PC 4: `IP:192.168.0.40`, `DNS:d603-16` / PC 5: `IP:192.168.0.101`, `DNS:d603-13`, `DNS:mail.pmart.com`(메일 내부 이름). 유효 1년 | `.secrets/tls/<pc>.crt`·`.key` → `render.sh` 가 그 PC 배포 파일로 넣음 |
| 스크립트 | `init-secrets.sh` 에 CA 만들기(없을 때만), `lan.sh tls-issue <pc>`(발급·갱신), `lan.sh tls-trust <pc>`(그 Windows PC 의 신뢰 저장소에 루트 CA 설치) | `deploy/lan/` |

IP 가 바뀌면(README "IP 변경 시 복구") 그 PC 인증서를 다시 발급한다.

## 5. 구성요소별 바뀌는 것

| 구성요소 | 바뀌는 것 |
|---|---|
| api-gateway | Spring Boot SSL 번들로 **8443 HTTPS 추가**(옮기는 동안 8080 HTTP 함께). CORS 허용 출처에 `https://192.168.0.40`·`https://d603-16` 추가 |
| backoffice-web | Nginx `listen 443 ssl` + 80 → 443 리디렉트(옮기는 동안은 둘 다) |
| Keycloak | `KC_HTTPS_CERTIFICATE_FILE`·`KEY_FILE`. ADR-0056 결정 11 로 공개 주소가 게이트웨이 `/iam` 이 되므로 **`KC_HOSTNAME=https://192.168.0.40:8443/iam`** — `iss` 는 한 번만 바뀌게 ADR-0056 4단계와 같은 배포 창에서 |
| 발급자 표 | Keycloak 줄 `issuer-uri` 를 `https://…/iam/realms/pmart-staff` 로(JWKS 는 내부 HTTP 그대로). employee-auth 의 `iss` 는 내부 이름이라 변화 없음 |
| Keycloak 클라이언트 | redirect URI·web origin 에 `https://` 주소 |
| Roundcube | 443 대신 `8190` 에 TLS(같은 PC 인증서) |
| mailserver | `SSL_TYPE=manual` + CA 발급 인증서 |
| 시험 스크립트(`deploy/lan/checks/*.py`, `watch.sh`) | HTTPS 주소에 루트 CA 경로(`verify=…`/`curl --cacert`) |
| 클라이언트(pmart-clients — handoff) | API·Keycloak 주소를 `https://` 로, **안드로이드 앱에 신뢰 설정**(아래) |

**안드로이드 주의**: Android 7 이상 앱은 휴대폰에 사용자가 설치한 CA 를 **기본적으로 믿지 않는다**. 앱(고객·라이더·mfc_app)에 `network_security_config.xml` 로 ① 앱에 넣은 `pmart-lan-root-ca.crt` 를 신뢰하거나 ② 사용자 CA 를 신뢰하도록 설정한다 — **lan/debug 빌드에만**, 운영 빌드에는 넣지 않는다. 앱이 연 시스템 브라우저(Custom Tabs, 직원 로그인)는 휴대폰에 설치한 CA 를 따른다.

## 6. 기기에 루트 CA 설치

| 기기 | 방법 |
|---|---|
| Windows PC(Chrome·Edge) | `certutil -addstore Root pmart-lan-root-ca.crt`(관리자) — `lan.sh tls-trust` 로 PC 2~5 원격, PC 1 은 UAC |
| Firefox | 설정 → 인증서 → 가져오기(또는 `security.enterprise_roots.enabled=true` 로 Windows 저장소 사용) |
| 안드로이드 휴대폰·PDA | 설정 → 보안 → 인증서 설치 → CA 인증서(브라우저·Custom Tabs 용). 앱은 §5 설정 |
| Python·curl 스크립트 | 인증서 경로 지정 |

## 7. 순서 (제안)

1. 루트 CA·PC 4·5 인증서 만들기, 스크립트, PC 들 신뢰 설치 — 동작 변화 없음.
2. **메일 서버(ADR-0056 2단계)를 처음부터 CA 인증서로** 띄움(자체 서명 단계 건너뜀).
3. **ADR-0056 4단계(Keycloak 을 게이트웨이 `/iam` 뒤로)와 같은 배포 창에서** 게이트웨이 8443·Keycloak HTTPS·발급자 표 `https` — `iss` 를 두 번 바꾸지 않게.
4. 백오피스 Nginx 443, 클라이언트 handoff(주소·안드로이드 신뢰 설정), 시험 스크립트.
5. 확인 뒤 HTTP 입구(8080·80·8180) 닫기 — 클라이언트 전환 확인 뒤.
6. (선택) Grafana·Kibana·api-docs.

## 8. 정할 것

| # | 질문 | 권장 |
|---|---|---|
| T1 | 언제 — 메일 서버 전/후 | **메일 서버 전에 1단계(CA 만들기)만**, 게이트웨이·Keycloak HTTPS 는 ADR-0056 4단계와 함께(§7) |
| T2 | 범위 | 1단계 필수 4곳(게이트웨이·백오피스·Keycloak·Roundcube) + 메일 내부. 운영 도구는 나중 |
| T3 | HTTP 입구를 언제 닫나 | 클라이언트 전환 확인 뒤(그 전까지 둘 다) |
| T4 | 루트 CA 인증서를 저장소에 커밋 | 예(공개값 — 앱·스크립트가 써야 함). 개인키는 `.secrets` |
| T5 | 인증서 수명 | 루트 10년, 서버 1년(`lan.sh tls-issue` 로 갱신, `watch.sh` 가 만료 30일 전 경보) |
| T6 | 안드로이드 신뢰 방식 | 앱에 CA 를 넣는 방식(lan/debug 빌드만) — 휴대폰마다 설치하지 않아도 앱 API 는 동작. 직원 로그인 브라우저용으로는 기기 설치도 필요 |
