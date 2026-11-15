# RAG 인수인계 (2026-11-13)

RAG 영역(`docs/operations/pc-split.md` §1-1 — 나눌 예정)을 받는 PC·사람·Claude 가 **처음 읽는 문서**. 무엇이 어디 있고, 지금 어떤 상태이며, 무엇이 남았는지를 한곳에 모았다. 세부는 링크를 따라간다.

## 1. RAG 두 개

| | 운영자 RAG | 고객 RAG |
|---|---|---|
| 서비스 | `services/ai-assistant-service` | `services/customer-assistant-service` |
| 누가 묻나 | 직원(`typ=OPERATOR`, 백오피스) — 정책·업무 절차 질의 | 회원 고객(`typ=CUSTOMER`, 고객 앱) — 고객센터 안내. 비회원 403 |
| 결정 | ADR-0036(1차, 상태: 제안), ADR-0051(고객 확대·서비스 분리·Ollama, 승인) | ADR-0051 |
| 계약 | `contracts/openapi/ai-assistant-service.yaml` | `contracts/openapi/customer-assistant-service.yaml` |
| 게이트웨이 | `/api/v1/assistant/**`(응답 제한 65초) | `/api/v1/customer-assistant/**`(응답 제한 65초) |
| 서비스 규칙 | `services/ai-assistant-service/CLAUDE.md` | `services/customer-assistant-service/CLAUDE.md` |
| 포트 | 8508 | 8509 |
| DB | `ai_assistant_service`(rag-pgvector) | `customer_assistant_service`(rag-pgvector) |
| 질문 제한 | — | 1분 5개·하루 50개(Asia/Seoul), 질문 원문 저장 안 함(C3·C5) |

두 서비스는 운영자 RAG 를 복사해 시작했다(공용 라이브러리는 아직 — ADR-0051 기록). 고친 것이 한쪽에만 들어가지 않게 주의.

## 2. 임베딩(색인)한 문서 — 정본은 manifest

**여기 적힌 파일만 색인한다.** 문서를 더하거나 빼면 manifest 를 고치고 재색인한다(§4).

### 운영자 RAG — `services/ai-assistant-service/src/main/resources/knowledge/manifest.yml` (90개, 2,085 청크 — 11-11 LAN)

| 묶음 | 경로 | 개수 | 우선순위(precedence) |
|---|---|---|---|
| ~~서비스 목록~~ | ~~`docs/architecture/service-map.md`~~ — 2026-11-15 뺌(서비스 책임 경계는 업무 직원 대상이 아님, "직원 휴가 신청"에 없는 API 를 지어 답한 근거) | 0 | — |
| ADR(**업무 정책만**, 2026-11-15) | `docs/adr/` 0014·0015·0040~0049·0052·0060 | 14 | 5(지위는 각 ADR 상태 줄 — 승인=확정, 그 밖=제안). 서비스 경계·인프라·인증 구현·배포·관측·RAG 설계 ADR 은 뺌(사용자 "그건 일반 운영자쪽에서 안봐도되는내용인데") — 뺀 목록·이유는 `RealDocumentsIngestTest.EXCLUDED_ADRS`, 새 ADR 은 둘 중 하나로 정해야 시험 통과 |
| 저장소 설계 | `docs/business/overview.md`·`domain-map.md`·`glossary.md`, `docs/business/flows/`(cancel-vs-handover·order-checkout-saga·replenishment-transfer·return-exchange-refund), `contracts/states/*.md`(8개) | | 4 |
| 업무 원문 | `docs/business/{WMS,SCM,TMS,MFC,BackOffice}-business-logic.md`(4), `OMS-business-logic.md`(3), `Commerce-business-logic.md`(2), `Delivery-business-logic.md`(1) | | 4·3·2·1 |
| 고객센터 상담 대응 | `docs/business/cs-scripts/README.md`·`01~10`(2026-11-09) | 11 | 1(업무 원문이 앞섬) |

- 넣지 않는 것: worklog·handoff·그 밖 architecture 문서·코드·시험·배포·비밀.
- `overrides`: OMS 8.2 결품 문단 SUPERSEDED(ADR-0049), Commerce 카테고리 1·2단계 대체 제안(ADR-0015) — 이유는 manifest 안 `reason`.
- 직원 등록·권한 요청·승인은 ADR-0056 대신 업무 흐름 `docs/business/flows/employee-account-provisioning.md` 로 넣었다(2026-11-15).
- 평가 묶음도 같이 맞춤(2026-11-15): 검색 평가 `questions.yml` 은 기술 질문 7개를 빼고 R3 를 업무 질문으로, 업무 질문 Q21~Q23 추가(21개). LAN `rag_e2e.py` 의 "서비스 간 호출" → "상품준비 뒤 고객센터 취소". **답변 평가 `answers.json` 30문항 중 A07~A12·A19(빠진 ADR 이 정답) 7개는 아직 그대로** — 다음 답변 평가 때 업무 질문으로 바꿀 것.

