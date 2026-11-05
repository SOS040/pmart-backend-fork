# Codex 검토 의견 모음 (RAG)

사용자가 대화에 붙여 넣은 Codex 의 설계 검토 원문을 보관한다(2026-11-04~). 원문은 수정하지 않는다.
2026-11-05 저장소 루트 `codex-opinion/` 에서 `docs/rag/codex-opinion/` 으로 옮겼다(RAG 문서 모음, `../README.md`).
각 의견에 대한 Claude 의 반영 여부는 아래 표와 관련 문서에 있다.

| 날짜 | 파일 | 주제 | 반영 |
|---|---|---|---|
| 2026-11-04 | [2026-11-04-ai-assistant-review-1.md](2026-11-04-ai-assistant-review-1.md) | ai-assistant-service 1차 계획 검토 10개(대체 관계, 지위·구현 축 분리, 기준선 표시, 트랜잭션 경계, 인용 실패 처리, FORBIDDEN_SCOPE, 질문 분류, 로컬 실행·색인 일관성, 원문 저장, 버전 근거) | 10개 모두 계획에 반영. 4·5·6번은 1차 계획의 결함이었다 |
| 2026-11-04 | [2026-11-04-ai-assistant-review-2.md](2026-11-04-ai-assistant-review-2.md) | 2차 검토 7개(재조회 계약·X-Source-System 검증, context 포함 해시, lease 상실 처리, n-gram 휴리스틱 한계, 우선순위·대체 적용, 스냅숏 수명·입력 일관성, 계약·스키마 불일치) | 모두 계획에 반영. 1번 재조회 경로·7번 422 는 원 계획에 있었으나 전달 사본이 잘렸음 |
| 2026-11-04 | [2026-11-04-ai-assistant-review-3.md](2026-11-04-ai-assistant-review-3.md) | 3차 검토 6개(모든 반환 경로 권한 재검사, 출처 허용 목록 ≠ 출처 인증, 스냅숏 고정·삭제 원자성, 발췌 자르기 제거, 이력 질문의 1차 제외 문서, 스키마·상태 정리) | 모두 계획에 반영(계획 파일 머리말 '3차 검토 반영 요약') |
| 2026-11-04 | [2026-11-04-ai-assistant-plan.md](2026-11-04-ai-assistant-plan.md) | 세 검토를 반영한 ai-assistant-service 1차 계획(Claude 작성, 검토 10개 + 2차 7개 + 3차 6개 반영판) | 구현 기준 계획 |
| 2026-11-05 | [2026-11-05-ai-assistant-review-4.md](2026-11-05-ai-assistant-review-4.md) | 구현 뒤 검증 마무리(전체 check·dependencyInsight·config-copies, 로컬 HTTP 대표 질문, 변경 파일·보고서·실패 사례) | 실행·보고(`../worklog.md` 11-05). 작성자 표기 없음 |
| 2026-11-05 | [2026-11-05-ai-assistant-review-5.md](2026-11-05-ai-assistant-review-5.md) | 증거 정리(커밋 관계·HEAD, BR-46 근거·diff, Kafka 수정 근거, 전체 check 1회, 실패표 경로 복구) | 실행·보고. 정리 중 BR-46 페이지 결함 발견. 작성자 표기 없음 |
| 2026-11-05 | [2026-11-05-ai-assistant-review-6.md](2026-11-05-ai-assistant-review-6.md) | BR-46 페이지 결함 수정·회귀 시험, 문서 경로 기준 명시·절 기준 평가 추가, Kafka 수정은 추정 원인으로 기록, 자료 보관 | 모두 반영(`../verification/2026-11-05/README.md`). 작성자 표기 없음 |

ai-assistant-service 구현은 위 두 검토를 반영한 계획으로 진행한다(외부 모델 호출 없는 local/fake 기준선). 완료 보고는
"구조·권한·멱등 검증 완료"와 "실제 모델의 검색·답변 품질 미검증"을 구분한다.
