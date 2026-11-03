# ADR-0032 local·LAN 서비스 등록·조회는 Eureka — 인스턴스는 하나씩, 운영(Kubernetes)은 Service DNS

- 상태: 승인 (2026-11-03, 사용자 요청 "유레카 서버, 각 서비스들은 유레카의 클라이언트가 되도록". 결정: 목적은 학습·Eum식 구성(서비스당 인스턴스 1개), 운영에는 쓰지 않음)
- 대체: "Eureka(서비스 검색)는 두지 않는다"(2026-10-23 `discovery-service` 제거, ADR-0027 배경) 중 **local·LAN 부분**. 운영(Kubernetes)은 그대로 Service DNS.
- 관련: ADR-0026(서비스 간 인증 없음), ADR-0027·0029(config-server, 설정 저장소), `deploy/lan/README.md`

## 배경
- LAN 은 Docker Desktop PC 3대(pc2·pc3·pc4)에 앱 34개를 나눠 띄운다. 지금 서비스 간 주소는 `http://<서비스 이름>:<포트>` 이고, 다른 PC 의 이름은 compose `extra_hosts` 가 PC IP 로 바꾼다(render.sh 가 placement.conf 로 생성).
- 사용자는 Spring Cloud 의 서비스 등록·조회 구조(Eureka 서버 + 모든 앱이 클라이언트)를 원한다. 목적은 학습·Eum 프로젝트식 구성이며, 같은 서비스를 여러 개 띄워 부하를 나누는 것은 지금 목표가 아니다(PC 메모리 여유가 적다).
- 운영(Kubernetes)은 Service DNS 와 replica 로 같은 일을 한다. 둘을 겹쳐 쓰면 경로만 늘어난다.

## 결정
1. **`platform-services/eureka-server`**(Spring Cloud Netflix Eureka Server, 8761). 단일 노드 — 자신은 등록·조회하지 않는다. 자가 보존은 끄고 10초마다 만료 인스턴스를 지운다(개발 환경은 재배포·PC 재시작이 잦다). 설정은 다른 앱처럼 config-server 에서 받는다(`eureka-server.yml`, ADR-0029). LAN 은 pc4.
2. **등록·조회는 Basic 인증**(사용자 `pmart`, 비밀번호 `PMART_EUREKA_PASSWORD` — LAN 은 `deploy/lan/.secrets/eureka-password`, 로컬 `pmart-local`). 대시보드도 같다. `/actuator/health`·`info`·`prometheus` 만 인증 없이 연다. 인증이 없으면 LAN 의 누구나 가짜 인스턴스를 등록해 호출을 가로챌 수 있다.
3. **모든 앱이 Eureka 클라이언트**(`spring-cloud-starter-netflix-eureka-client`, convention `pmart.spring-service`·`pmart.spring-gateway`). 설정 저장소 `application.yml` 이 **기본 끄고**(`eureka.client.enabled: false` — local·prod·통합 시험) `application-lan.yml` 만 켠다. config-server 는 등록하지 않는다(다른 앱보다 먼저 떠야 하고 설정 저장소를 읽지 않는다).
4. **등록 주소 = 그 PC 의 LAN IP + 공개 포트**: 컨테이너 IP(172.28.0.x)는 다른 PC 에서 닿지 않는다. `eureka.instance.prefer-ip-address: true`, `ip-address: ${PMART_HOST_IP}`(render.sh 가 compose 환경변수로 넣는다). 공개 포트 = 컨테이너 포트 = `server.port` 라 포트는 따로 주지 않는다. `instance-id` 는 `<앱>:<IP>:<포트>` — 나중에 같은 서비스를 더 띄워도 겹치지 않는다. 갱신 10초·만료 30초, 조회 10초.
5. **게이트웨이**: lan 프로필의 업스트림을 `lb://<서비스 이름>`(api-gateway-lan.yml). 등록이 없으면 503. local·prod 는 지금처럼 `http://<호스트>:<포트>`.
6. **서비스 간 호출**: common-web 의 `LoadBalancedRestClientAutoConfiguration`(`eureka.client.enabled=true` 일 때만)이 Boot 자동 구성 `RestClient.Builder`(서비스가 주입받는 것)에 인터셉터를 붙인다. 주소의 **호스트를 서비스 이름**으로 보고 등록된 인스턴스의 IP·포트로 바꾼다 — 그래서 `*-base-url` 설정(`http://product-service:8101`)은 local·LAN·prod 가 같은 모양 그대로다. 등록된 인스턴스가 없으면 `UnknownHostException` → `Downstream` 이 `DEPENDENCY_UNAVAILABLE`(요청 미전달, 재시도 가능)로 해석한다(Spring Cloud 기본 인터셉터의 `IllegalStateException` 은 알 수 없는 500 이 된다). 외부 API(Toss·Kakao)·Elasticsearch 는 `RestClient.builder()` 로 따로 만들어 영향이 없다.
7. **운영(Kubernetes)은 쓰지 않는다**: prod 프로필은 `application.yml` 기본값(꺼짐)을 따르고, eureka-server 를 Helm 차트에 넣지 않는다.

## 대안
- **계속 두지 않음**(compose 이름 + `extra_hosts`): 이미 동작하지만 사용자 결정으로 제외.
- **실제 다중 인스턴스 부하 분산**: placement·포트 배정·watch·Prometheus 를 인스턴스 단위로 바꿔야 하고 메모리가 모자라다. 운영(k8s)으로 이어지지도 않는다. 결정 4·6 으로 나중에 가능하게만 둔다.
- **`@LoadBalanced` RestClient.Builder 빈**: Boot 자동 구성 빌더를 대체해 각 서비스 코드나 공통 커스터마이저 순서에 영향을 준다. 커스터마이저 하나로 같은 효과.
- **운영에도 Eureka**: k8s Service DNS 와 겹친다.

## 결과
- LAN 에서 Eureka 대시보드(`http://<pc4>:8761`, Basic 인증)로 어느 서비스가 어느 PC·포트에 떠 있는지 본다. 게이트웨이·서비스 간 호출은 등록부에서 주소를 찾는다.
- 인스턴스가 하나라 장애 조치 효과는 없다 — 그 서비스가 죽으면 호출은 여전히 실패한다(등록이 빠지면 연결 거부 대신 503·`DEPENDENCY_UNAVAILABLE`).
- 대가: (1) LAN 기동 직후·재배포 직후 등록·조회 주기(최대 약 10~20초) 동안 호출이 503 일 수 있다. (2) eureka-server 가 죽으면 클라이언트는 받아 둔 등록부로 계속 호출하지만 새 주소는 받지 못한다. (3) PC IP 가 바뀌면(ipcheck) render·재배포가 필요하다 — `extra_hosts` 와 같다. (4) Kafka·PostgreSQL·Redis·Zipkin·Elasticsearch·JWKS(리소스 서버 자체 클라이언트) 주소는 Eureka 를 거치지 않고 지금처럼 이름·`extra_hosts` 다.
- 로컬에서 시험하려면 eureka-server 를 local 프로필로 띄우고 서비스를 `--eureka.client.enabled=true --eureka.client.service-url.defaultZone=http://pmart:pmart-local@localhost:8761/eureka/` 로 띄운다.