### 고객 RAG — `services/customer-assistant-service/src/main/resources/knowledge/manifest.yml` (13개, 63 청크)

- `docs/business/customer-faq/01-customer-center.md` ~ `13-alcohol.md` — 고객 공개 안내만(Claude 초안·사용자 승인 2026-11-09 C1). 업무 문서·cs-scripts·ADR 은 내부 문서라 넣지 않는다.
- **고치거나 더하면 사용자 승인 뒤** manifest 에 넣고 재색인(`rag-ingest-customer`). 지위·우선순위 가중은 0(승인된 안내는 모두 같은 지위).

## 3. LAN 배치·모델 (PC 2 — 2026-11-11 PC 2 → PC 5, **2026-11-15 PC 5 → PC 2 다시**: PC 5 메모리. 데이터·모델 볼륨을 그대로 옮김, 색인은 그대로)

| 구성요소 | 내용 |
|---|---|
| `ollama` | PC 2 GPU(RTX 2060, 쓸 수 있는 메모리 약 5GB). 컨테이너 CPU 4·메모리 6GB 상한(같은 PC 의 주문·결제 몫을 남김 — 재색인은 주문 시험이 없을 때). LAN 포트 없음 — 같은 PC 서비스만 `http://ollama:11434` |
| 대화 모델 | `qwen3:4b`(GPU 100%, 3.9GB, 문맥 8192) — 사용자 결정 2026-11-09(8B 와 비교 25 vs 26/30, 8B 는 8~22초) |
| 임베딩 모델 | `qwen3-embedding:0.6b`(ID `ac6da0dfba84`, 1024차원, **CPU** — `embedding-num-gpu: 0`, GPU 는 대화 모델에). 2026-11-09 비교 평가로 결정(`verification/2026-11-09/embedding-compare/`) |
| `rag-pgvector` | PostgreSQL + pgvector, 포트 15433(LAN 노출 없음), 서비스마다 DB·계정(`deploy/lan/rag/rag-init-db.sh`) |
| 설정 | 설정 저장소 `ai-assistant-service{,-lan,-local}.yml`·`customer-assistant-service{,-lan,-local}.yml` — 임계값(운영자 LAN 0.40, 발췌 전환 하한 0.45, 지위 가중 ×0.5), 대화 온도(운영자 0.2·고객 0.0), 요청 제한 25초 |
| 로컬 | `deploy/local/ai-assistant/docker-compose.yml`(pgvector 15433). local 프로필은 해시 임베딩 기준선(`provider: local`, 384차원) — 외부·Ollama 없음 |

- 배치 정본 `deploy/lan/placement.conf`(pc2 줄 `rag-pgvector ollama ai-assistant-service customer-assistant-service`), compose 는 `deploy/lan/render.sh`.
- 감시: `deploy/lan/watch.sh` 가 rag-pgvector·ollama 를 SSH 로 컨테이너 안에서 점검(2026-11-09 LAN 포트 없는 둘을 HTTP 로 보다 10분마다 재시작시킨 사고 뒤).
- 아이디어(결정 아님): 임베딩을 GPU 로(5대 모두 GPU) — 지금은 CPU 라 운영자 전체 재색인 약 60분.

## 4. 명령 (배포·색인은 PC 1 에서)

```bash
deploy/lan/lan.sh rag-pull pc2 qwen3-embedding:0.6b qwen3:4b   # 모델 받기
deploy/lan/lan.sh rag-warm pc2                                  # 대화 모델 미리 올림(첫 적재 ~30초 > 요청 제한 25초) — ollama 재시작 뒤 한 번
deploy/lan/lan.sh rag-ingest pc2                                # 운영자 RAG 재색인(일회 컨테이너, manifest 파일들을 rag/knowledge-operator 로 복사해 /repo)
deploy/lan/lan.sh rag-ingest-customer pc2                       # 고객 RAG 재색인
```

- 임베딩 모델 파일을 바꾸면 설정 `embedding-version` 을 바꾸고 재색인.
- LAN 색인에는 출처 커밋이 비어 기록된다(render 가 `REVISION` 파일을 두지만 색인기가 아직 읽지 않음 — 남은 일).

## 5. 시험·평가

| 무엇 | 경로·명령 |
|---|---|
| 단위·통합 | `./gradlew :services:ai-assistant-service:check`, `:services:customer-assistant-service:check` |
| 검색 평가(질문·정답 문서/절) | `services/ai-assistant-service/src/integrationTest/resources/eval/questions.yml`(`RetrievalEvalIT`) |
| 운영자 답변 평가(30문항, 정답 사실) | 질문 `…/integrationTest/resources/eval/answers.json`, 채점 `deploy/lan/checks/rag_answer_eval.py` → 보고서 `docs/rag/verification/<날짜>/answer-eval/` |
| 고객 답변 평가(37문항) | 질문 `deploy/lan/checks/eval/customer-answers.json`, 채점 `deploy/lan/checks/customer_rag_eval.py`(고객 토큰 필요 — `.secrets/customer-token`, 15분) |
| LAN 연결 확인 | `deploy/lan/checks/rag_e2e.py`(운영자 6문항), `customer_rag_e2e.py`(경로·403·헬스) |
| 직원 토큰 | `deploy/lan/checks/staff_auth.py`(2026-11-13 — auth-service 운영자 로그인을 없앤 뒤) — `docs/operations/dev-on-other-pcs.md` |

