# Elasticsearch 8.18.0 Basic 라이선스 기능 확인 (2026-11-18, PC 4)

RAG 구조 개편 계획([`../../planning/2026-11-18-es-hybrid-restructure-plan.md`](../../planning/2026-11-18-es-hybrid-restructure-plan.md))에 필요한 ES 기능이 **무료(Basic)**로 되는지 실제 요청으로 확인했다. LAN에서 쓰는 ES와 같은 판이고, LAN 서버는 건드리지 않았다.

## 환경
- **이미지**: `docker.elastic.co/elasticsearch/elasticsearch:8.18.0`. LAN `es-search`·`es-logs`와 같은 판이다(ADR-0031 결정 7).
- **실행**: PC 4 로컬, `127.0.0.1:19200`에만 열었다. `discovery.type=single-node`, `xpack.security.enabled=false`, 힙 1g.
- **라이선스**: `GET _license`의 `type`이 `basic`이었다. trial은 켜지 않았다.
- **데이터**: 시험용 인덱스 하나(`text` + `keyword` + `dense_vector` 3차원 HNSW cosine)에 문서 3개.
- **정리**: 확인한 뒤 컨테이너를 지웠다.

## 결과

| 기능 | 결과 | 응답 |
|---|---|---|
| BM25 (`match` + `bool.filter`) | **가능** | 200 |
| kNN (`dense_vector` HNSW cosine + `filter`) | **가능** | 200 |
| 한 요청 안의 `query` + `knn` 점수 합(boost) | **가능** | 200 |
| `bool.should`에 `match` + `knn` 쿼리 | **가능** | 200 |
| retriever `standard` / `knn` | **가능** | 200 |
| retriever `rescorer`(query rescore) | **가능** | 200 |
| `rescore` + `script_score`(cosine 재계산) | **가능** | 200 |
| retriever `rrf` | **불가(유료)** | 403 `current license is non-compliant for [Reciprocal Rank Fusion (RRF)]` |
| retriever `linear` | **불가(유료)** | 403 `current license is non-compliant for [linear retriever]` |
| retriever `text_similarity_reranker` | **불가(유료)** | 403 `current license is non-compliant for [text_similarity_reranker]` |
| inference API (`PUT _inference/rerank/…`, Elastic Rerank `.rerank-v1`, `hugging_face`) | **불가(유료)** | 403 `current license is non-compliant for [inference]` |
| ML 학습 모델 (`PUT _ml/trained_models/…`) | **불가(유료)** | 403 `current license is non-compliant for [ml]` |

Learning to Rank는 요청 본문 형식 오류(400)로 끝나서 라이선스 판정까지 가지 못했다. 다만 같은 `_ml/trained_models` API를 쓰므로 위 ML 거부와 같은 조건으로 본다(직접 확인한 것은 아님).

## 결론
- **무료로 되는 것**: BM25, kNN(HNSW cosine), filter
- **유료라 안 되는 것**: ES 내장 결합(RRF·linear)과 ES 리랭커(text_similarity_reranker·inference·ML)
- **계획 반영**: RRF는 앱에서 계산하고, 리랭커는 무료 대체안 중에서 고른다(계획서 Q1·Q2).
- **참고(직접 확인 안 함)**: Elastic 포럼에서는 9.x에서도 RRF·linear가 Enterprise 등급이라고 한다. ES 판을 올려도 무료가 되지 않는 것으로 본다.

## 다시 확인하는 법
1. ES 8.18.0을 Basic으로 띄운다.
   ```bash
   docker run -d --name rag-es-licensecheck -p 127.0.0.1:19200:9200 \
     -e discovery.type=single-node -e xpack.security.enabled=false -e ES_JAVA_OPTS="-Xms1g -Xmx1g" \
     docker.elastic.co/elasticsearch/elasticsearch:8.18.0
   ```
2. Python(`urllib`)으로 위 표의 요청을 하나씩 보낸다.
   - 한국어 본문은 UTF-8로 보낸다. Windows Git Bash의 curl로 보내면 cp949로 깨진다.
3. 끝나면 컨테이너를 지운다.
   ```bash
   docker rm -f rag-es-licensecheck
   ```
