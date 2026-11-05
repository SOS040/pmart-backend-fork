# ADR-0037 LAN Kafka 를 3노드 KRaft 클러스터로 — 노드마다 브로커+컨트롤러, 복제 3, min.insync.replicas 2, 자동 토픽 생성 끔

- 상태: 제안 (2026-11-05, 사용자 결정 "처음 안인 3대 클러스터로 확정", 전환 2026-11-06 예정)
- 보완: ADR-0034(이력 토픽 무제한 보관·백업·복원·감시 — 복제 3 에 맞춰 토픽 생성·크기 측정 방식을 바꾼다), ADR-0035(Stream 소비 — 바인더 설정은 그대로, 접속 주소만 3노드), ADR-0033(Config Bus 토픽도 복제 3)
- 관련: ADR-0009(Outbox·Debezium), ADR-0013(LAN compose 배포), ADR-0030(관측·경보), `docs/operations/2026-11-06-kafka-cluster-elk-split-plan.md`(작업·전환·롤백 절차)

## 배경
- 지금 LAN Kafka 는 pc2 단일 노드다(`apache/kafka:4.1.1`, KRaft combined, 투표자 `1@localhost:9093`, 내부 토픽 복제 1, 자동 토픽 생성 켜짐). pc2 가 꺼지면 업무 이벤트 발행(Debezium)·소비 18개 서비스·Config Bus·검색 색인이 모두 멈춘다.
- 사가(ADR-0003)·outbox(ADR-0009)·검색 재색인(ADR-0034)이 모두 Kafka 를 전제한다. 이력 토픽은 검색 색인의 유일한 정본 사본인데 복제본이 하나뿐이다.
- 검토한 대안: 도메인별로 서로 독립된 단일 KRaft 3대. 한 대가 죽으면 그 도메인 토픽이 멈추고, 여러 도메인을 쓰는 서비스는 다중 바인더가 필요하다. 사용자가 검토 후 버렸다(2026-11-05).
- 개발 단계이고 운영 트래픽이 없다. 기존 이벤트는 시험 이벤트라 버려도 되고, 이력 토픽 3개만 백업·복원한다.

## 결정
1. **클러스터 1개, 노드 3개, 노드마다 broker+controller(combined)**:
   - kafka-1 = pc2(node 1), kafka-2 = pc3(node 2), kafka-3 = pc4(node 3).
   - 투표자 `1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094`.
   - 리스너 `PLAINTEXT://0.0.0.0:9092`, `CONTROLLER://0.0.0.0:9094`. 광고 주소 `PLAINTEXT://kafka-N:9092`. 다른 PC 의 노드 이름은 `extra_hosts` 로 푼다(지금 방식과 같음).
   - **컨트롤러 포트는 9094**: 관례인 9093 은 pc4 에서 Alertmanager 가 호스트 포트로 쓰고 있다(`render.sh` 356줄). 세 노드가 같은 포트를 쓰도록 클러스터 전체를 9094 로 맞춘다. 호스트에 9092·9094 를 공개한다.
2. **클러스터 ID 고정**: 한 번 만든 값(base64url 16바이트)을 `render.sh` 에 상수로 두고 세 노드에 같은 `CLUSTER_ID` 로 준다. 비밀이 아니고, 볼륨을 만든 뒤 바뀌면 노드가 뜨지 않으므로 `.secrets`(분실·재생성 가능)가 아닌 저장소에 둔다.
   - 데이터 볼륨 이름은 `kafkacluster` 로 새로 쓴다. 단일 노드 때의 `kafkadata` 는 롤백용으로 남는다.
3. **복제**:
   - 모든 토픽 replication factor 3, `min.insync.replicas=2`. 발행 측 `acks=all`(Kafka 클라이언트 기본값)과 함께 한 노드가 죽어도 쓰기가 계속되고 확인된 쓰기는 두 노드 이상에 있다.
   - 내부 토픽도 같다: `__consumer_offsets` 복제 3, 트랜잭션 상태 로그 복제 3·min ISR 2, Debezium Connect 내부 토픽(config·offset·status) 복제 3.
4. **브로커 자동 토픽 생성 끔**(`auto.create.topics.enable=false`) — 토픽은 만드는 주체가 명시적으로 만든다:
   - **특수 설정 토픽**(이력 토픽 무제한 보관, `search-index.DLT` 30일)은 `deploy/lan/kafka/topics-init.sh`(ADR-0034 결정 2 의 멱등 방식, 복제 3).
   - **업무 토픽·heartbeat 토픽**은 Debezium Connect 가 AdminClient 로 만든다: 커넥터 설정 `topic.creation.default.replication.factor=-1`·`partitions=-1`(브로커 기본값 = 복제 3·파티션 1, 로컬 단일 브로커에서는 1). outbox 의 목적지 토픽은 행의 `topic` 값으로 정해지므로(EventRouter) 목록으로 미리 관리하지 않는다.
   - **`{topic}.DLT`·Config Bus 토픽**은 Spring Cloud Stream 바인더가 AdminClient 로 만든다(기본 `autoCreateTopics=true`, 복제 = 브로커 기본값 3).
   - 이유: 브로커 자동 생성은 소비자·생산자의 메타데이터 요청만으로 토픽이 생긴다(구독 이름 오타 등). 만드는 주체를 Connect·바인더·스크립트로 좁히고 복제·ISR 은 브로커 기본값으로 통일한다.
   - 처음에는 모든 토픽을 `topics-init.sh` 목록으로만 만들려 했으나, outbox 목적지가 동적이고 heartbeat 토픽이 커넥터마다 하나씩(29개) 있어 목록이 어긋나면 커넥터가 멈춘다. 그래서 위처럼 나눈다(2026-11-05 구현 중 변경).
