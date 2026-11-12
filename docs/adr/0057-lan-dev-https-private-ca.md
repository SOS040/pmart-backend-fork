# ADR-0057 LAN 개발 환경 HTTPS 는 사설 루트 CA 로 — 입구만 HTTPS, 루트 공개 인증서는 저장소에, 안드로이드는 lan/debug 빌드에만 신뢰 설정

- 상태: 승인 (2026-11-12, 사용자 "권장안대로 하자" — T1~T6), 1단계(CA·인증서·Windows 신뢰) 구현.
- 상세 계획·진행: `docs/architecture/lan-https-private-ca.md`. 운영 인증서는 이 ADR 범위 밖(공인 인증서로 따로 정한다).
- 관련: ADR-0056(직원 로그인 — 비밀번호·인가 코드·티켓, 결정 9 메일 내부 TLS, 결정 11 Keycloak `/iam`), ADR-0054 결정 I.

## 배경
- LAN 은 전부 HTTP 다. 직원 로그인(ADR-0056)의 비밀번호·인가 코드·로그인 티켓·Refresh, OTP 등록 QR(보류)이 평문으로 오간다. 도메인이 없어 공인 인증서를 받을 수 없다.

## 결정
1. PC 1 에서 openssl 로 **사설 루트 CA** 를 만든다(10년). 개인키는 `deploy/lan/.secrets/tls/`(git 제외), **공개 인증서 `deploy/lan/tls/pmart-lan-root-ca.crt` 는 커밋**한다(T4).
2. PC 별 서버 인증서(1년, SAN = LAN IP + 컴퓨터 이름 + `tls/extra-sans.conf`)를 `lan.sh tls-issue` 로 발급·갱신한다. `watch.sh` 가 만료 30일 전부터 알린다(T5).
3. **입구만 HTTPS**(T2): api-gateway(8443 추가), backoffice-web(443), Keycloak, Roundcube, 메일 서버 내부(ADR-0056 결정 9 의 자체 서명을 대체). 서비스끼리·DB·Kafka·Redis·운영 도구는 HTTP 그대로.
4. 순서(T1): CA 를 메일 서버보다 먼저. 게이트웨이·Keycloak HTTPS 는 ADR-0056 4단계(Keycloak `/iam`)와 **같은 배포**에서 — `iss` 를 한 번만 바꾼다. HTTP 입구는 클라이언트 전환 확인 뒤 닫는다(T3).
5. 기기 신뢰: Windows PC 는 `lan.sh tls-trust`(LocalMachine\Root). 안드로이드 앱(고객·라이더·mfc_app)은 **lan/debug 빌드에만** 앱에 넣은 CA 를 신뢰하는 `network_security_config` — 운영 빌드에는 넣지 않는다(T6, 클라이언트 handoff). 직원 로그인용 시스템 브라우저를 위해 기기에도 설치.

## 결과
- 2026-11-12: 루트 CA(`CN=PMart LAN Dev Root CA 2026`, SHA-1 `D3A46343…CD2A`), PC 4·5 인증서, PC 2~5 Windows 신뢰 설치. PC 1 은 관리자 승인 대기.
- 루트 CA 를 다시 만들면 모든 기기·앱에 다시 넣어야 한다 — 개인키 백업이 필요하다(.secrets 백업 방침과 함께).
- 운영으로 갈 때 사설 CA 신뢰 설정은 운영 빌드·이미지에 들어가지 않게 한다.
