# LAN 공유 개발 환경 (deploy/lan)

사내 LAN PC 여러 대에 PMart 서비스를 나눠 띄운다. Kubernetes(Helm) 이전 단계의 통합 확인용이며 운영 환경이 아니다.
PC 접속·원격 docker 설정은 `docs/worklog/2026-10-24.md` 참조.

| 파일 | 역할 |
|---|---|
| `placement.conf` | 배치 정보 — PC 이름·IP·ssh 별칭·구성요소. PC 수는 가변 |
| `cors-origins.conf` | 게이트웨이 웹 CORS 허용 출처(한 줄에 하나). render 가 api-gateway 의 `PMART_GATEWAY_CORS_ALLOWEDORIGINS` 로 넣는다 |
| `google-client-ids.conf` | 고객 Google 로그인 허용 클라이언트 ID(공개 식별자, ADR-0019). render 가 auth-service 의 `PMART_AUTH_CUSTOMER_GOOGLE_CLIENTIDS` 로 넣는다 |
| `render.sh` | 배치 → `build/<pc>/compose.yml`, `.env`, `init-db.sql` 생성 |
| `init-secrets.sh` | `.secrets/`(git 제외)에 DB 비밀번호·auth 서명키·S3 자격 증명·첫 운영자 관리자(아이디 `operator-admin-login`, 비밀번호 원문 `operator-admin-password`, bcrypt 해시 `operator-admin-hash`) 생성. 있으면 유지. **재생성하면 LAN 과 값이 달라지므로** 잃어버렸으면 PC 1 백업(`%USERPROFILE%\.pmart-lan-secrets-backup\`, 저장소 밖)에서 되돌린다 |
| `lan.sh` | 실행기: `render` / `deploy` / `status` / `logs` / `restart` / `down` |
| `watch.sh` | 감시: 각 PC 구성요소를 LAN 에서 실제 프로토콜로 점검, 연속 실패 시 그 PC 에서 재시작. PC IP 변경 감지 |
| `ipcheck.sh` | PC 의 실제 LAN IP 와 `placement.conf` IP 비교(`watch.sh` 가 사용, 단독 실행 가능) |
| `watch-install.ps1` | PC 1 작업 스케줄러에 `watch.sh` 5분 간격 등록(로그온 중 실행, `-S4U` 는 관리자 필요, `-Remove` 로 해제) |

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
./gradlew :services:<name>:jib -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha> --no-configuration-cache   # jib 은 구성 캐시와 호환되지 않음(ci-cd.md)
# 2) 파일 생성·배포
deploy/lan/lan.sh render
deploy/lan/lan.sh deploy all            # 또는 deploy pc3 delivery-tracking-service
deploy/lan/lan.sh status all
deploy/lan/lan.sh logs pc2 order-service 200
# 3) 시험 데이터(승인 API 경로, 다시 돌려도 안전) — deploy/lan/seed/README.md
java deploy/lan/seed/Seed.java
```
이미지는 태그가 아니라 **digest** 로 지정한다(ci-cd.md). push 하지 않은 서비스가 배치에 있으면 render 가 중단된다.

`jib` 이 Docker Hub 매니페스트 PUT 에서 401 로 실패하면(2026-10-30 발생, 로그인은 정상) 같은 Jib 로 데몬에 만들고 Docker CLI 로 올린 뒤 그 digest 를 적는다:
```bash
./gradlew :services:<name>:jibDockerBuild -PcontainerRegistry=docker.io/heeji4445 -PimageTag=<sha> -PgitRevision=<sha> --no-configuration-cache
docker push heeji4445/pmart-<name>:<sha>                  # 출력 끝의 digest: sha256:… 를
printf %s 'sha256:…' > services/<name>/build/jib-image.digest
```

## 장애 대비 (2026-10-27, `docs/worklog/2026-10-27.md`)
- **컨테이너 고정 IP**: compose 네트워크 `172.28.0.0/24`, 구성요소는 배치 순서대로 `.10~`, 자동 배정은 `.192/26`.
  Docker Desktop VM 이 리셋되면 새 VM 이 이전 포트 전달 규칙(옛 컨테이너 IP)을 복원하는데, IP 가 바뀌면 LAN 포트가 엉뚱한 컨테이너로 간다.
  네트워크 설정을 바꾼 뒤에는 `lan.sh down <pc>` → `lan.sh deploy <pc>` (볼륨은 남는다).
- **Connect 로그**: 파일 appender 를 끄고 stdout 만(json-file 20MB×3 제한). `org.apache.kafka.clients.admin.internals` 는 WARN.
- **감시**: `deploy/lan/watch.sh [--dry-run]`. 앱 health, PostgreSQL SSLRequest, Kafka ApiVersions, Redis PING, Zipkin, Connect 커넥터·작업 RUNNING 을
  PC 1 에서 점검한다. 같은 구성요소가 2회 연속 실패하면 `lan.sh restart`(PC 당 10분 쿨다운), 전부 실패면 'PMart Docker Desktop Start' 예약 작업부터.
  상태·로그는 `~/.pmart-lan-watch/` (`watch.log`, `last-<pc>`). Kafka 가 재시작되면 Connect 작업이 UNASSIGNED 로 남는데(재배정 안 됨), 이것도 감시가 Connect 재시작으로 복구한다.

## IP 변경 시 복구 (2026-10-28)
PC 2·3·4 는 DHCP 로 IP 를 받는다(공유기는 우리가 설정할 수 없음, 지금까지는 같은 IP 가 나왔다). 반면 `placement.conf` 의 IP 는
각 PC compose 의 `extra_hosts`·Kafka 광고 주소·Connect 의 DB 주소에 그대로 들어가므로, 한 PC 의 IP 가 바뀌면 **모든 PC** 를 다시 배포해야 한다.

- **SSH 는 컴퓨터 이름으로 접속**한다(PC 1 `~/.ssh/config`: `HostName DS603-12`/`D603-15`/`D603-16`, `AddressFamily inet`).
  호스트 키는 `HostKeyAlias`(`pmart-pc2` 등) 이름으로 `known_hosts` 에 있어 IP 가 바뀌어도 같은 키로 검증한다(검증은 끄지 않는다).
  그래서 IP 가 바뀌어도 `lan.sh`·`run-ps.sh` 는 그대로 접속된다.
- **감지**: `watch.sh` 가 5분마다 이름으로 IP 를 조회한다(`ipcheck.sh`). 판정이 바뀔 때만 `~/.pmart-lan-watch/watch.log` 에 남고, 현재 판정은 `ip-<pc>` 파일.

  | 판정 | 뜻 | 감시 동작 |
  |---|---|---|
  | `OK` | 조회된 LAN 주소 중 배치 IP 가 있음 | 평소대로 |
  | `LOOKUP_FAIL` / `NO_LAN_ADDR` | 이름 조회 실패 / LAN 서브넷 주소 없음(IPv6 링크 로컬·WSL 가상 어댑터만) — **IP 변경 아님** | 평소대로 |
  | `UNVERIFIED <ip>` | 이름이 다른 LAN 주소를 가리키나 그 주소의 SSH 호스트 키가 이 PC 와 다르거나 응답 없음 | 평소대로, 사람이 확인 |
  | `AMBIGUOUS <ip들>` | 호스트 키가 일치하는 LAN 주소가 여럿 | 평소대로, 사람이 확인 |
  | `CHANGED <새 ip>` | 다른 LAN 주소에서 **이 PC 의 호스트 키가 확인됨** = IP 변경 확정 | **모든 재시작 보류**(재시작으로는 복구 안 됨) |
- 수동 확인: `deploy/lan/ipcheck.sh pmart-pc4 192.168.0.40` → 판정 한 줄.

### 절차 (`CHANGED` 가 떴을 때)
1. 확인: `cat ~/.pmart-lan-watch/ip-*`, `deploy/lan/ipcheck.sh <별칭> <배치 IP>`, `ssh <별칭> hostname`(이름으로 접속되는지).
2. `deploy/lan/placement.conf` 에서 그 PC 의 IP 만 새 IP 로 고친다(감시는 이 파일을 고치지 않는다).
3. `deploy/lan/lan.sh render` → `grep -rn <옛 IP> deploy/lan/build/` 결과가 비어 있는지 확인.
4. `deploy/lan/lan.sh deploy all` — 바뀐 PC 뿐 아니라 **전체**(다른 PC 의 `extra_hosts` 도 바뀜). 컨테이너는 다시 만들어지지만 볼륨(DB·Kafka 데이터)은 남는다.
5. 확인: `deploy/lan/watch.sh --dry-run` 이 PC 마다 OK, `ip-<pc>` 가 `OK`, Connect 커넥터 전부 RUNNING(`curl http://<pc3 IP>:8083/connectors?expand=status`),
   게이트웨이 `http://<pc4 IP>:8080` 응답.
6. `placement.conf` 변경을 커밋하고 worklog 에 남긴다.

## 주의
- 원격 작업 폴더 `%USERPROFILE%\pmart-lan`, compose 프로젝트 `pmart-lan`. 같은 PC 의 다른 프로젝트 컨테이너는 건드리지 않는다.
- `down` 은 볼륨을 남긴다. DB 를 새로 만들려면 원격에서 `docker volume rm pmart-lan_pgdata` (서비스 DB 가 늘었을 때 `init-db.sql` 은 새 볼륨에서만 실행됨).
- 서명키를 다시 만들면 발급된 토큰이 모두 무효가 된다.
- 비밀정보는 `.secrets/`·`build/` 에만 있다(둘 다 git 제외). 원격 PC 에는 `.env` 로 복사된다.
