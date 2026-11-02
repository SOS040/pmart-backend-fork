# ADR-0029 설정의 정본은 설정 저장소(pmart-config) — 서비스 yml 은 부트스트랩만, 환경은 프로필로

- 상태: 승인 · 진행 중 (2026-11-02, 사용자 결정 "특정 서비스는 이름 같은 정보만 기입하고 나머지는 Configuration 서버에서 받아와야 한다", "Spring Cloud Config 의 일반적인 방식으로") — 시범: payment-service
- 대체: ADR-0027 의 결정 2(저장소 내용을 render.sh 가 생성)·3(키는 `${PMART_*}` 자리표시자 이름)·5(클라이언트 기본 꺼짐). 결정 1(config-server)·4({cipher}, 서버 복호화)·6(부팅 때만 읽음)·7(서버 비밀 configtree)은 유지한다.
- 관련: ADR-0027, ADR-0028, `deploy/lan/README.md`

## 배경
- ADR-0027 은 config-server 를 "LAN 에서만 켜는 보조 수단"으로 두었다: 서비스 yml 에 설정이 그대로 있고, 저장소는 render.sh 가 만든 `PMART_*` 값만 채웠다. 저장소가 정본이 아니고(머리에 "고치지 말 것"), 키 이름만 봐서는 무슨 설정인지 알 수 없었다.
- 운영도 config-server 를 쓴다(사용자: AWS VPC 의 EC2, 운영 이미지는 GraalVM Native).

## 결정
1. **서비스 `application.yml` = 부트스트랩만**: `spring.application.name`, `spring.config.import: optional:configserver:`, `spring.cloud.config.fail-fast: true`, 사용자 `pmart`, 로컬 기본 비밀번호 `pmart-local`. 주소는 `spring.cloud.config.uri`(기본 `http://localhost:8888`, LAN·운영은 환경변수 `SPRING_CLOUD_CONFIG_URI`·`_PASSWORD`).
2. **설정 저장소 최상위가 정본**(사람이 직접 고친다), Spring 속성 이름 그대로:
   - `application.yml` 모든 서비스 공통 · `application-<프로필>.yml` 환경 공통
   - `<앱>.yml` 서비스 고유(포트·permit-paths·토픽 도메인·타임아웃 등) · `<앱>-<프로필>.yml` 환경별(DB·키)
3. **환경은 프로필**(`SPRING_PROFILES_ACTIVE`): `local`(개발자 PC) · `lan` · `prod`(아직 없음). 비밀값은 `{cipher}`(ADR-0027 결정 4, 암호화는 `configCipher` 태스크).
4. **로컬**: config-server 를 `local` 프로필로 띄운다 → native(파일) 저장소로 pmart-config clone 을 읽는다(git 저장소로 읽으면 force-pull 이 작업 중인 clone 을 origin/main 으로 되돌린다 — 2026-11-02 재현). 서비스는 `--spring.profiles.active=local`.
5. **통합 시험**은 config-server 없이 돈다(`spring.cloud.config.enabled=false`). 시험에 필요한 공통·서비스 고유 설정은 `src/integrationTest/resources/config/application.yml` 에 **사본**으로 둔다(DB·Kafka 는 Testcontainers). 원본을 바꾸면 사본도 바꾼다.
6. **LAN 배포 스크립트**: 옮긴 앱(저장소에 `<앱>-lan.yml` 이 있는 앱)은 `SPRING_PROFILES_ACTIVE=lan` 을 받고 `lan/<앱>.yml` 을 만들지 않는다. 포트는 저장소 `<앱>.yml`, DB 필요 여부는 `db/migration` 폴더로 판단한다. `lan.sh` 의 사본 동기화는 사람이 고친 내용을 버리지 않는다(앞서 있으면 유지, 갈라지면 멈춤), 배포 전 미커밋·미push 가 있으면 멈춘다.
7. **전환**: 서비스를 하나씩 옮긴다(시범 payment-service). 옮기는 동안 config-server 는 최상위와 `lan/`(ADR-0027 방식, 아직 옮기지 않은 앱)을 함께 읽는다. 다 옮기면 `lan/`·render 의 설정 생성·`search-paths` 를 지운다.

## 대안
- **ADR-0027 유지**(서비스 yml 에 설정, 저장소는 생성물): 사용자 결정으로 제외.
- **환경을 폴더로(`lan/`·`prod/`) 나누기**: 서버마다 search-paths 를 달리해야 하고, Spring Cloud Config 의 표준 프로필 방식과 다르다.

## 결과
- 서비스 yml 이 몇 줄로 줄고, 설정을 GitHub 한 곳에서 보고 고친다(변경 이력이 저장소에 남는다).
- 대가: (1) 로컬 개발에 config-server 가 필요하다. (2) 통합 시험 설정이 저장소와 이중이다 — 어긋나면 시험은 통과하는데 실제와 다를 수 있다. (3) 배치(어느 PC 의 DB 인지)를 `placement.conf` 와 저장소 두 곳에 맞춘다. (4) 비밀값을 바꿀 때 사람이 암호화해 넣는다.
- 코드와 함께 바뀌는 설정(permit-paths 등)이 저장소에 있으므로, 이미지 롤백 때 설정과 어긋날 수 있다 — 바꿀 때 저장소 변경을 같은 작업에서 한다.
- GraalVM Native(운영): 실행 중 받는 값(주소·키)은 문제없지만, 빈 생성 조건을 바꾸는 설정은 빌드 때 정해진다 — Native 를 정할 때 다시 본다.
