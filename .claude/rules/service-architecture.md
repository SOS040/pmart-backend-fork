---
paths:
  - "services/**/src/main/**"
  - "platform-services/**/src/main/**"
  - "**/db/migration/**"
---

# 서비스 구조·데이터·트랜잭션

## 패키지 레이어 (`com.pmart.<domain>`)
- `api`: HTTP 요청·응답, 기본 입력 검증
- `application`: 유스케이스, 트랜잭션, 명령 조정
- `domain`: 업무 규칙, 상태 전이, 불변조건
- `infrastructure`: DB, Kafka, 외부 API 어댑터

- Controller에 업무 규칙을 작성하지 않는다.
- 외부 API 응답 모델을 도메인 모델로 직접 쓰지 않는다.
- 다른 서비스의 내부 구현을 복제해 별도 정본을 만들지 않는다.

## 데이터 소유
- 각 서비스는 자기 데이터만 변경한다.
- DB 인스턴스를 공유해도 서비스별 DB 또는 스키마·계정 분리.
- 서비스 간 DB JOIN, FK, 직접 테이블 접근 금지.
- 영속성: 일반 엔티티는 Spring Data JPA, 명시적 SQL이 필요하면 JDBC.

## Migration (Flyway)
- 서비스별로 변경 이력 관리.
- 운영 환경에서 Hibernate 자동 스키마 변경 금지.
- 구버전·신버전 공존을 고려한 호환 마이그레이션 (expand → contract).
- 이미 적용된 migration 파일은 수정하지 않는다.

## 일관성
- Redis를 주문·재고·결제의 최종 원장으로 쓰지 않는다 (캐시·임시 데이터·위치 최신값 용도).
- 재고 원장과 예약은 해당 Inventory 서비스가 확정한다.
- 행 잠금·조건부 갱신·버전 검증은 경합 조건에 맞게 선택한다.
- 다중 서비스 거래는 Saga·보상·원결과 조회로 처리한다. 2PC 금지. 주문·취소·시간변경 사가는 `order-orchestration-service`가 조정하고, 확정 사실의 전파는 이벤트(choreography)로 한다 (ADR-0003).
- 보상은 이번 시도에서 새로 확보한 자원만 해제한다. 기존 유효 점유·이미 움직인 실물·과거 출발·인계는 되돌리지 않는다.
- 새 saga를 만들거나 보상 단계를 바꾸면 `docs/business/flows/`를 같은 커밋에서 갱신한다. 서비스 책임이 바뀌면 `docs/architecture/service-map.md`도 갱신한다.
- 정책값·승인선·창이 미설정이면 0/무제한/정상으로 대체하지 않는다. 해당 신규 실행만 CONFIG_REQUIRED로 보류한다.
- 외부 PG 호출을 DB 트랜잭션으로 원자화했다고 가정하지 않는다.
