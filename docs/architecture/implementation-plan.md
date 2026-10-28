# 업무 구현 계획 (4단계) `[제안]`

버전 0.2 · 2026-10-28 · 상태: 진행 중(결정 D1~D5·D7 확정, D6 대기). 범위는 `architecture-design.md` §8 의 **1단계(핵심 판매 루프)**.

## 0. 출발점
- 1~3단계(빌드 뼈대 · 공통 라이브러리 · 32개 서비스 스캐폴딩 + 게이트웨이)는 끝났다(2026-10-23). LAN 3대 배포·감시도 돈다(ADR-0013).
- 업무 코드는 없다. 모든 작업은 계약 생성 인터페이스의 기본 구현(501)이고, 각 서비스 DB 는 `V1__init.sql`(`SELECT 1`) + 공통 outbox/inbox 뿐이다.
- 설계 정본: 계약 `contracts/openapi`·`contracts/events`, 흐름 `docs/business/flows/`(S1·S2 결제·이행, 취소 vs 인계, 반품·환불), 서비스 지도, ADR-0001~0013.

## 1. 방식
- **얇은 수직 슬라이스**: 한 흐름을 처음부터 끝까지 실제로 돌게 만든 뒤 다음으로 간다. 순서는 S1 사가가 필요로 하는 순서(§3).
- 슬라이스마다 같은 단계: 계약 확인(필요하면 계약 먼저 수정·lint) → Flyway `V2__…` 이후 테이블 → domain 규칙·단위 시험 → application(트랜잭션·Outbox) → api/infrastructure 어댑터 → 통합 시험(Testcontainers PostgreSQL·Kafka, 이웃 서비스는 WireMock) → 일지.
- **완료 기준(DoD)**: ① 대상 작업이 501 이 아니라 계약대로 동작 ② 업무 규칙 단위 시험(경계값 포함: 15,000원·30,000원·10,000g/10,001g·10분 등) ③ 통합 시험 통과(`:check`) ④ 발행 이벤트가 스키마 검증(ENFORCE)을 통과 ⑤ 일지·필요 시 흐름 문서 갱신 ⑥ 슬라이스 끝에 LAN 재배포 후 게이트웨이 경유 시나리오 1회(§5).
- 규칙(기존): 서비스 간은 API·이벤트만, 동기 호출 2 hop 이하, 정책값 미설정은 `CONFIG_REQUIRED`(0/무제한 대체 금지), 시각 UTC 저장·달력 판정 Asia/Seoul, 변경 명령은 `Idempotency-Key` + 결과 조회.

## 2. 슬라이스 0 — 공통 기반 (업무 구현 전에 한 번)
| 항목 | 내용 | 위치 |
|---|---|---|
| 요청 멱등 기록 | `Idempotency-Key` + 본문 해시 저장, 같은 키·같은 본문은 기존 응답 재생, 다른 본문은 `IDEMPOTENCY_CONFLICT`, 처리 중 재요청 처리. 표는 outbox 처럼 라이브러리 마이그레이션으로 | `libraries/common-web` (+ 공통 마이그레이션) |
| 서비스 간 호출 규약 | RestClient 기본(연결·응답 타임아웃 필수), 서비스 신원 토큰(`ServiceTokenProvider`) 부착, 시간 초과 = 결과 불명(업무 실패 아님) 예외 타입, Resilience4j 재시도는 조회만 | `libraries/common-web` 또는 `common-security` |
| 시험 도구 | 고객·운영자·서비스 토큰 발급 픽스처(시험용 서명키), 고정 시계(`SeoulClock` 확장) | `libraries/common-test` |
| 낙관적 잠금 규약 | `X-Expected-Version` ↔ 엔티티 `@Version`, 충돌 시 `VERSION_CONFLICT` | `libraries/common-web` |

`libraries/` 변경이므로 영향 서비스(32개 전부 — 자동 구성이라 새 표가 생김)를 확인·보고한다.

**상태(2026-10-28)**: 구현 — `RequestIdempotency` + `V0_0_2__pmart_request_record.sql`(common-messaging), `Downstream`·`HttpClientDefaultsPostProcessor`·`RequestHeaders.checkExpectedVersion`·`OptimisticLockAdvice`(common-web), `TestJwt`·`TestJwtConfiguration`(common-test). 사용 규약은 `.claude/rules/api-and-events.md`. 요청 기록 보존 기간은 운영 결정 대기(정리 작업은 이후).

