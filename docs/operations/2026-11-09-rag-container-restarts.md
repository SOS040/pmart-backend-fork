# 2026-11-09 RAG 컨테이너(rag-pgvector·ollama) 반복 재시작 — 원인과 해결

- 범위: pc2 에 새로 둔 RAG 전용 컨테이너 `rag-pgvector`·`ollama`(ADR-0051)가 약 10분마다 재시작된 일과, 같은 날 RAG 배포 중 겪은 관련 문제.
- 상세 진행은 `docs/rag/worklog.md`(2026-11-09), 감시 구조는 `docs/operations/lan-monitoring-and-backup.md`, 배포 절차는 `deploy/lan/README.md`.

## 요약

| # | 증상 | 원인 | 해결 | 상태 |
|---|---|---|---|---|
| 1 | **rag-pgvector·ollama 가 약 10분마다 재시작** — 답변 평가 중 질문이 503(모델 응답 없음)·500(DB 연결 끊김)으로 실패, 재시작 뒤 첫 질문은 모델을 다시 올리느라 늦음 | PC 1 의 LAN 감시 `deploy/lan/watch.sh`(작업 스케줄러 5분마다)가 목록에 없는 구성요소를 Spring 앱으로 보고 LAN 에서 `/actuator/health` 를 불렀다. 두 컨테이너는 Spring 앱이 아니고 **LAN 포트도 일부러 열지 않아** 늘 "응답 없음" → 연속 2회 실패 → 재시작(쿨다운 10분) | `watch.sh check()` 에 두 구성요소를 추가 — 그 PC 에 SSH 로 들어가 컨테이너 안에서 `pg_isready`(rag-pgvector)·`ollama list`(ollama). 검색 Logstash 와 같은 방식(`eabbb76`) | 해결 — 고친 뒤 14분(감시 2회) 재시작 없음 확인 |
| 2 | 서비스 하나만 다시 배포해도(`lan.sh deploy pc2 ai-assistant-service`) ollama·rag-pgvector 가 다시 시작돼 모델이 내려감 | `docker compose up -d <서비스>` 가 의존 컨테이너(`depends_on`)도 맞춘다 | 서비스를 지정한 배포는 `up -d --no-deps <서비스>`(`20617aa`) | 해결 |
| 3 | 모델 미리 올리기(`lan.sh rag-warm`)가 10분 넘게 멈춤 → 색인이 시작되지 않음 | `ollama run` 은 표준입력이 터미널이 아니면 입력 끝을 기다린다. 원격 실행에서는 끝이 오지 않음 | `ollama run … </dev/null`(`20617aa`). 지금 약 10초 | 해결 |

## 1. 반복 재시작 — 어떻게 찾았나

처음에는 "앞서 멈춰서 중지한 작업의 남은 셸이 고쳐지던 lan.sh 를 읽어 실행했다"고 **짐작했으나 틀렸다.** 아래 증거로 원인을 확정했다.

1. **재생성이 아니라 재시작**: 두 컨테이너의 생성 시각은 그대로(07:10·07:24 UTC)인데 시작 시각만 09:43:24, 다시 09:53:24 로 바뀜.
2. **정상 종료 명령**: rag-pgvector 로그에 `terminating connection due to administrator command`, `checkpoint starting: shutdown immediate` — 죽은 게 아니라 멈추라는 명령을 받았다.
3. **명령은 PC 1 에서**: pc2 sshd 로그(OpenSSH/Operational)에 09:43:23~25 PC 1(192.168.0.32) 접속. 그 시각 작업자 쪽은 SSH 를 쓰지 않는 평가 스크립트만 돌고 있었다.
4. **감시 기록과 일치**: PC 1 `~/.pmart-lan-watch/restart-pc2`(마지막 재시작) 18:53:24 KST = 09:53:24 UTC, `watch.log` 에 `restart rag-pgvector ollama …` 실행 출력.
5. 참고: Docker 이벤트 기록은 최근 256건만 메모리에 남는다(헬스체크 exec 로 약 2분치) — 지난 시각의 이벤트로는 확인할 수 없었다.

## 2. 왜 그렇게 동작했나

- 감시는 각 구성요소를 "다른 PC 에서 보듯이" 실제 프로토콜로 점검하고(2026-10-27 LAN 47시간 불통 사고가 계기), 연속 `FAIL_LIMIT`(2)회 실패하면 `lan.sh restart` 한다. PC 마다 `COOLDOWN`(600초) 안에는 다시 하지 않는다.
- 점검 방법은 구성요소 이름별로 정해져 있고(postgres 15432 SSLRequest, kafka 9092 ApiVersions …), **목록에 없으면 Spring 앱으로 보고** `http://<PC IP>:<포트>/actuator/health` 를 부른다.
- rag-pgvector·ollama 는 같은 PC 의 RAG 서비스만 쓰도록 **LAN 포트를 열지 않았다**(Ollama 는 인증이 없어 열면 LAN 의 누구나 쓸 수 있다). 포트도 actuator 도 없으니 늘 실패 → 5분 × 2 = 10분마다 재시작.
- Ollama 가 다시 시작되면 GPU 에 올려 둔 대화 모델(qwen3:4b)이 내려간다. 첫 적재는 26~30초라 서비스의 모델 호출 제한(25초)을 넘어 503 이 났다. rag-pgvector 재시작은 열린 DB 연결을 끊어 그때 처리 중인 요청이 500 이 됐다.
- 근본 원인: 두 컨테이너를 배포할 때(`2d018b7`) 감시 점검 목록에 넣지 않은 누락.

## 3. 해결과 재발 방지

- `deploy/lan/watch.sh` `check()`:
  ```bash
  rag-pgvector|ollama)
    ssh … "<그 PC>" "docker exec pmart-lan-$c-1 $([ "$c" = ollama ] && echo 'ollama list' || echo 'pg_isready -q -U pmart')"
  ```
  직접 실행해 둘 다 성공(종료 코드 0) 확인. 고친 뒤 09:55~10:07 UTC 동안 두 컨테이너 시작 시각 변화 없음.
- `deploy/lan/placement.conf` 머리 주석(`cba170a`): **LAN 포트를 열지 않는 구성요소를 새로 두면 `watch.sh` 의 `check()` 에 점검 방법을 같이 넣는다** — 넣지 않으면 Spring 앱으로 보고 10분마다 재시작된다.
- 감시가 Ollama 를 정상적으로 재시작해야 하는 경우(진짜 장애)에도 모델은 내려간다. 그 뒤에는 `deploy/lan/lan.sh rag-warm pc2` 로 다시 올린다(약 10초).

## 4. 영향

- LAN 개발 환경만(운영 없음). 데이터 손실 없음 — rag-pgvector 는 정상 종료·재시작, 색인 스냅숏·답변 기록은 그대로.
- 2026-11-09 운영자 RAG 답변 평가 중 한 회차가 이 재시작에 걸려 질문 2개가 503·500 으로 실패 — 그 회차 결과는 쓰지 않고 다시 평가했다(`docs/rag/verification/2026-11-09/answer-eval/report-7-final.md`).