**최근 결과**: 운영자 7차 엄격 24/30·사람 26/30(답 없는 질문 11/11, 1.7~4초 — `verification/2026-11-09/answer-eval/report-7-final.md`), 고객 9차 37/37(`verification/2026-11-10/customer-eval/report.md`). 11-11 PC 5 이동 뒤 `rag_e2e` 6/6·`customer_rag_e2e` 3/3. 11-15 PC 2 이동 뒤 같음(6/6·3/3), qwen3:4b 100% GPU.

## 6. 남은 일·결정 대기

- **2026-11-15 재색인 뒤(ADR-0056~0061 추가, 96개·2,158 청크)**: `rag_e2e` **5/6** — "직원 휴가 신청은 어떻게 해?"(근거 없음 기대)에 service-map hr-service 절을 근거로 **없는 API(`POST /hr/employees/{id}/leave`)를 지어 답함**(전에는 NONE). "refresh token 보관·CSRF" 질문은 ADR-0059 의 **배경(예전 sessionStorage 방식)**을 답함 — 결정 절이 아니라 배경 절이 먼저 잡힘. 둘 다 생성 답변 충실도·절 우선순위 문제(재평가 대상). 같은 날 고친 것: 상담 대본 `cs-scripts/02-order-cancel.md` §5 가 옛 ADR-0052 결정 2 로 남아 "고객센터에서 취소할 수 있다"고 답하던 것 → ADR-0060 에 맞춤, 재색인 뒤 바르게 답함. `lan.sh rag-ingest*` 는 이제 render 를 먼저 한다(전에는 마지막 render 때 문서로 색인).
- 운영자: 오답 A09(TMS 문서를 고름)·A11, 조건 빠짐 A05·A15 — 질문 묶음을 늘려 재평가. 색인 출처 커밋(`REVISION`). ADR-0056·0057 색인 여부.
- 고객: C11 "신선식:" 글자 잘림 1건. 운영자 RAG 에도 같은 결함(숫자 부분 문자열 검사·표 한 행) 있는지 확인.
- 결정 대기(ADR-0036·0051): 답변 보존 기간·질문 원문 저장(운영자), 운영자 역할 세분, ADR-0051 "결정 대기" 절.
- 공용 라이브러리(두 서비스 공통 코드) — 아직 없음.
- 개발 PC(PC 4)에서: **평소는 local 프로필**(해시 임베딩 384차원, 모델 없음 — `deploy/local/ai-assistant/docker-compose.yml` pgvector 15433). 실제 모델로 답변을 볼 때만 PC 4 에 로컬 ollama(같은 모델 qwen3:4b·qwen3-embedding:0.6b — PC 1 이 11-09 평가 때 쓴 방식). 실제 모델 임베딩(1024)과 해시(384)는 DB 를 섞지 않는다. LAN rag-pgvector·ollama(PC 2, LAN 포트 없음)에는 붙지 않는다 — LAN 색인은 PC 1 `rag-ingest` 로만. 로컬 ollama 로 부족하면 PC 2 포트 열기를 그때 정한다(사용자 2026-11-15 "일단 자기걸쓰는데 … 한번해보고 포트열든"). PC 4 에는 게이트웨이·설정 서버가 있어 무거운 시험 중 LAN 이 느려지면 PC 1 에 알린다.

## 7. 이 폴더(`docs/rag/`) 지도

| 경로 | 내용 |
|---|---|
| `README.md` | 폴더 안내(11-05 기준 상태 — **최신 상태는 이 문서**) |
| `worklog.md` | RAG 작업 일지(11-04~11-10). 영역을 나눈 뒤에는 `docs/worklog/rag/`(만들 때) 또는 여기 |
| `codex-opinion/` | 설계·검토 원문 1~6차(수정 금지)와 1차 계획 |
| `references/2026-11-07/` | 사용자가 넣은 외부 자료 원본(무료 모델 추천·Spring AI 구조 검토) |
| `planning/` | 원본 대조·운영자/고객 RAG 보고서·구조도(2026-11-07) |
| `verification/2026-11-05/` | 1차 검토(빌드·시험 로그·diff·검색 기준선) |
| `verification/2026-11-09/` | 임베딩 비교(`embedding-compare/`), 운영자 답변 평가 1~7차(`answer-eval/`) |
| `verification/2026-11-10/` | 고객 답변 평가(`customer-eval/`) |