## 3. 슬라이스 순서 (1단계)
| # | 슬라이스 | 서비스 | 끝나면 되는 것 | 주 근거 |
|---|---|---|---|---|
| 1 | **판매 기준정보** | approval(최소), product, pricing, center-master, delivery-serviceability | 승인된 SKU·판매상태·가격 버전·MFC 1곳·권역이 조회되고 `ApprovalDecided`→`ProductVersionPublished`/`PriceVersionEffective` 가 흐른다 | ADR-0001·0004·0007, BO-04·05·06·07 |
| 2 | **고객·장바구니** | auth(고객 로그인), customer(배송지·쇼핑 주소), cart | 로그인 고객이 주소를 정하고 장바구니에 담는다(구매 상한·판매 상태 검증) | Commerce 4~7장 |
| 3 | **주문 초안·견적** | order | 초안 생성·조회: 판매/상한/권역/중량 검증, pricing 견적, 배달팁 4,000/3,000/0, 봉투 100, 최소 15,000, 10kg 분할, 첫 구매 점유, 10분 동결, `purchaseIntentId` 멱등 | S1 (1), Commerce 8·10장, OMS |
| 4 | **자원 확보(S1 a~d)** | order-orchestration, mfc-inventory, mfc-capacity, delivery-capacity, mfc-inbound(초기 보유) | 결제 진입 시 재고 R·작업 여력·슬롯 확보와 실패 보상(이번 시도분만 해제), 10분 만료 | S1 b~d·1.3, ADR-0002·0003·0008 |
| 5 | **결제·확정(S1 f~j)** | payment(토스 어댑터), order, orchestration, cart | PG 승인→금액 대사→`OrderConfirmed`→자원 확정·장바구니 정리. 불명 1분×5→case, 승인 후 확정 실패 시 보상 취소 | S1 f~j, ADR-0005 |
| 6 | **이행(S2)** | fulfillment-routing(얇게), mfc-fulfillment, mfc-location, mfc-quality(최소) | 작업 생성·배정(수동)·피킹·포장·결품(`LineShortageReported`)·QR 인계 fence | S2, ADR-0006, MFC |
| 7 | **배송** | rider, delivery-dispatch, delivery-execution, delivery-tracking | 배차 제안·수락, 픽업, 전달 완료/실패, 주문 부분/전체 전달 | S2 §3, ADR-0007 |
| 8 | **취소·반품·부분취소** | claim, orchestration(S3), payment(부분취소) | 출고 전 취소 vs 인계 경합, 결품 PG 부분취소, 반품 접수 | cancel-vs-handover, return-exchange-refund |
| 9 | **운영 화면·사건** | backoffice-bff, case, audit, notification | 운영자 조회 뷰, 불명·대사 사건 상신, 감사 기록, 고객 알림 | ADR-0004 |

- 3번(주문 견적)은 확정 정책이 가장 많은 곳이라 1·2번과 겹쳐 진행할 수 있다(이웃은 WireMock). 단 LAN 시나리오는 1·2번 뒤.
- 4번 이후는 순서를 바꾸지 않는다(사가 단계 순서와 같음).
- storefront·search·product-content(전시·검색 기본)는 2번과 함께 조회 위주로 최소 구현.

## 4. 결정 (2026-10-28 사용자 위임: D6 제외 백엔드 제안대로)
| # | 질문 | 영향 슬라이스 | 결정 |
|---|---|---|---|
| D1 | 상품·가격·센터·권역 기준정보를 넣는 경로 | 1 | **결정** — approval 최소 구현(변경요청→단일 승인→`ApprovalDecided`)을 먼저 만들고 정식 경로로만 넣는다. 적재는 그 API 를 부르는 스크립트. ADR-0014 |
| D2 | MFC 초기 재고를 어떻게 넣는가 | 4 | **결정** — mfc-inbound 초기 보유 반입 → 검수 → mfc-inventory 전기. 조정 집행으로 적재하지 않음. ADR-0014 |
| D3 | 재고 예약 TTL(`Q-04`), 슬롯 08/09시 개시 | 4 | **잠정** — TTL 10분(흐름 문서 값, 설정값으로 둠). 슬롯 개시는 설정 미등록이면 `CONFIG_REQUIRED`. 업무 결정 나면 설정만 바꾼다 |
| D4 | 고객 결제 흐름(BR-01) | 5 | **결정** — 10/24 제안안(초안 `nextAction=PAY` 에 결제 진입 정보, 인증 결과는 payment 고객 명령, success/fail URL 서버 고정). 모바일 복귀 방식만 미정. 슬라이스 5 시작 때 계약 반영 |
| D5 | 토스페이먼츠 시험 환경 | 5 | **결정** — 통합 시험은 WireMock 고정, LAN 시나리오만 토스 테스트 키(비밀은 `.secrets/`) |
| D6 | 고객 소셜 로그인 제공자·방식 | 2 | **미정(사용자 결정 대기)** — 그때까지 시험용 토큰으로 진행, 로그인 API 는 결정 후 |
| D7 | BR-14 초안 브랜치의 `Quote` 필드 | 3 | **결정** — 3번은 현재 main 계약으로 구현, 초안이 합쳐지면 필드 추가 |

## 5. 검증·배포
- 서비스 단위: `./gradlew :services:<name>:check` (단위 + Testcontainers 통합). 바뀐 라이브러리 소비자도 함께.
- 슬라이스 끝: 바뀐 서비스만 Jib push → digest → `deploy/lan/lan.sh render && deploy` → 게이트웨이 경유 시나리오(예: 3번 끝 "로그인 → 장바구니 → 초안 생성 → 견적 확인") → `watch.sh` 전부 OK·커넥터 RUNNING. LAN 배포는 매번 사용자 요청으로만 한다(절대 규칙).
- 시나리오 스크립트는 `deploy/lan/scenarios/` 에 남겨 다음 슬라이스에서 회귀로 다시 돌린다.

## 6. 하지 않는 것 (1단계 밖)
promotion·쿠폰, review, supplier·procurement, demand-planning(수동 입력만), cdc-*, transport-*, fleet, cold-chain, ai-assistant, 다중 MFC 라우팅, AI 배차.
