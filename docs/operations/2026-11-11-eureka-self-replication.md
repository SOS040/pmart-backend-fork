# 2026-11-11 eureka-server 401 로그 폭주(자기 자신을 복제 대상으로 잡음)와 CPU 점검

- 범위: PC 4 의 `eureka-server`(ADR-0032, 서비스 등록·조회, 단일 노드)가 평소 한 코어의 절반 가까이를 쓰고, 앱을 한꺼번에 재시작할 때 2코어까지 오른 일. 2026-11-10 자원 점검 때 218% 를 처음 봤고 11-11 에 원인을 찾았다.
- 관련: ADR-0032(Eureka), pmart-config `eureka-server.yml`·`eureka-server-lan.yml`·`application-lan.yml`, 작업 기록 `docs/worklog/2026-11-11.md`.

## 요약

| 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|
| eureka 로그 10분에 1,375줄(1시간 약 4,000줄) — 거의 전부 `Batch update failure with HTTP status code 401; discarding N replication tasks`·`Discarding N tasks of TaskBatchingWorker-target_localhost-… due to permanent error`. 복제 요청(POST) 초당 1.1건 | eureka 가 `http://localhost:8761/eureka/` 를 **다른 eureka 서버(peer)** 로 잡아, 받은 등록·갱신·해제를 모두 그 주소로 복제했다. 복제 요청에 Basic 인증이 없어 전부 401 → 버림 | pmart-config `eureka-server-lan.yml` 에 `eureka.server.my-url: http://localhost:8761/eureka/` — 그 주소가 자기 자신임을 알린다(pmart-config `ab94164`) | **해결** — 2026-11-11 07:31 LAN 적용. 재시작 6분 뒤 401 0, POST 0, 로그 4분 24줄 |
| eureka-server CPU 평소 한 코어의 약 44%(6시간 중앙값, p95 53%), 앱 35~37개를 한꺼번에 재시작할 때 147~200% | **복제 때문이 아니었다**(고친 뒤에도 평소 43%). 평소 부하는 앱 36개가 10초마다 목록 조회(GET 초당 3.7)·갱신(PUT 초당 3.5)하는 정상 요청 — 일부러 줄인 주기(`registry-fetch-interval-seconds: 10`, `lease-renewal-interval-in-seconds: 10`, 재배포 뒤 새 주소를 빨리 받으려고). 급증은 일제 재시작 때 등록·조회가 몰린 것 | 바꾸지 않음(아래 §7) | 이상 아님 — 다음 일제 재시작 때 급증이 줄었는지 한 번 본다 |

## 1. 어떻게 찾았나

1. **언제 높은가** — Prometheus `process_cpu_usage{job="eureka-server"}`(전체 CPU 12개 기준이라 ×12 로 한 코어 기준으로 바꿈), 48시간, 10분 창 최대값:
   - 한 코어를 넘은 때는 두 번뿐: 11-10 12:13 **147%**, 11-11 00:13 **200%**.
   - 그 15분 동안 다시 시작한 앱 수(`changes(process_start_time_seconds[15m])`): 37개(PC 5 추가 배포), 35개(발급자 표 RIDER 제거 뒤 일제 재시작). → 급증은 일제 재시작 때의 등록 몰림.
   - 평소에도 중앙값 44% 로, 인스턴스 40여 개의 등록부치고 높다.
2. **무슨 요청인가** — `http_server_requests_seconds_count{job="eureka-server"}` 30분 비율: GET 3.6/s(목록 조회), PUT 3.6/s(갱신), **POST 1.1/s**. 앱은 시작할 때 한 번만 등록(POST)하므로 POST 가 계속 이어지는 것은 이상하다.
3. **로그** — 최근 10분 1,375줄을 메시지별로 세니 거의 전부가 복제 실패(401)와 "영구 오류로 작업 버림". 대상 이름이 `target_localhost`.
4. **기동 로그** — `PeerEurekaNodes`: `Adding new peer nodes [http://localhost:8761/eureka/]`, `Replica node URL: http://localhost:8761/eureka/`. 단일 노드인데 peer 가 하나 있다.

## 2. 왜 그렇게 동작했나

- eureka 서버는 `eureka.client.service-url.defaultZone` 의 주소들을 다른 서버(peer)로 보고, 등록·갱신·해제를 받을 때마다 peer 에게 복제한다. 다만 **자기 자신의 주소는 뺀다** — 주소의 호스트가 자기 인스턴스 이름과 같은지로 판단한다(`isThisMyUrl`, `eureka.server.my-url` 이 있으면 그 값과 비교).
- 2026-11-03 `28c94ac`(pmart-config): 공통 `application-lan.yml` 의 defaultZone 에 비밀번호가 들어 있어 `PeerEurekaNodes` 가 그 주소를 로그에 찍었다. 그래서 eureka-server 만 defaultZone 을 **인증 정보 없는** `http://localhost:8761/eureka/` 로 덮었다. 의도는 "자기 주소"였다.
- 그런데 공통 설정의 `eureka.instance.prefer-ip-address: true`·`ip-address: ${PMART_HOST_IP}` 때문에 eureka-server 의 인스턴스 이름은 PC IP(192.168.0.40)다. `localhost` ≠ `192.168.0.40` → **남으로 판단** → 모든 변경을 localhost(= 자기 자신)로 복제 → 인증 없음 401 → 다시 시도하지 않을 오류로 버림.
- 그래서 앱 갱신(10초마다 × 40여 개)마다 복제 요청과 실패 로그가 생겼다. 등록부 자체는 맞게 동작했다(복제만 헛돎) — 서비스 호출에는 영향이 없었다.
- 처음에는 CPU 도 이 복제 탓으로 봤으나 **틀렸다**: 고친 뒤 평소 CPU 가 그대로였다(§4).
- 참고(확인은 못 함): `28c94ac` 전에는 defaultZone 이 `http://pmart:<비밀번호>@eureka-server:8761/eureka/` 였고, 그 이름도 PC IP 와 달라 자기에게 **인증된** 복제를 했을 가능성이 크다(실패 로그 없이 일만 두 배).