5. **접속 주소**: 앱(`spring.kafka.bootstrap-servers`, 설정 저장소 `application-lan.yml`), Debezium Connect, Logstash, 운영 스크립트가 모두 `kafka-1:9092,kafka-2:9092,kafka-3:9092` 를 쓴다. 하나만 적지 않는다(그 노드가 꺼진 채 시작하면 붙지 못한다).
6. **감시**(ADR-0030 체계에 추가):
   - `kafka-exporter`(버전 고정)를 pc4 에 두고 Prometheus 가 수집한다.
   - 경보: 브로커 수 < 3, 복제 부족 파티션 > 0, 리더 없는 파티션 > 0, 활성 컨트롤러 없음.
   - ADR-0034 토픽 크기 측정(`topic-metrics.sh`)은 **리더 복제본 기준**으로 바꾼다. `kafka-log-dirs` 합계는 복제 3 이면 세 배라 증가 경보가 잘못 울린다.
   - `watch.sh` 는 세 노드를 각각 점검한다.
7. **전환은 새로 시작**: 이력 토픽 3개(`commerce.product.v1`·`commerce.pricing.v1`·`mfc.sku-handling.v1`)만 백업 후 새 클러스터에 복원한다. 소비 위치는 초기화한다. Debezium 복제 슬롯 29개는 지우고 커넥터 등록 때 새로 만든다. PostgreSQL 업무 데이터·outbox·publication 은 유지한다. 옛 Kafka 볼륨은 롤백용으로 남긴다. 절차는 계획 파일 5·6장.

## 대안
- **단일 노드 유지**: 가장 단순하지만 pc2 하나가 전체 이벤트 흐름의 단일 장애점이다.
- **도메인별 독립 단일 Kafka 3대**: 장애가 도메인에 갇히지만 그 도메인은 멈추고, 여러 도메인을 쓰는 서비스에 다중 바인더·여러 접속 주소가 필요하다. Bus·검색처럼 도메인을 가로지르는 흐름의 위치도 애매하다.
- **컨트롤러 전용 노드 분리**(3 controller + N broker): 운영 권장 구성이지만 PC 가 세 대뿐이고 컨테이너·메모리가 두 배다. 개발 LAN 에는 combined 로 충분하다. 운영(Helm)은 따로 정한다.
- **복제 3, min ISR 1**: 한 노드에만 쓰인 확인이 나갈 수 있어 그 노드를 잃으면 확인된 이벤트를 잃는다.
- **Alertmanager 호스트 포트를 옮기고 컨트롤러 9093 유지**: 북마크·Grafana·Prometheus 설정을 같이 바꿔야 한다. 컨트롤러 포트는 클러스터 안에서만 쓰므로 바꾸는 쪽의 영향이 작다.

## 결과
- 한 노드가 꺼져도 컨트롤러 투표(3 중 2)와 쓰기(ISR 2)가 계속된다. 두 노드가 꺼지면 멈춘다.
- 디스크 사용이 세 배가 된다(이력 토픽은 무제한 보관이므로 세 PC 모두 C: 여유를 감시한다).
- 노드당 메모리 상한은 지금 수준(1.3~1.5 GiB)이라 pc3·pc4 에 Kafka 메모리가 새로 든다. 세 PC Docker 사용량(9.0·7.9·10.2 / 23.5 GiB)으로 수용 가능하다.
- PC 사이 9092·9094 가 통해야 한다(Windows 방화벽 인바운드 규칙). 전환 전에 확인한다.
- 특수 설정이 필요한 토픽(보관 기간 등)을 추가할 때만 `topics-init.sh` 를 고친다. 그 밖의 토픽은 Connect·바인더가 복제 3 으로 만든다.
- 배포 순서가 바뀐다: `lan.sh deploy` 는 대상 PC 의 Kafka 노드를 모두 먼저 띄우고(한 노드만으로는 과반이 안 돼 healthy 가 되지 않는다) 기다린 뒤 토픽을 준비하고, 그다음 config-server·나머지를 올린다.
- 네트워크 단절로 한 PC 가 고립되면 그 PC 의 앱은 다른 노드에 닿지 못해 발행·소비가 멈출 수 있다(클러스터 자체는 다수 쪽에서 계속).
