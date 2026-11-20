# RAG 문서 버전 관리 구조 확인 (2026-11-20, PC 4)

요청(사용자 2026-11-20, 5-6): 문서가 바뀌어 버전이 달라질 때 **사람이 문서 수정·PR → 트리거 실행 → 바뀐 문서 색인 → 문서 버전 갱신** 구조가 있는지, 어디까지 되어 있는지.
기준: 브랜치 `rag/operator-rag-docs`(main `00a56bc8` + 이번 변경)의 코드·배포 스크립트. 코드는 바꾸지 않고 읽기만 했다.

## 결론 (단계별)

| 단계 | 있음? | 지금 모습 |
|---|---|---|
| ① 사람이 문서 수정 → PR | **있음** | 다른 PC 는 포크 `<영역>/<주제>` 브랜치로 PR, PC 1 이 검토·병합(`docs/operations/pc-split.md`). 색인 대상은 manifest 명시 목록뿐이라 새 문서는 manifest 에도 한 줄 넣어야 한다 — 빠뜨리면 `RealDocumentsIngestTest`(운영자용)·`CustomerScopeIngestTest`(고객용)가 실패한다(이번 변경). |
| ② 트리거 실행 | **없음(수동)** | 병합해도 자동으로 색인이 돌지 않는다. 저장소에 CI(`.github/workflows`·Jenkinsfile)가 없고, 기동 때도 색인하지 않는다(ADR-0036 결정 6). PC 1 이 손으로 `deploy/lan/lan.sh rag-ingest pc2`·`rag-ingest-customer pc2` 를 실행한다(그 전에 이미지 재빌드·배포 — manifest 가 이미지 안에 있다). |
| ③ 바뀐 문서만 색인 | **부분** | 색인은 매번 manifest 의 **모든 문서로 새 스냅숏**을 만든다(바뀐 문서만 골라 넣는 증분 색인 없음). 다만 임베딩 캐시(`embedding_cache`, 키 = 모델·버전·차원·전처리 해시·**본문 해시**)가 있어 **내용이 같은 청크는 Ollama 를 다시 부르지 않고**, 바뀐 청크만 새로 임베딩한다. LAN 에서는 render 가 manifest 에 적힌 파일만 `build/<pc>/rag/knowledge-*` 로 복사해 일회 컨테이너가 읽는다. |
| ④ 문서 버전 갱신 | **스냅숏 단위로만** | 스냅숏(`index_snapshot`)마다 manifest 해시, 출처 커밋(`source_revision`, render 의 REVISION), 작업 트리 변경 여부(`source_dirty`), 파일별 SHA-256·크기(`index_source_file`)를 남긴다. 상태 BUILDING → ACTIVE(한 트랜잭션으로 전환) → RETIRED, 답변은 만든 때의 스냅숏 ID 에 묶인다. **문서별 버전 번호를 올리는 장치는 없다** — 청크의 `document_version` 은 문서 머리에 "버전: x.y" 가 있을 때 읽어 둘 뿐이고, rag-docs 문서는 "시행일"만 있어 비어 있다. rag-docs README 의 "문서 버전 0.x" 는 사람이 적는 표시다. |

정리하면 **①은 있고, ②는 사람이 손으로, ③은 전체 재색인 + 바뀐 청크만 재임베딩, ④는 스냅숏(커밋·파일 해시) 기록까지**다. "병합 → 자동 트리거 → 바뀐 문서만 색인 → 문서 버전 갱신" 의 자동 흐름은 없다.

## 근거 (코드·스크립트)
- 색인 작업: `services/ai-assistant-service/build.gradle.kts`(`ingestKnowledge`), `KnowledgeIngestor.ingest`(INGEST 잠금 → BUILDING → 파일 읽기·해시 → 청크 → 색인 중 파일 변경 감지 → `SnapshotStore.activate`).
- 스냅숏·출처 기록: `db/migration/V1__init.sql` 의 `index_snapshot`(manifest_hash·source_revision·source_dirty·preprocessing_hash), `index_source_file`(path·sha256·bytes), `doc_chunk.document_version`.
- 임베딩 캐시: 같은 V1 의 `embedding_cache` 기본키, `KnowledgeIngestor` 의 `cachedEmbedding`/`cacheEmbedding`.
- LAN 실행: `deploy/lan/lan.sh` `rag-ingest`·`rag-ingest-customer`(render → rag-warm → copy → 일회 컨테이너), `deploy/lan/render.sh`(manifest 의 `path:` 줄만 복사, REVISION 기록).
- 옛 스냅숏 정리: `pruneSnapshots`(수동, 유예 지난 RETIRED·FAILED 중 처리 중 답변이 없는 것).

## 없는 것 (만들려면 필요한 일 — 이번에 만들지 않음)
1. 병합 트리거: main 병합을 감지해 이미지 빌드·`rag-ingest` 를 부르는 장치(CI 또는 PC 1 의 감시 작업).
2. 증분 색인: 바뀐 파일만 새 스냅숏에 넣고 나머지는 이전 스냅숏에서 복사(지금은 전체 재청크·캐시로 임베딩만 아낌).
3. 문서별 버전: 문서 머리의 버전·시행일을 읽어 문서 단위 이력(언제 어떤 내용으로 바뀌었는지)을 남기는 표.
4. 비정상 종료로 남은 BUILDING 스냅숏 정리(2026-11-19 확인한 빈틈 — 아직 고치지 않음).
