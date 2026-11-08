# docs/architecture — 주제별 목차

이 폴더의 파일은 옮기지 않는다(CLAUDE.md·규칙·ai-assistant 색인 목록·다른 문서가 경로를 직접 쓴다). 새 문서를 더하면 이 목차의 맞는 주제에 한 줄 넣는다.

- **정본**: 설계의 기준이 되는 문서. 바뀌면 같은 커밋에서 고친다.
- **현재 구현**: 코드 기준 안내서(기준 커밋·날짜가 있다). 코드가 바뀌면 낡을 수 있다.
- **계획·초안**: 진행 중이거나 결정 전.
- **비교·참고**: 다른 프로젝트(Eum)나 사용자 메모와의 비교. 결정 문서가 아니다.

## 1. 전체 구조 (정본)
| 문서 | 내용 | 구분 |
|---|---|---|
| [service-map.md](service-map.md) | 서비스 49개의 책임 경계 — 소유·제공 API·발행/구독 이벤트·동기 의존·단계. **어느 서비스 책임인지 모르면 여기부터** | 정본 |
| [architecture-design.md](architecture-design.md) | 전체 구조·통신 패턴·사가 목록·단계 계획, 이벤트 봉투(§5.2) | 정본 |
| [events.md](events.md) | 이벤트 카탈로그(발행자 → 구독자) | 정본 |
| [tech-stack.md](tech-stack.md) | 기술 스택 표(실제 버전은 `gradle/libs.versions.toml`) | 정본 |
| [implementation-plan.md](implementation-plan.md) | 1단계(핵심 판매 루프) 업무 구현 계획, 결정 D1~D7 | 계획 |

## 2. 주문·결제
| 문서 | 내용 | 구분 |
|---|---|---|
| [payment-system-spec.md](payment-system-spec.md) | 결제 구현 명세 — 토스페이먼츠 × OMS, 결정·규칙·외부 API 사실 | 정본(결제 명세) |
| [payment-saga-patterns.md](payment-saga-patterns.md) | 결제 흐름의 패턴별 책임 — 사가 오케스트레이션·이벤트 코레오그래피·Outbox → 클래스 대응 | 현재 구현 |
| [order-full-flow-analysis.md](order-full-flow-analysis.md) | 주문 전체 흐름 그림 해설(상태값·타이머·키) | 현재 구현 |
| [toss-payment-eum-comparison.md](toss-payment-eum-comparison.md) | 토스 결제 처리 Eum vs pmart 시퀀스 비교 | 비교·참고 |

## 3. 상품
| 문서 | 내용 | 구분 |
|---|---|---|
| [product-registration-design.md](product-registration-design.md) | 임시 백오피스 상품 등록(개별·일괄) → 승인 → 상품·SKU·바코드 정본 | 초안 |
| [product-search.md](product-search.md) | **상품 검색 현재 구현 정리** — 색인 문서·색인 규칙·검색 API(띄어쓰기·초성·센터 취급)·필터·재색인(reindex.sh)·감시·남은 것 | 현재 구현 |
| [search-comparison.md](search-comparison.md) | 사용자 메모(검색 구현 7단계)와 현재 구현 대조 | 비교·참고 |

## 4. 플랫폼·관측
| 문서 | 내용 | 구분 |
|---|---|---|
| [platform-capabilities-code-map.md](platform-capabilities-code-map.md) | MSA 공통 기능 코드 지도 — 게이트웨이·디스커버리·설정·Resilience·모니터링·트레이싱·메시징·검색 | 현재 구현 |
| [trace-correlation-plan.md](trace-correlation-plan.md) | 사가 하나를 traceId·correlationId 로 끝까지 따라가기(작업 A·B·C) — 구현·LAN 확인 완료 | 현재 구현 |
| [observability-architecture.html](observability-architecture.html) · [.png](observability-architecture.png) | 관측 구성 한 장 그림(로그·지표·추적) | 현재 구현 |
| [elk-in-pmart.md](elk-in-pmart.md) | ELK 를 상품 검색·로그 모니터링 두 용도로 쓰는 방식, 같은 ES 를 쓰는 대가 | 현재 구현 |
| [../operations/lan-monitoring-and-backup.md](../operations/lan-monitoring-and-backup.md) | LAN 운영 설정 — watch.sh·Prometheus·경보 22개·Zipkin·로그 보존·Kafka 백업·메모리 상한 | 현재 구현 |
| [elasticsearch-metrics.md](elasticsearch-metrics.md) | Elasticsearch 지표 수집(es-exporter → Prometheus)·경보 | 현재 구현 |
| [debezium-usage-vs-eum.md](debezium-usage-vs-eum.md) | Debezium 사용 방식 — pmart(Outbox CDC) vs Eum(테이블 CDC 혼용) | 비교·참고 |

## 5. 그림 (`diagrams/`)
원본 `.mmd`(Mermaid) + `.svg`·`.png`, 영문판은 `.en.*`.
| 그림 | 쓰는 문서 |
|---|---|
| [order-full-flow](diagrams/order-full-flow.png) | order-full-flow-analysis, payment-saga-patterns |
| [order-event-topics](diagrams/order-event-topics.png) | order-full-flow-analysis, payment-saga-patterns |
| [order-event-pipeline](diagrams/order-event-pipeline.png) | order-full-flow-analysis, payment-saga-patterns |
| [payment-saga-patterns](diagrams/payment-saga-patterns.png) | payment-saga-patterns |
| [trace-correlation-flow](diagrams/trace-correlation-flow.png) | trace-correlation-plan, payment-saga-patterns |
| [trace-correlation-target](diagrams/trace-correlation-target.svg) | trace-correlation-plan |

## 이 폴더 밖의 관련 문서
- 결정 근거: `docs/adr/`(결정을 바꾸면 새 ADR)
- 업무 흐름·사가·보상: `docs/business/flows/`, 확정 정책 `docs/business/overview.md`
- 운영 절차·사고 기록: `docs/operations/`, LAN 배포 `deploy/lan/README.md`, 재색인 `deploy/lan/elastic/REINDEX.md`
- 클라이언트 요청·회신: `docs/handoff/clients/`, 시뮬레이터: `docs/handoff/simulator/`
