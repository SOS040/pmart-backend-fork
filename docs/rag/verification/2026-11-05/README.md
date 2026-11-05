# ai-assistant 검토 자료 (2026-11-05)

외부 검토 요청에 따라 임시 폴더(Windows `%TEMP%`, 세션 scratchpad)와 `build/` 에만 있던 로그·diff·평가 결과를 이곳에 모았다. 아직 커밋하지 않았다(커밋·push 보류).

## 1. 저장소 상태

| 저장소 | HEAD(이 자료를 만들 때) | 작업 트리 |
|---|---|---|
| pmart-backend | `619489e` | 미커밋: ai-assistant 검토 반영(아래 3·4), payment sagaId 묶음(별건, 문서 `trace-gateway-checkout-payment.md`), 이 폴더 |
| pmart-config | `6cbf609` | 깨끗함 |

- ai-assistant 관련 기존 커밋은 모두 2026-11-04 에 커밋·push 됐다: `a387410`(1차 구현), `bcb2fd4`(BR-46 질문 목록 포함), pmart-config `7676cb8`.

## 2. 이전 검증 (다시 돌리지 않은 것 포함)

| 항목 | 결과 | 파일 |
|---|---|---|
| 전체 `./gradlew check --continue` (09:35, HEAD `47ecacc`) | BUILD SUCCESSFUL 12m 7s, 첫 실행 통과 | `logs/full-check.log` |
| `:services:ai-assistant-service:check` (09:23) | 통과 | `logs/aa-check.log` |
| `dependencyInsight` (spring-ai 2.0.2, spring-boot-starter 4.0.9) | | `logs/aa-di.log` |
| `config-copies.sh check` | 시험 사본 = 정본 | `logs/aa-cc.log` |
| BR-46 원래 diff (`bcb2fd4` 중 ai-assistant) | | `diffs/br46-bcb2fd4.diff` |
| Kafka 시험 컨테이너 수정 diff (`869a6b1`) | | `diffs/kafka-testcontainer-869a6b1.diff` |

## 3. BR-46 질문 목록 페이지 결함 수정

- **결함**: `PolicyQuestionService.history()` 는 권한 재검사로 빠지는 행이 있으면 목록이 중간에 끊겼다.
  - 최근 순 [A(권한 없음), B, C, D], size 2 → 1쪽 [B, C] 뒤 `nextCursor = null` → D 를 영영 못 봄(사례 A·B·C·D).
  - 반대로 뒤에 숨김 행만 남았는데 커서를 주는 경우도 있었다.
- **수정**:
  - 페이지 계산을 `domain/HistoryPager`(프레임워크 없는 순수 함수)로 분리했다.
  - 보이는 행을 **size + 1 개째까지** 찾아 본 뒤, 그때만 커서를 준다(커서 = 이 쪽 마지막 보이는 행). 그래서 커서가 있으면 다음 쪽은 비어 있지 않고, 커서가 없으면 더 볼 것이 없다.
  - 숨김 행이 몇 개 연속돼도 계속 읽는다. 읽는 양은 그 사용자 행 수를 넘지 않는다. 예전의 "최대 5번" 상한은 없앴다.
- **회귀 시험**:
  - `HistoryPagerTest` 8개:
    - A·B·C·D 사례
    - 숨김 행 연속 + 읽기 단위 경계
    - 뒤에 숨김 행만 남음 → 커서 없음
    - 정확히 하나 더 있을 때 커서
    - 전부 숨김
    - 빈 목록
    - 모든 조합: 행 8개의 숨김/보임 256 조합 × 쪽 크기 1~4 × 읽기 단위 1·2·3·50 — 이어 붙인 결과 = 보이는 행 순서대로, 누락·중복 없음, 마지막 쪽 전에는 꽉 참, 커서가 있으면 다음 쪽 비지 않음
    - 읽는 양
  - `PolicyQuestionFlowIT.questionHistoryKeepsPagingWhenTheNewestAnswerIsHidden`: 실제 HTTP·DB 로 A·B·C·D.
    - **수정 전 코드로 돌려 실패 확인**: `logs/aa-br46-before.log` — "D 가 남았으므로 커서가 있다" 에서 실패.
    - 수정 후 통과: `logs/aa-br46-after.log`
