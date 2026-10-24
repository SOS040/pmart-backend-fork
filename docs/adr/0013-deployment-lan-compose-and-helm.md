# ADR-0013 배포 정의: LAN 공유 개발 환경은 배치 파일 + compose 생성, Kubernetes 는 공통 Helm 차트 하나

- 상태: 제안 (2026-10-24)
- 관련: `deploy/lan/`, `deploy/helm/`, `.claude/rules/ci-cd.md`, ADR-0012(이미지 저장소), `docs/worklog/2026-10-24.md`

## 배경
- 개발·통합 확인용으로 같은 LAN 의 Windows PC 3대(PC 2~4, Docker Desktop)를 쓰기로 했다. 클러스터는 아직 없다.
- 서비스 32개(업무 31 + 게이트웨이)를 PC 여러 대에 나눠야 하고, PC 수는 바뀔 수 있다.
- 운영(Kubernetes) 배포 정의도 없었다. CLAUDE.md 구조에 `deploy/helm/` 만 적혀 있었다.
- 이미지는 공개 저장소에 올라가므로(ADR-0012) 설정은 이미지 밖에서 환경변수로만 준다(`${PMART_*}`, local 프로파일 미사용).

## 결정
1. **LAN 배치 정본은 `deploy/lan/placement.conf` 한 파일**이다. 한 줄이 PC 하나(이름·IP·ssh 별칭·구성요소)다. `render.sh` 가 PC별 `compose.yml`·`.env`·`init-db.sql` 을 만든다. 서비스 포트·필요 인프라는 각 서비스 `application.yml` 에서 읽는다(정본 한 곳).
2. **주소 통일**: 호스트 포트 = 컨테이너 포트로 둔다. 다른 PC 의 구성요소는 `extra_hosts`(이름 → IP)로 가리킨다. 그래서 어디서든 `kafka:9092`, `auth-service:8501`, `postgres-<pc>:15432`, `<service>:<port>` 다. Kubernetes Service DNS 와 같은 이름이라 게이트웨이 업스트림 기본값을 그대로 쓴다.
3. **데이터**: PostgreSQL 은 DB 가 필요한 서비스가 있는 PC 마다 하나를 두고, 그 PC 서비스의 DB 만 만든다(서비스별 DB 분리 원칙 유지). Kafka·Debezium Connect·Redis·Zipkin 은 전체에서 하나씩이다. Connect 는 모든 PC 의 PostgreSQL 을 대상으로 한다(`connector-registrar` `DB_HOSTS`).
4. **Kubernetes 는 공통 차트 `deploy/helm/pmart-service` 하나**를 쓰고, 서비스별 차이는 `values/<service>.yaml`(생성 파일)로만 둔다. 이미지는 digest 필수(태그 배포를 렌더링 단계에서 거부)이고, 비루트·읽기 전용 루트 파일시스템·graceful shutdown 을 기본으로 한다.
5. **비밀정보**: LAN 은 `deploy/lan/.secrets/`(git 제외)에서 `.env` 로만 전달한다. Kubernetes 는 차트가 Secret 을 만들지 않고 이름 규칙(`pmart-<service>-db`, `pmart-auth-signing`)만 정한다. Secret Manager 연동 방식은 별도 결정이다.

## 대안
- **PC별 compose 를 손으로 관리**: PC 수·배치가 바뀔 때마다 여러 파일을 고쳐야 하고, 포트·DB 목록이 서비스 설정과 어긋난다.
- **Docker Swarm / k3s 로 LAN 클러스터 구성**: 오케스트레이터가 하나 더 생긴다. Windows Docker Desktop 에서 다중 노드 구성이 번거롭고, 요청 없이 새 플랫폼을 들이지 않는다(CLAUDE.md).
- **서비스마다 Helm 차트**: 32개 차트의 템플릿이 거의 같아 중복이 커진다. 차이는 포트·Secret·env 뿐이다.
- **Kafka 리스너를 내부/외부 둘로 분리**: 표준적이지만, 주소 통일(결정 2)로 리스너 하나(`kafka:9092`)면 같은 PC 와 다른 PC 모두 닿는다.

## 결과
- 배치 변경은 `placement.conf` 한 줄 수정 → `deploy/lan/lan.sh deploy all` 이다. 2026-10-24 현재 PC 1 은 빌드 전용이고, 원안의 PC 1 몫(게이트웨이·auth·플랫폼 지원)은 PC 4 에 있다(승인 대기).
- LAN 환경에서는 PostgreSQL 이 PC 마다 하나라서, 서비스 십여 개가 한 인스턴스를 나눠 쓴다. `max_connections=200`, 서비스별 Hikari 풀 5로 둔다.
- 정해야 할 것 `[팀 확인 필요]`: 클러스터·네임스페이스, Ingress(호스트·TLS·IngressClass), Kafka·Redis·Zipkin·PostgreSQL 을 클러스터 안에 둘지 관리형으로 쓸지, Secret Manager 연동, 리소스 수치, Jenkinsfile(CD 단계).
