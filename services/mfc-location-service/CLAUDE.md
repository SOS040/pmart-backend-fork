# mfc-location-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-location-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-location-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.location.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.location.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-location-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-location-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8304.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 위치 마스터(ADR-0040 2단계, 2026-11-07, 브랜치 `mfc-v2`): V2 `location`(불변 location_id, 센터 안 코드 유일 — 문법 Q-03 미결이라 자유 문자열)·`location_state_change`. 등록·속성 변경(코드·센터 불변, X-Expected-Version)·상태 전이(ACTIVE↔CLOSED·COUNT_LOCKED, RETIRED 는 DELETE)·목록(코드 커서)·적치 후보(온도대 필수, ACTIVE STORAGE/PICKING, 동선 순서, 공간 V 는 null). 쓰기는 그 센터 운영자, 읽기는 그 센터 운영자 또는 서비스 토큰. 바뀔 때마다 같은 트랜잭션에서 `MfcLocationStateChanged`(전체 속성, 토픽 `mfc.location.v1`) — 구독 mfc-inventory 투영. `POST /internal/location-republications[?mfcId]` 는 같은 버전으로 다시 알림(최대 20000). 폐지(DELETE)는 inventory 위치 잔량 조회가 생길 때까지 501.
- **위치 코드 문법(ADR-0042, Q-03 결정 2026-11-07)**: 새 등록만 `LocationCodes` 로 검사 — 선반·피킹 `{A/C/F}-{구역 영문1+숫자1}-{랙 2}-{단 1~9}-{칸 2}`(온도대 글자 = temperatureZone, 구역 = areaCode, 없으면 코드에서 채움), 그 밖 `{RCV/PAK/QRT/DSP/RTN/DSL}-{A/C/F}-{번호 2}`. 어기면 400 `LOCATION_CODE_INVALID`. 옛 자유 형식 코드는 그대로 쓰고(속성 변경 때 문법 검사 안 함), 새 문법 코드는 속성 변경 때도 온도대·구역 일치 검사.
