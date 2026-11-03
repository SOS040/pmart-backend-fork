# ADR-0031 검색·로그를 Elastic Stack 으로 — Elasticsearch 8.18(nori), 검색 색인은 Logstash 가 도메인 이벤트로, 로그는 Filebeat → Logstash

- 상태: 승인 (2026-11-03, 사용자 결정 "엘라스틱서치 쓰는 쪽으로 바꾸는 게 나을 것 같다, Eum 쪽을 참고해서" — 검색 데이터는 "이벤트 유지 + Logstash", 로그는 "Eum 구성 + 상한 유지")
- 대체: ADR-0021 결정 1(OpenSearch)·2(search-service 가 직접 색인 — 색인 주체만 Logstash 로)·3(n-gram 만, nori 없음)·4(search-service 의 Painless 갱신 — 같은 스크립트를 Logstash 가 실행). ADR-0030 결정 2(Fluent Bit → OpenSearch, OpenSearch Dashboards)·4(OpenSearch 단일 노드 복제본). ADR-0021 결정 5~8, ADR-0030 결정 1·3(지표·디스크 감시·dead_letter)은 유지.
- 보완: ADR-0034(2026-11-03) — 결정 3·8 의 "이벤트는 Kafka 에 보존"을 `commerce.product.v1`·`commerce.pricing.v1` 무제한 보관으로 보장하고, 재색인 방식(전체 재생)·선행 조건을 정한다
- 관련: Eum(`Eum-main/eum_backend/docker-compose`) 비교 `docs/architecture/toss-payment-eum-comparison.md` 와 2026-11-02 대화, ADR-0009(이벤트 계약), CLAUDE.md 절대 규칙(다른 서비스 DB 직접 접근 금지)

## 배경
- 사용자가 검색 엔진을 Eum 과 같은 Elasticsearch 로 바꾸고, 데이터 넣는 방법·Logstash·로그 수집·검색과 로그의 관계를 Eum 처럼 하기로 했다.
- Eum 은 Debezium 이 **업무 DB 테이블 변경을 그대로** 검색 엔진에 넣는다. pmart 에서는 절대 규칙(다른 서비스 DB 직접 접근 금지)과 ADR-0009 에 어긋나므로 사용자가 "이벤트 유지 + Logstash"를 택했다.
- Eum 로그는 앱 → Logstash TCP + Filebeat(게이트웨이 파일만)이고 보존 정책·폭주 차단이 없다. 사용자가 "Eum 구성 + 상한 유지"를 택했다(2026-10-25 pc3 디스크 고갈 교훈).
- 로컬 확인(2026-11-03): ES 8.18.0 + analysis-nori 로 "칠성사이다 제로 500ml" → 칠성|사이다|제로, "국산콩으로 만든 두부를" → 국산|콩|만들|두부, 그러나 붙여 쓴 "나랑드사이다제로" → 나|드사이|다제 — nori 만으로는 상품명 부분 검색이 깨진다.

## 결정
1. **검색 엔진 = Elasticsearch 8.18.0**(Eum 과 같은 판), LAN 단일 노드·보안 끔(LAN 시험망, 공개 상품 정보·앱 로그만. 운영은 인증·TLS 필수). **analysis-nori 는 컨테이너 시작 때 없으면 설치**(Eum 방식, 인터넷 필요). 색인을 자동으로 만들지 않는다(`action.auto_create_index` = 로그 색인만 허용) — 매핑 없는 색인이 생기지 않게.
2. **한국어 분석 = nori + n-gram 함께**(Eum 의 다중 필드 방식): 상품명 `name`(nori) · `name.edge`(앞부분 일치, edge n-gram 1~10) · `name.partial`(부분 일치, n-gram 1~2). 검색은 세 필드를 함께 보고(nori 일치가 점수 높음), **모든 n-gram 조각이 있어야 한다**(지금의 AND 동작 유지 — "나랑드사이다제로" 같은 붙임말·브랜드명도 찾는다). 새 색인 `products-v2`(매핑 변경이므로 새 버전).
3. **검색 데이터 = 도메인 이벤트 그대로, 넣는 주체는 Logstash**(Eum 의 Logstash 자리): `commerce.product.v1`·`commerce.pricing.v1` 을 Logstash Kafka 입력(소비 그룹 `search-index-logstash`)이 읽어 ES 에 반영한다. 갱신 규칙은 기존 search-service 의 것을 그대로 옮긴다:
   - 버전·시각 비교 Painless 스크립트(같은 사건 재전달·늦게 온 옛 사건은 바꾸지 않음 — 멱등·순서 안전)
   - 상품 사건: 카테고리 조상 범위 계산, 대표 SKU 가격이 먼저 와 있으면 붙임
   - 콘텐츠 사건: product-content 공개 카드 API 로 이름·대표 이미지 보강(HTTP 필터)
   - 가격 사건: SKU 가격 문서 + 그 SKU 를 대표로 둔 상품 일괄 갱신(update_by_query)
   - 정의 파일은 search-service 소유(`services/search-service/src/main/logstash/search-index.conf`), 색인 정의도 search-service(`src/main/resources/elasticsearch/*.json`). 배포가 Logstash·초기화 컨테이너에 복사한다.
   - search-service 는 **조회만** 한다(Kafka 소비·색인 코드 제거). 업무 DB 는 읽지 않는다.
   - 실패: Logstash 영구 큐(디스크 상한 256MB)에 들어간 뒤 ES 쓰기는 재시도, 매핑 오류 등(400/404)은 Logstash dead letter queue(상한 64MB). 콘텐츠 보강 HTTP 실패는 태그를 붙여 로그로 남기고 건너뛴다 — 다시 넣으려면 소비 그룹 오프셋을 되돌린다(이벤트는 Kafka 에 보존).
