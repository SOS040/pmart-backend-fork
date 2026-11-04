# 2026-11-04 LAN 배포 오류와 해결 기록

- 범위: 이날 LAN 배포 두 번에서 생긴 오류와 해결이다.
  - 1차 18:05: Stream 이전(ADR-0035) 이미지 `944cae3`
  - 2차 19:35: 추적 A·C·B(`a1d514c`) + Bus 그룹 고정 + 백오피스
- 함께 기록한 것: 같은 날 시험·로컬 확인 중 생긴 환경 오류.
- 상세 진행은 `docs/worklog/2026-11-04.md`, 배포 절차는 `deploy/lan/README.md`.

## 요약

| # | 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|---|
| 1 | **2차 배포 뒤 앱 20개가 6분 넘게 뜨지 않음** | config-server 가 요청마다 GitHub git fetch(약 10초, 직렬) → 35개 동시 요청이 밀려 30초 시간 초과 → 재시작 → 다시 요청 | config-server `refresh-rate` 15초(`render.sh`) → config-server 만 재배포 | **해결**(7e37718) |
| 2 | `lan.sh deploy all` 이 "config-server 가 UP 이 아니다" 로 멈춤 | 1 과 같은 원인으로 config-server 기동·응답이 대기 시간보다 늦음 | 잠시 뒤 UP 확인 후 다시 실행 | 해결(1 로 재발 방지) |
| 3 | Jib push 가 35개 모두 `unauthorized` | `.secrets/registry.env` 의 Docker Hub 토큰 폐기·만료, Docker Desktop 도 로그아웃 | 사용자가 새 액세스 토큰(읽기·쓰기)을 `registry.env` 에 넣음 | 해결 |
| 4 | 1차 배포 중 pc3 접속 실패(`Could not resolve hostname d603-15`) | PC 1 → pc3 연결만 잠깐 끊김(이름 해석·ping 모두 실패, pc2 → pc3 는 정상) | pc4 를 먼저 배포하고, pc3 가 다시 닿은 뒤 `deploy pc3` | 해결(원인 미상) |
| 5 | pc3 앱 10개가 배포 직후 health DOWN | 재시작된 Eureka 에 아직 다시 등록되지 않음("Remote status from Eureka server") | 1~2분 뒤 자동으로 UP | 정상 동작 |
| 6 | 모든 앱이 10초마다 WARN `No servers available for service: eureka-server` | Eureka 클라이언트가 eureka-server 자신을 등록부에서 찾음(등록 대상 아님) → LoadBalancedInterceptor 가 원래 주소로 보냄(정상) | `RoundRobinLoadBalancer` 로그를 ERROR 로(`application-lan.yml`) | 반영(로그 확인 전) |
| 7 | Kafka 에 빈 `anonymous.*` 소비 그룹이 계속 쌓임(138개 + 재시작 소동 뒤 41개) | Bus 소비 그룹이 인스턴스마다 무작위 이름 → 재시작마다 새 그룹 | 빈 그룹 삭제 + Bus 그룹 이름 고정 `bus-<앱>-<PC>`(`application-lan.yml`·`render.sh`) | 해결 |

## 1. config-server 설정 요청 폭주 (가장 오래 걸린 문제)

### 증상
- 2차 `deploy all` 뒤 앱 컨테이너는 running 인데 health 에 응답하지 않는 앱이 20개였다. 6분이 지나도 그대로였다.
- 앱 로그:
  - `I/O error on GET request for "http://config-server:8888/<앱>/default": Read timed out`
  - 컨테이너 상태가 계속 "Up 2 minutes" 로 돌아감(재시작 반복)
- config-server 응답 시간: `curl -u pmart:… http://192.168.0.40:8888/cart-service/lan` 이 **30초 넘게 응답 없음**.
- config-server 로그: `Could not fetch remote for main remote: …code-and-pick/pmart-config.git` 가 섞여 나옴.

