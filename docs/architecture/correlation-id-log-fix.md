# 요청 correlationId 가 로그에서 사라지던 문제와 수정 (2026-11-05)

- 상태: 코드·시험 완료, **LAN 미배포**. 공통 라이브러리 `common-observability` 변경이라 모든 서비스를 다시 빌드·배포해야 반영된다.
- 관련 문서:
  - 추적 전체: [`trace-gateway-checkout-payment.md`](trace-gateway-checkout-payment.md) 2절
  - traceId 와 sagaId: [`trace-id-and-saga-id.md`](trace-id-and-saga-id.md)
  - 계획과 작업 A·B·C: [`trace-correlation-plan.md`](trace-correlation-plan.md)

## 1. 증상

- 사가가 아닌 요청(조회, 결제 승인 앞부분, 결제 진입의 사가 시작 전 등)의 로그에 `correlationId` 가 없었다.
  - 요청 필터(`CorrelationIdFilter`)가 요청마다 값을 정하는데도 그랬다. 값은 `X-Correlation-Id` 헤더가 있으면 그 값, 없으면 새 UUID 다.
  - 그 값은 응답 헤더에만 남았다.
- LAN 근거:
  - 11-04 결제의 payment `payment pay-… APPROVED` 로그에 `traceId` 는 있고 `correlationId` 는 없었다.
  - 이틀치 로그 중 `correlationId` 가 있는 것은 사가 구간(`CorrelationScope`)과 Kafka 소비 로그뿐이었다.
- 영향:
  - 사가가 아닌 흐름은 traceId 로만 찾을 수 있었다.
  - 그 흐름에서 발행한 사건 봉투의 `correlationId` 도 비어서 나갈 수 있었다. 봉투는 발행할 때 MDC 값을 가져가기 때문이다.

## 2. 원인 (시험으로 확인)

- `correlationId` 가 추적 라이브러리(Micrometer Tracing + OpenTelemetry)의 **baggage → MDC 연동 필드**로 등록돼 있었다.
  - 설정: `management.tracing.baggage.correlation.fields: correlationId,X-Source-System` — 공통 라이브러리 기본값
- 연동 필드는 추적 라이브러리가 관리한다. span 범위를 열고 닫을 때마다 MDC 값을 그 span 의 baggage 값으로 맞춘다.
- 요청 필터는 값을 MDC 에만 넣었다.
  - 요청 안에서 하위 span(DB·HTTP 호출 관측 등)이 하나라도 끝나면, 라이브러리가 MDC 를 baggage 값(없음)으로 되돌린다.
  - 그 순간 값이 로그에서 사라졌다.

```
요청 시작   필터: MDC correlationId = corr-abc        ← 로그에 찍힘
하위 span   열림 → 닫힘: 추적 라이브러리가 MDC 를 baggage 값으로 되돌림(baggage 에 없음)
그 뒤 로그  MDC correlationId = (없음)                  ← 사라짐
```

- 재현 시험 결과(수정 전): 바깥 범위 `corr-abc-1`, 하위 span 안 `corr-abc-1`, **하위 span 이 끝난 뒤 `null`**.

## 3. 먼저 시도했다가 되돌린 방법 — 요청 값을 baggage 에도 넣기

- 필터가 값을 MDC 와 baggage 둘 다에 넣게 했다(`CorrelationScope`). 사가가 아닌 요청에서는 로그에 남았다.
- 그러나 전체 `./gradlew check` 에서 order-orchestration `CheckoutSagaIT` 가 실패했다. 사가 참여자 호출의 `X-Correlation-Id` 가 sagaId 가 아니라 요청 UUID 였다.
- 이유:
  1. 사가 진행은 `CorrelationScope` 로 MDC 와 baggage 를 sagaId 로 연다.
  2. 그런데 참여자 HTTP 호출의 하위 span 은 **요청 span 의 문맥(요청 값이 든 baggage)을 물려받아** 만들어진다.
  3. 그 span 이 열릴 때 라이브러리가 MDC 를 요청 값으로 덮었다.
  4. 공통 RestClient 는 MDC 에서 헤더 값을 읽으므로 요청 UUID 가 나갔다.
- 사가 추적(작업 C)을 깨뜨리므로 되돌렸다.

## 4. 최종 수정

| 파일 | 변경 |
|---|---|
| `libraries/common-observability/src/main/java/com/pmart/common/observability/ObservabilityDefaultsPostProcessor.java` | `management.tracing.baggage.correlation.fields` 를 `X-Source-System` 만으로 바꿈(correlationId 를 뺌). 이유를 주석으로 남김 |
| `libraries/common-observability/src/test/java/com/pmart/common/observability/CorrelationIdTracingTest.java` | 새 시험(아래 5장) |
| `libraries/common-observability/build.gradle.kts` | 시험 의존성 2개(MockMvc `spring-boot-starter-webmvc-test`, 추적 자동 구성 `spring-boot-micrometer-tracing-opentelemetry`). 운영 의존성은 그대로 |

