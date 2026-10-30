# ADR-0017 운영 PostgreSQL 은 업무 도메인별 DB 서버 5대로 나누고, 서비스별 논리 DB 를 그 안에 둔다

- 상태: 제안 (2026-10-30, 사용자 결정)
- 관련: ADR-0013(LAN 배포·운영 데이터 배치 미정), ADR-0005(payment-service), ADR-0009(Outbox·Debezium), `.claude/rules/service-architecture.md`, `docs/architecture/tech-stack.md`

## 용어
- **DB 서버**: PostgreSQL 서버 하나(PostgreSQL 용어로 database cluster, 인스턴스). 프로세스·포트·메모리·디스크·WAL·백업 단위다. 운영에서는 기본 서버 1대 + 대기 서버(복제본)로 구성한다.
- **논리 DB**: DB 서버 안의 `CREATE DATABASE` 단위. 서비스마다 하나(`product_service` 등). 다른 논리 DB 는 같은 접속에서 조회할 수 없다.

## 배경
- 서비스별 데이터 소유 원칙(CLAUDE.md, `service-architecture.md`)에 따라 DB 가 필요한 서비스마다 논리 DB 를 따로 둔다. 2026-10-30 현재 29개다(LAN `init-db.sql` 28개 + `cdc_inventory_service`).
- 같은 DB 서버의 논리 DB 들은 데이터는 분리되지만 CPU·메모리·디스크·`max_connections`·WAL 은 공유한다. 한 서비스의 부하·장애가 같은 서버의 다른 서비스로 번진다.
- ADR-0013 은 운영 PostgreSQL 배치를 `[팀 확인 필요]` 로 남겼다. LAN 은 PC 마다 DB 서버 하나(현재 3대)다.

## 결정
1. 운영 PostgreSQL 은 **DB 서버 5대**로 나눈다. 경계는 업무 도메인(Commerce·Payment·MFC/WMS·Delivery/TMS·BackOffice)을 따른다.

   | DB 서버 | 논리 DB | 개수 |
   |---|---|---|
   | ① 커머스·주문 | `order_service`, `order_orchestration_service`, `cart_service`, `customer_service`, `pricing_service`, `claim_service`, `storefront_service` | 7 |
   | ② 결제 | `payment_service` | 1 |
   | ③ 센터 | `mfc_inventory_service`, `mfc_fulfillment_service`, `mfc_inbound_service`, `mfc_location_service`, `mfc_quality_service`, `mfc_capacity_service`, `cdc_inventory_service` | 7 |
   | ④ 배송 | `delivery_serviceability_service`, `delivery_capacity_service`, `delivery_dispatch_service`, `delivery_execution_service`, `delivery_tracking_service`, `rider_service` | 6 |
   | ⑤ 플랫폼·백오피스 | `auth_service`, `approval_service`, `audit_service`, `notification_service`, `case_service`, `center_master_service`, `product_service`, `product_content_service` | 8 |

   DB 가 없는 서비스: `api-gateway`, `backoffice-bff`, `fulfillment-routing-service`, `search-service`.
2. 서비스 하나 = 논리 DB 하나는 그대로다. 서비스 간 JOIN·다른 논리 DB 접근은 같은 DB 서버 안이어도 금지다.
3. **DB 계정은 서비스마다 따로** 두고 자기 논리 DB 에만 권한을 준다. Kubernetes Secret 이름 규칙 `pmart-<service>-db`(ADR-0013)를 그대로 쓴다.
4. 각 DB 서버는 기본 + 대기 서버로 고가용성을 갖추고, 시점 복구(PITR) 백업을 켠다. ①·② 는 복구 목표를 가장 짧게 둔다.
5. 새 서비스가 생기면 이 표의 도메인 경계에 따라 DB 서버를 고르고, 이 ADR 의 표를 갱신한다(새 DB 서버를 늘리는 것은 새 ADR).
6. 개발 환경은 이 수에 맞추지 않는다. LAN 은 ADR-0013 대로 PC 마다 DB 서버 하나(현재 3대), 로컬은 하나다.

## 대안
- **1대**: 관리가 가장 쉽지만 한 서버의 부하·장애가 전체를 멈춘다. 주문과 센터 운영이 서로 영향을 준다.
- **3대(커머스·결제 / 센터·배송 / 플랫폼)**: 비용(고가용성 기준 노드 6대)이 적다. 결제 감사 범위와 센터·배송의 부하 시간대 차이를 나중에 분리해야 한다. 서비스별 논리 DB 라 나중에 쪼갤 수 있지만 데이터 이전·점검 시간이 든다.
- **서비스마다 DB 서버(29대)**: 격리는 가장 높지만 트래픽이 없는 단계에서 비용·운영 부담이 지나치다.
- **스키마로 분리(논리 DB 하나에 서비스별 스키마)**: 권한 설정을 놓치면 같은 접속에서 서비스 간 JOIN 이 가능해진다. 논리 DB 분리를 유지한다.

## 결과
- 고가용성 기준 최소 노드 10대. 백업·모니터링·파라미터·버전 업그레이드를 서버 5대 단위로 관리한다.
- ② 결제 서버는 논리 DB 가 하나뿐이라 자원 여유가 크다. 결제 보안 감사 범위와 접근 통제를 좁히는 대가로 받아들인다.
- Debezium(ADR-0009)은 논리 DB 마다 복제 슬롯을 만든다. Connect 가 멈추면 WAL 이 쌓이므로 DB 서버별 슬롯 지연·디스크를 감시한다.
- 커넥션: DB 서버별로 (서비스 레플리카 수 × 커넥션 풀 크기)의 합이 `max_connections` 를 넘지 않게 한다. 넘으면 PgBouncer 를 검토한다.
- 열린 항목 `[팀 확인 필요]`:
  - DB 서버 형태: 관리형(예: AWS RDS/Aurora)인지 Kubernetes 안에 둘지(ADR-0013 의 미정 항목과 같다).
  - `product_service`·`product_content_service` 의 위치: 쓰기는 백오피스(⑤), 조회 부하는 커머스(전시·검색, ①)에 몰린다. 우선 ⑤ 에 두고, 조회 부하가 문제면 ① 로 옮기거나 읽기 복제본을 둔다.
  - DB 서버별 사양·복구 목표(RPO/RTO) 수치.
- 별도 할 일: LAN 은 현재 모든 서비스가 같은 DB 계정(`pmart`)을 쓴다. 결정 3에 맞춰 서비스별 계정으로 바꾸는 일은 LAN 배포 작업 때 한다(배포는 사용자 요청 시에만).