### 원인
- config-server 의 git 저장소 기본값은 `refresh-rate: 0` 이다. **설정 요청마다** GitHub 에서 `git fetch` 를 한다.
- fetch 는 SSH 로 한 번에 약 10초 걸렸고, 같은 저장소라 하나씩 처리됐다. 일부는 실패했다.
- 앱 35개가 동시에 재시작하며 설정을 요청하자 줄이 30초를 넘었다.
- 앱은 `fail-fast` 라 설정을 못 받으면 기동하지 않고, compose `restart` 로 다시 떠서 또 요청한다. 그래서 줄이 줄지 않는 악순환이 됐다.
- 1차 배포 때는 같은 구성으로도 넘어갔다. GitHub fetch 지연이 그때보다 길었던 것으로 보인다(측정값 없음).

### 해결
1. `deploy/lan/render.sh` 에서 config-server 컨테이너에 환경변수를 추가했다:
   ```yaml
   SPRING_CLOUD_CONFIG_SERVER_GIT_REFRESH_RATE: "15"   # GitHub fetch 는 15초에 한 번
   ```
2. `lan.sh render` → `lan.sh deploy pc4 config-server`(config-server 만 다시 띄움).
3. 응답 시간 30초 이상 → 4.5초. 그 뒤 몇 분 안에 앱 34개가 모두 UP.
4. 이미지를 다시 빌드하지 않으려고 이미지 설정(`application.yml`)이 아니라 배포 환경변수로 넣었다.

### 대가·주의
- 설정 저장소에 push 한 뒤 **최대 15초 동안 config-server 가 이전 설정을 줄 수 있다.**
- 웹훅 → Bus 갱신(ADR-0033)이 그 안에 앱을 깨우면 앱이 이전 값을 다시 받을 수 있다. 그러면 15초 뒤 다시 push 하거나 `lan.sh restart <pc> <앱>` 한다.
- 이 상태에서 Bus 갱신이 제대로 되는지는 **아직 다시 확인하지 않았다.**

### 확인 방법
```bash
PW=$(cat deploy/lan/.secrets/config-password)
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -u "pmart:$PW" http://192.168.0.40:8888/cart-service/lan
deploy/lan/lan.sh logs pc4 config-server 200 | grep -c "Could not fetch remote"
```

## 2. `lan.sh deploy all` 이 config-server 대기에서 멈춤
- `deploy all` 은 config-server 를 먼저 올리고 UP 을 확인한 뒤 나머지를 올린다. 첫 실행은 그 대기 시간을 넘겨 `config-server 가 UP 이 아니다(pc4)` 로 끝났다.
- 바로 뒤 health 를 보니 UP 이었다. `deploy all` 을 다시 실행하면 config-server 는 그대로 두고 나머지를 올린다.
- 1 의 조치 뒤로 같은 원인은 줄었다. 대기 시간을 늘릴지는 후속 과제다.

## 3. Docker Hub push 인증 실패
- 증상: `Build image failed, perhaps you should make sure your credentials for 'registry-1.docker.io/heeji4445/pmart-…' are set up correctly`(35개 모두).
- 원인:
  - `deploy/lan/.secrets/registry.env` 의 액세스 토큰이 폐기·만료됐다.
  - 환경변수 없이 시도하니 Docker Desktop 자격 증명도 없었다(`docker info` 에 사용자 없음).
- 해결: 사용자가 Docker Hub 에서 새 액세스 토큰(읽기·쓰기)을 발급해 `registry.env` 에 넣었다. 비밀값이라 대화창 `!` 명령이 아니라 별도 편집기로 넣었다. 서비스 하나로 push 를 확인한 뒤 나머지 34개를 push 했다.
- 남은 일: 예전 토큰은 Docker Hub 에서 폐기한다(사용자).

## 4. 배포 중 pc3 접속 실패
- 증상: `ssh: Could not resolve hostname d603-15: Name or service not known` → `pc3(pmart-pc3) 접속 실패 — 배포 중단`. PC 1 에서 `ping 192.168.0.222` 도 실패했다.
- 확인한 것:
  - pc2 에서는 pc3 이름이 풀렸고 ping 도 됐다.
  - pc3 서비스들의 Kafka 소비자도 붙어 있었다.
  - 즉 pc3 는 켜져 있었고, PC 1 → pc3 만 잠깐 끊겼다. 몇 분 뒤 PC 1 에서도 다시 닿았다.
- 해결: 접속되는 pc4 를 먼저 배포하고, pc3 가 다시 닿은 뒤 `lan.sh deploy pc3`. 섞인 상태(일부만 새 이미지)는 사건 형식이 같아 문제없었다.
- 원인은 확인하지 못했다(이전 DNS 불안정 기록과 같은 종류로 보임).