## 3. 해결

pmart-config `eureka-server-lan.yml`:

```yaml
eureka:
  instance:
    hostname: eureka-server
  client:
    service-url:
      defaultZone: 'http://localhost:8761/eureka/'
  server:
    my-url: 'http://localhost:8761/eureka/'   # 위 주소 = 나 → peer 없음
```

- 인증 정보 없는 주소는 그대로라 비밀번호가 로그에 찍히지 않는다(11-03 의 목적 유지).
- 다른 방법(쓰지 않음): defaultZone 을 PC IP 주소로 — 공통 설정의 IP 를 eureka 설정에 한 번 더 적어야 해서 PC 가 바뀌면 같이 고쳐야 한다. `my-url` 은 defaultZone 과 같은 값만 지키면 된다.
- local 프로필은 인스턴스 이름이 `localhost` 라 이 문제가 없다.

## 4. 확인

- 시험 `platform-services/eureka-server/src/integrationTest/java/com/pmart/eureka/SingleNodePeersIT.java` — LAN 과 같은 조건(defaultZone localhost, 인스턴스 이름 eureka-server, prefer-ip-address, ip 192.168.0.40):
  - `my-url` 없음(대조군): peer **1개**(고치기 전 LAN 재현)
  - `my-url` 있음: peer **0개**
  - 기존 `EurekaServerIT` 3개와 함께 통과(`./gradlew :platform-services:eureka-server:integrationTest`).
- LAN 적용(2026-11-11 07:31 KST, pmart-config `ab94164` → config-server 가 같은 판을 주는지 확인 → `lan.sh restart pc4 eureka-server`):
  - 기동 5초 뒤 health 200, 약 1분 뒤 앱 36개·인스턴스 36개 모두 UP. 게이트웨이 경로(공개 홈 200, 운영자 로그인·계정 목록 200) 정상
  - 기동 로그에 `Adding new peer nodes` 없음. 401 로그는 재시작 전(22:30 UTC)까지만 있고 그 뒤 0
  - 재시작 6분 뒤(4분 창): POST **0**/초, GET 3.7·PUT 3.5/초, 로그 4분 24줄(정리 작업 기록만) — 전에는 4분 약 550줄
  - CPU 4분 평균 한 코어의 **43%** — 고치기 전 평소(44%)와 같다 → CPU 는 이 문제와 상관없음

## 5. LAN 적용 절차와 영향(2026-11-11 적용함)

1. pmart-config push(`eureka-server-lan.yml`).
2. PC 4 eureka-server 재시작(`deploy/lan/lan.sh restart pc4 eureka-server`). eureka 설정은 실행 중 갱신 대상이 아니다.
3. 재시작 동안 등록부가 비어 게이트웨이 `lb://` 조회가 잠깐(10~20초) 503 일 수 있다. 앱은 다음 갱신(10초)에서 404 를 받고 스스로 다시 등록한다.

## 6. CPU 는 왜 그대로 두나
- 앱 36개 × (10초마다 목록 조회 + 10초마다 갱신) ≈ 초당 7건. eureka 는 목록 응답을 캐시하지만 요청마다 Basic 인증·JSON 직렬화가 든다. 한 코어의 40% 남짓은 이 정도 요청에 무리한 값이 아니고, PC 4 는 12코어라 여유가 있다.
- 줄이려면 두 주기를 기본값 30초로 되돌리면 된다(요청 약 1/3). 대신 재배포 뒤 다른 앱이 새 주소를 받는 데 최대 30초 걸린다 — 10초로 줄인 이유(application-lan.yml 주석)와 맞바꿈이라 지금은 그대로 둔다.
- 일제 재시작 때의 급증(2코어)은 등록·조회가 몰린 일시 현상이다. 다음 일제 재시작 때 한 번 더 본다.

## 7. 재발 방지

- `eureka-server-lan.yml` 주석: defaultZone 을 바꾸면 `my-url` 도 같이 바꾼다. `SingleNodePeersIT` 가 지킨다.
- 로그에 같은 오류가 몇 분에 수백 줄씩 쌓이면 원인이 하나다 — 로그 ES(es-logs)에서 서비스별 WARN·ERROR 개수를 자원 점검 때 같이 본다.
