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
# 서비스마다 커넥터 1개 (DB 1개 = 커넥터 1개 = 복제 슬롯 1개)
deploy/local/register-connector.sh order-service order_service
```

## 접속 정보 (로컬 전용)
| 구성 | 주소 |
|---|---|
| PostgreSQL | `localhost:5432`, user `pmart` / `pmart-local`, DB는 `init-db.sql` |
| Kafka | `localhost:9092` (컨테이너 내부 `kafka:19092`) |
| Kafka Connect REST | `http://localhost:8083` |
| Zipkin | `http://localhost:9411` |

서비스 `application-local.yml` 예:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/order_service
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
- 공통 마이그레이션 `V0_0_1__pmart_outbox_inbox.sql`(common-messaging)이 `outbox_event`·`inbox_event`·publication을 만든다. 커넥터는 테이블이 생긴 뒤 등록한다(`snapshot.mode=no_data`).
- 운영에서는 DB 사용자에 `REPLICATION` 권한, publication 생성 권한이 필요하다. 비밀정보는 Secret Manager.
- Schema Registry는 두지 않는다(2026-10-23). 계약 적합성은 발행 전 `EventSchemaValidator`(ENFORCE)로 보장하고, 소비자는 WARN 권장.