4. **로그 = Filebeat → Logstash → Elasticsearch → Kibana**(Eum 구성). 앱은 stdout JSON 만 낸다(앱이 Logstash 로 직접 보내지 않는다 — 수집 장애가 앱에 번지지 않게). **PC 마다 Filebeat** 가 Docker 컨테이너 로그를 읽는다. ADR-0030 의 디스크 상한은 그대로:
   - 앱 JSON 로그(`serviceName`)만 보낸다(Filebeat 에서 버림 — 인프라 로그 폭주·자기 순환 차단).
   - 초당 500건 상한(Filebeat `rate_limit`). 큐는 메모리만(디스크 버퍼 없음 — Logstash 가 멈추면 Filebeat 가 읽기를 멈추고, 원본은 `docker logs` 20MB×3 에 남는다).
   - 색인 `pmart-logs-YYYY.MM.DD`: **3일(ILM 삭제) 또는 1GB 초과 시 삭제, 전체 4GB 초과 시 오래된 것부터 삭제**(`log-janitor` 가 5분마다 — ILM 은 크기로 지우지 못한다).
5. **검색과 로그의 관계 = 같은 Elasticsearch 노드**(Eum 과 같음, 지금과 같음). 로그가 검색에 영향을 주면 그때 노드를 나눈다.
6. 화면은 **Kibana**(5601, OpenSearch Dashboards 대체). 초기화 컨테이너 `es-init` 이 ILM·색인 틀·검색 색인(`products-v2`·`sku-prices-v1` + 별칭)·Kibana 데이터 뷰를 만들고 끝난다(다시 돌아도 같은 결과). Logstash 는 `es-init` 이 끝난 뒤 뜬다.
7. 이미지(버전 고정): `docker.elastic.co/elasticsearch/elasticsearch:8.18.0`, `…/kibana/kibana:8.18.0`, `…/logstash/logstash:8.18.0`, `…/beats/filebeat:8.18.0`. 라이선스는 Elastic(Basic 무료 기능만 사용).
8. **LAN 전환**: OpenSearch·OpenSearch Dashboards·Fluent Bit·logs-init 을 내리고 위로 바꾼다. 검색 색인은 Logstash 새 소비 그룹이 Kafka 처음부터 읽어 다시 만든다. 지난 LAN 로그는 옮기지 않는다.

## 대안
- **OpenSearch 유지(ADR-0021·0030)**: 사용자 결정으로 제외.
- **Eum 과 똑같이 테이블 CDC**: 절대 규칙 위반, 상품 테이블이 바뀌면 검색이 깨진다 — 사용자가 택하지 않았다.
- **search-service 가 계속 Kafka 를 소비해 ES 에 직접 색인**: 시험·오류 처리(Kafka 재시도·DLT)가 더 단단하지만, 사용자가 Eum 처럼 Logstash 를 거치기로 했다.
- **앱 → Logstash TCP(Eum)**: 경로가 둘이 되고 앱이 수집 서버에 의존한다.

## 결과
- 검색 품질: nori 로 조사·어미가 붙은 검색어("두부를")와 뜻 단위 순위가 좋아지고, n-gram 으로 붙임말 부분 검색은 그대로.
- 대가: (1) 색인 규칙이 Java 가 아니라 Logstash 설정에 있다 — Logstash 를 실제로 띄운 통합 시험으로 지킨다. (2) Logstash Kafka 입력은 오프셋을 주기적으로 커밋하므로 공통 라이브러리의 재시도·DLT·dead_letter 대신 Logstash 영구 큐·DLQ 를 쓴다(재처리는 오프셋 되돌림). (3) pc4 메모리가 약 1GB 늘어난다(Logstash). (4) ES 시작 때 nori 설치에 인터넷이 필요하다(통합 시험도 같다). (5) Elastic 라이선스(무료 Basic).
- Filebeat 메모리 큐라 Logstash 가 오래 멈추면 그 사이 로그는 `docker logs` 회전에 따라 잃을 수 있다(디스크 보호를 우선).