- diff: `diffs/ai-assistant-review-fixes.diff`
  - `PolicyQuestionService.java`
  - 새 파일 `HistoryPager.java`·`HistoryPagerTest.java`
  - `PolicyQuestionFlowIT.java`
  - 평가 관련 파일

## 4. 검색 평가 — 문서 경로 기준 명시, 절 기준 추가

| 지표 | recall@5 | MRR | 보고서 |
|---|---|---|---|
| **문서 경로 기준**(기존, 결과 보존) | 0.76 | 0.628 | 이전 `before/retrieval-baseline-path-only.md`, 이번 `after/retrieval-baseline-path.md`(머리말만 바뀌고 본문 동일 — diff 확인) |
| **절 기준**(추가) | 0.60 | 0.387 | `after/retrieval-section.md` |

- **문서 경로 기준**: 상위 5 안에 정답 문서의 청크가 하나라도 있으면 적중이다. 그 청크가 답을 담은 절인지는 보지 않는다.
- **절 기준**: 정답 문서의 **정답 절** 청크여야 적중이다.
  - 정답 절은 `src/integrationTest/resources/eval/questions.yml` 의 `sections` 다.
  - 검색 결과를 보지 않고 원문을 읽어, 각 질문의 답을 직접 적은 절만 골랐다.
  - 기존 `expected` 문서 안에서만 골랐다. 문서를 넓히지 않았다.
  - 일부러 뺀 것은 다음과 같다.
    - Q04 Delivery 6.4: 5시간 자동 완료 — domain-map 이 폐기한 옛 규칙
    - Q05 ADR-0002: 누가 쓰는지만 있고 차감 시점은 없음
    - 부록 C(1차 제외), 검증 시나리오, 화면 명세
- 시험이 정답 절이 색인에 실제로 있는지 대조한다. 오타나 제목 변경으로 영원히 못 맞히는 정답을 막기 위해서다.
- **문서는 맞혔지만 절은 못 맞힌 질문**: Q01·Q04·Q11·Q20. 상위 5 근거가 보고서 끝에 있다.
- 이전 실패표(정답 경로 전체 이름 복구판): `before/retrieval-failures-full-paths.md`
- 모두 어휘 해시 기준선이다. 의미 검색·생성 답변 품질은 실제 모델 평가 전까지 미검증이다(ADR-0036).

## 5. 이번 실행 결과

| 항목 | 결과 | 파일 |
|---|---|---|
| `:services:ai-assistant-service:check` (spotlessApply 뒤) | BUILD SUCCESSFUL, 단위 91 · 통합 31, 실패 0 | `logs/aa-check-after-fix.log`, `after/test-results/` |
| 공용 코드(`libraries/`) 변경 | 없음 → 전체 빌드 재실행 안 함 | |

## 6. Kafka 시험 컨테이너 수정의 기록 방식

- `869a6b1`(common-test `KafkaTestContainer`)은 **추정 원인에 대한 방어적 수정**이다. 원인을 확정한 것이 아니다.
- **추정**: Testcontainers 2.0.5 `KafkaContainer` 기본 명령이 시작 스크립트 파일이 생기자마자 실행한다. 그래서 복사(내용·실행 권한)가 끝나기 전이면 셸이 126 으로 끝난다.
- **근거**:
  - jar 의 기본 명령 문자열과 `copyFileToContainer` 순서(`javap` 확인)
  - 2026-11-04 전체 check 의 종료 코드 126(작업 일지)
- **확인하지 못한 것**: 재현하지 못했다. 당시 실패 로그는 이후 실행으로 덮여 남아 있지 않다.
- **수정 내용**: 스크립트 마지막 줄까지 쓰인 것을 확인한 뒤 `bash` 로 읽혀 실행한다(실행 권한이 필요 없음). 다른 원인이었다면 다시 날 수 있다.
- 수정 뒤 전체 check 2번(11-05 05:40·09:35)은 126 없이 통과했다.
- 커밋 메시지와 `KafkaTestContainer` 주석은 단정형으로 적혀 있다. 주석은 공용 코드라 이번에 고치지 않았다. 작업 일지 `docs/worklog/2026-11-05.md` 05:40 절을 이 표현으로 고쳤다.

## 7. 보류

커밋·push, LAN 배포, 외부 모델 연결.
