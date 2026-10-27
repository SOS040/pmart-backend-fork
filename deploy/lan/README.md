# LAN 공유 개발 환경 (deploy/lan)

사내 LAN PC 여러 대에 PMart 서비스를 나눠 띄운다. Kubernetes(Helm) 이전 단계의 통합 확인용이며 운영 환경이 아니다.
PC 접속·원격 docker 설정은 `docs/worklog/2026-10-24.md` 참조.

| 파일 | 역할 |
|---|---|
| `placement.conf` | 배치 정보 — PC 이름·IP·ssh 별칭·구성요소. PC 수는 가변 |
| `render.sh` | 배치 → `build/<pc>/compose.yml`, `.env`, `init-db.sql` 생성 |
| `init-secrets.sh` | `.secrets/`(git 제외)에 DB 비밀번호·auth 서명키 생성. 있으면 유지 |
| `lan.sh` | 실행기: `render` / `deploy` / `status` / `logs` / `restart` / `down` |
| `watch.sh` | 감시: 각 PC 구성요소를 LAN 에서 실제 프로토콜로 점검, 연속 실패 시 그 PC 에서 재시작 |
| `watch-install.ps1` | PC 1 작업 스케줄러에 `watch.sh` 5분 간격 등록(`-Remove` 로 해제) |

## 주소 규칙
- 서비스 포트는 서비스마다 고유(gateway 8080, 8101~8507)라 **호스트 포트 = 컨테이너 포트**.
- 다른 PC 의 구성요소는 `extra_hosts` 로 가리킨다. 그래서 어느 PC 에서든 주소가 같다:
  `kafka:9092`, `auth-service:8501`, `redis:6379`, `zipkin:9411`, `postgres-<pc>:15432`, `<service>:<port>`.
  게이트웨이 업스트림 기본값(`http://<service>:<port>`)도 그대로 맞는다.
- PostgreSQL 은 DB 가 필요한 서비스가 있는 PC 마다 하나(컨테이너 안에서도 15432). 그 PC 서비스의 DB 만 만든다.
- Debezium Connect 는 한 곳. `connector-registrar` 가 모든 PC 의 PostgreSQL(`DB_HOSTS`)을 훑어 커넥터를 만든다.
- 설정은 각 서비스 `application.yml` 의 `${PMART_...}` 환경변수로만 준다. `local` 프로파일은 쓰지 않는다(이미지에 로컬 고정값이 들어 있음).

## 사용
```bash
# 1) 이미지 push (서비스마다; digest 가 build/jib-image.digest 에 남는다)
./gradlew :services:<name>:jib -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha>
# 2) 파일 생성·배포
deploy/lan/lan.sh render
deploy/lan/lan.sh deploy all            # 또는 deploy pc3 delivery-tracking-service
deploy/lan/lan.sh status all
deploy/lan/lan.sh logs pc2 order-service 200
```
이미지는 태그가 아니라 **digest** 로 지정한다(ci-cd.md). push 하지 않은 서비스가 배치에 있으면 render 가 중단된다.

## 장애 대비 (2026-10-27, `docs/worklog/2026-10-27.md`)
- **컨테이너 고정 IP**: compose 네트워크 `172.28.0.0/24`, 구성요소는 배치 순서대로 `.10~`, 자동 배정은 `.192/26`.
  Docker Desktop VM 이 리셋되면 새 VM 이 이전 포트 전달 규칙(옛 컨테이너 IP)을 복원하는데, IP 가 바뀌면 LAN 포트가 엉뚱한 컨테이너로 간다.
  네트워크 설정을 바꾼 뒤에는 `lan.sh down <pc>` → `lan.sh deploy <pc>` (볼륨은 남는다).
- **Connect 로그**: 파일 appender 를 끄고 stdout 만(json-file 20MB×3 제한). `org.apache.kafka.clients.admin.internals` 는 WARN.
- **감시**: `deploy/lan/watch.sh [--dry-run]`. 앱 health, PostgreSQL SSLRequest, Kafka ApiVersions, Redis PING, Zipkin, Connect 커넥터·작업 RUNNING 을
  PC 1 에서 점검한다. 같은 구성요소가 2회 연속 실패하면 `lan.sh restart`(PC 당 10분 쿨다운), 전부 실패면 'PMart Docker Desktop Start' 예약 작업부터.
  상태·로그는 `~/.pmart-lan-watch/` (`watch.log`, `last-<pc>`).

## 주의
- 원격 작업 폴더 `%USERPROFILE%\pmart-lan`, compose 프로젝트 `pmart-lan`. 같은 PC 의 다른 프로젝트 컨테이너는 건드리지 않는다.
- `down` 은 볼륨을 남긴다. DB 를 새로 만들려면 원격에서 `docker volume rm pmart-lan_pgdata` (서비스 DB 가 늘었을 때 `init-db.sql` 은 새 볼륨에서만 실행됨).
- 서명키를 다시 만들면 발급된 토큰이 모두 무효가 된다.
- 비밀정보는 `.secrets/`·`build/` 에만 있다(둘 다 git 제외). 원격 PC 에는 `.env` 로 복사된다.