- 이제 MDC `correlationId` 는 **추적 라이브러리가 건드리지 않고 우리 코드만** 관리한다.

| 구간 | 누가 MDC 에 넣나 | 값 |
|---|---|---|
| HTTP 요청 | `CorrelationIdFilter` | `X-Correlation-Id` 헤더 값 또는 새 UUID |
| 사가·결제 처리 | `CorrelationScope.open(...)`(order-orchestration, payment) | sagaId(취소는 claimId). 끝나면 요청 값으로 돌아옴 |
| Kafka 소비 | `EventConsumers` | 사건 봉투의 `correlationId` |
| 다른 스레드로 넘길 때 | `MdcContextPropagation` + `ContextExecutorService.wrap` | 넘기기 전 스레드의 값 |

- 원격 전파는 바뀌지 않았다.
  - 다른 서비스로 부를 때 `X-Correlation-Id` 헤더: 공통 RestClient 가 MDC 에서 읽음
  - baggage 원격 필드(`remote-fields: correlationId,X-Source-System`)
  - 사건 봉투·Kafka 헤더 `correlationId`
- **요청 값은 baggage 에 넣지 않는다.** 다른 서비스로는 `X-Correlation-Id` 헤더로 간다. 사가 구간만 `CorrelationScope` 가 baggage 에도 sagaId 를 넣는다(전과 같음).

## 5. 시험

| 시험 | 확인한 것 | 결과 |
|---|---|---|
| `CorrelationIdTracingTest`(MockMvc + 실제 추적) | ① 헤더로 받은 값이 하위 span 전후로 유지 ② 생성한 값도 유지 ③ 사가 범위 안에서는 하위 span 전후 sagaId, 범위를 닫으면 요청 값으로 복귀 ④ 요청 뒤 MDC 에 남지 않음 | 4개 통과 |
| common-observability 전체 | 기존 시험 포함 | 18개 통과 |
| order-orchestration 통합(`CheckoutSagaIT` 포함) | 참여자 호출 `X-Correlation-Id` = sagaId, 사가 사건 봉투 = sagaId | 15개 통과 |
| payment 통합(`approvalLogsCarryTheAttemptSagaId` 포함) | 결제 승인 로그 = 결제 시도의 sagaId | 9개 통과 |
| 전체 `./gradlew check --continue` | 모든 서비스의 단위·통합 시험 | 첫 실행 통과(11분 42초) |

- 문제 재현:
  - "하위 span 뒤 사라짐"은 수정 전 설정에서 첫 시험이 실패해 확인했다.
  - "사가 값 덮어쓰기"는 3장의 `CheckoutSagaIT` 실패로 확인했다.
- 최종 수정 뒤 새 시험을 수정 전 설정으로 되돌려 다시 돌려 보지는 않았다.

## 6. 바뀌는 것 (배포 뒤)

| 로그 | 지금(LAN) | 배포 뒤 |
|---|---|---|
| 사가가 아닌 요청(조회·검색·로그인 등) | correlationId 없음 | 요청 값(UUID 또는 받은 헤더 값) |
| 결제 진입의 사가 시작 전(order-service) | 없음 | 요청 값 |
| 결제 승인의 결제 시도 조회 전(payment) | 없음 | 요청 값 → 조회 뒤 sagaId |
| 사가 구간·Kafka 소비 | sagaId·봉투 값 | 같음 |
| api-gateway | 없음 | 없음(WebFlux 라 이 필터가 돌지 않음) |
| 사가가 아닌 흐름의 사건 봉투 | 비어 나갈 수 있음 | 발행한 요청의 값(코드 구조로 본 판단, 시험으로는 확인 안 함) |

## 7. 남은 것

- **LAN 반영**: 모든 서비스 이미지를 다시 빌드·push 하고 전체 배포. 배포 뒤 LAN 로그에서 사가가 아닌 요청의 correlationId 를 확인한다.
- **확인 안 함**:
  - 사가가 아닌 요청에서 다른 서비스로 나가는 `X-Correlation-Id` 헤더 값
  - 사건 봉투 값
- **운영 전 과제**(따로 기록): 외부 요청의 `X-Correlation-Id`·`baggage`·`traceparent` 는 게이트웨이에서 정리한다. `trace-gateway-checkout-payment.md` 5절.
