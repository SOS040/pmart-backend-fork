# 로컬 개발 인프라 (deploy/local)

Debezium 기반 Outbox 발행을 로컬에서 그대로 재현한다. 애플리케이션에는 폴링 릴레이가 없다.

```
서비스(@Transactional 업무 변경 + outbox_event INSERT)
   └─ PostgreSQL WAL (wal_level=logical, publication pmart_outbox)
        └─ Kafka Connect(Debezium PostgreSQL 커넥터, pgoutput)
             └─ Outbox Event Router SMT → Kafka 토픽 {domain}.{aggregate}.v{n}
                  (key = aggregateid, value = 봉투 JSON, headers = eventType/aggregateType/traceId/correlationId)
```

## 시작
```bash
docker compose -f deploy/local/docker-compose.yml up -d
```
서비스마다 커넥터 1개(DB 1개 = 커넥터 1개 = 복제 슬롯 1개). **등록은 자동**: `connector-registrar` 컨테이너(`connector-registrar.sh`)가 10초마다 서비스 DB 를 훑어, 서비스가 처음 기동해 Flyway 가 publication `pmart_outbox` 를 만든 DB 에 `outbox-<service>` 커넥터를 만든다(이미 있으면 그대로). 서비스 목록은 DB 이름 규칙(`order_service` → `order-service`)에서 얻는다.

```bash
docker compose -f deploy/local/docker-compose.yml logs -f connector-registrar   # created outbox-... 확인
# 커넥터 설정(템플릿)을 바꿨을 때만 수동 갱신(PUT)
deploy/local/register-connector.sh order-service order_service
```

## 접속 정보 (로컬 전용)
| 구성 | 주소 |
|---|---|
| PostgreSQL | `localhost:15432`(호스트 PostgreSQL 5432와 충돌 방지), user `pmart` / `pmart-local`, DB는 `init-db.sql` |
| Kafka | `localhost:9092` (컨테이너 내부 `kafka:19092`) |
| Kafka Connect REST | `http://localhost:8083` |
| Redis | `localhost:6379` (delivery-tracking-service 캐시) |
| Zipkin | `http://localhost:9411` |
| S3(SeaweedFS `mini`) | `http://localhost:8333`(127.0.0.1 만), path-style, 리전 `us-east-1`, 버킷 `pmart-product-content`(비공개), 키는 로컬 전용 기본값(`S3_ACCESS_KEY`/`S3_SECRET_KEY` 로 변경) |

product-content-service 는 local 프로파일에서 `http://localhost:8333`·`pmart-product-content` 를 쓰고, 자격 증명은 compose 와 같은 로컬 기본값을 쓰고, 바꿨으면 `PMART_S3_ACCESS_KEY`/`PMART_S3_SECRET_KEY` 로 덮어쓴다.

서비스 `application-local.yml` 예:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/order_service
    username: pmart
    password: pmart-local
  kafka:
    bootstrap-servers: localhost:9092
pmart:
  messaging:
    domain: order
```

## 확인
```bash
# 커넥터 상태
curl -s localhost:8083/connectors/outbox-order-service/status
# 토픽 소비
docker compose -f deploy/local/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic order.order.v1 --from-beginning --property print.headers=true
```

## 주의
- 공통 마이그레이션 `V0_0_1__pmart_outbox_inbox.sql`(common-messaging)이 `outbox_event`·`inbox_event`·publication을 만든다. 커넥터는 publication 이 생긴 뒤에만 등록된다(`snapshot.mode=no_data`, `publication.autocreate.mode=disabled`).
- `init-db.sql` 은 볼륨이 처음 만들어질 때만 실행된다. 서비스 DB 가 추가된 뒤 기존 볼륨을 쓰고 있다면 `docker compose ... down -v` 로 새로 만들거나 `CREATE DATABASE <db>` 를 직접 실행한다.
- 운영에서는 DB 사용자에 `REPLICATION` 권한, publication 생성 권한이 필요하다. 비밀정보는 Secret Manager.
- Schema Registry는 두지 않는다(2026-10-23). 계약 적합성은 발행 전 `EventSchemaValidator`(ENFORCE)로 보장하고, 소비자는 WARN 권장.