## 5. 배포 직후 pc3 앱 health DOWN
- 증상: `{"description":"Remote status from Eureka server","status":"DOWN"}`.
- 원인: pc4 의 eureka-server 도 재시작돼 등록부가 비었다. 앱이 다시 등록하기 전까지 Eureka health 가 DOWN 이다.
- 해결: 등록 주기(약 30초) 뒤 1~2분 안에 모두 UP. 조치 불필요.

## 6. Eureka "No servers available" 경고 잡음
- 원인: 공통 RestClient 의 `LoadBalancedInterceptor` 가 요청 호스트를 등록부에서 찾는다(ADR-0032). eureka-server 는 등록 대상이 아니라 매번 "없음" WARN 을 남기고 원래 주소로 보낸다. 동작은 정상이다.
- 해결: `application-lan.yml` 에 `org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer: ERROR`.
- 잃는 정보: 등록이 없는 다른 서비스 조회 경고도 함께 사라진다. 실제 연결 실패는 `Downstream`(DEPENDENCY_UNAVAILABLE)으로 남는다.
- 반영 뒤 로그에서 경고가 사라졌는지는 아직 보지 않았다.

## 7. Bus 빈 소비 그룹 누적
- 원인: Spring Cloud Bus 소비자는 인스턴스마다 무작위 `anonymous.*` 그룹을 쓴다(모든 인스턴스가 알림을 받게). 재시작마다 새 그룹이 생기고 옛 그룹은 비어 남는다. Kafka 가 7일 뒤 지우지만 목록을 어지럽힌다.
- 해결:
  1. 빈 그룹 삭제: 옛 `search-service` 1개 + 빈 `anonymous.*` 138개. 2차 배포 소동 뒤 41개 더 삭제. 소비자가 붙은 그룹은 건드리지 않았다.
  2. 이름 고정: `application-lan.yml` 에 `spring.cloud.stream.bindings.springCloudBusInput.group: bus-${spring.application.name}-${PMART_BUS_INSTANCE:${HOSTNAME:local}}`, `start-offset: latest`. `render.sh` 가 앱마다 `PMART_BUS_INSTANCE: <pc>` 를 넣는다.
  3. 결과: `bus-<앱>-<PC>` 34개 Stable. config-server 는 설정 저장소를 읽지 않아 anonymous 1개가 남는다.
- 주의: 같은 이름을 두 인스턴스가 쓰면 알림이 한쪽에만 간다. Kubernetes 는 `HOSTNAME`(파드 이름)이 달라서 괜찮다.

## 같은 날 시험·로컬 확인 중 환경 오류 (코드 문제 아님)

| 증상 | 원인 | 결과 |
|---|---|---|
| 전체 `./gradlew check` 에서 mfc-capacity `ServiceBootIT` 실패(Kafka 시험 컨테이너 종료 코드 126) | 로컬 compose·다른 시험 컨테이너와 동시 실행(추정) | 단독 재실행 6/6 통과 |
| 전체 `./gradlew check` 에서 rider-service `ServiceBootIT` 실패(`Could not find a valid Docker environment`) | 그 순간 Testcontainers 가 Docker 를 못 찾음 | 단독 재실행 통과 |
| 로컬 공용 postgres 컨테이너가 첫 초기화 중 멈춤(`pg_ctl: server does not shut down`) | 전체 check 와 동시 실행으로 자원 부족(추정) | `docker compose up -d postgres` 다시 → 복구 |

## 후속 과제
- [ ] `refresh-rate` 를 둔 상태에서 웹훅 → Bus 갱신을 다시 확인(15초 지연 영향).
- [ ] `lan.sh deploy` 를 PC·앱 단위로 나눠 순차 기동할지, config-server UP 대기 시간을 늘릴지 검토. 35개 동시 기동은 config-server·Eureka 부하가 크다.
- [ ] 6 의 WARN 이 LAN 로그에서 사라졌는지 확인.
- [ ] 예전 Docker Hub 토큰 폐기(사용자).
- [ ] PC 1 ↔ pc3 연결이 끊기는 원인(이름 해석·ARP) — 다시 생기면 그때 시각과 `ipconfig`·`arp -a` 를 남긴다.
