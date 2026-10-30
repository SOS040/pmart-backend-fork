# 결제(Payment) 시스템 구현 명세 — 토스페이먼츠 × OMS

> **이 문서의 독자는 결제 시스템을 구현할 Claude Code(또는 개발자)입니다.**
> 실제 프로젝트에서 동작하는 결제 시스템을 구현하는 데 필요한 결정·규칙·외부 API 사실을 모두 담았습니다.
> 결제 외 업무(가격 계산, 재고, 슬롯, 쿠폰, 배송, 클레임 정책)는 **기존 프로젝트의 OMS 설계가 정본**이며, 이 문서는 그 설계에 결제를 끼워 넣는 방법만 정합니다.
>
> 작성일: 2026-10-30 · 근거: 토스페이먼츠 공식 문서·Flutter SDK 2.3.0 소스, `OMS-business-logic.md`(실제 프로젝트), 이 저장소의 결제 사전 테스트 결과
>
> **pmart-backend 메모(2026-10-30)**: 외부에서 받은 결제 구현 입력이다. 이 저장소에서는 사용자 결정으로 결제를 독립 서비스 `payment-service`(ADR-0005)로 두고 웹훅은 기존 계약대로 하며, 미결정 사항(§12)은 구현하지 않았다. 이 명세를 어떻게 옮겼는지는 ADR-0020. 키는 `deploy/lan/.secrets/toss-*`(Git 제외).

---

## 0. 구현자(Claude Code)에게 — 먼저 읽을 것

### 0.1 문서 우선순위
1. **실제 프로젝트의 기존 코드와 `OMS-business-logic.md`** (이하 "OMS 문서", `§`는 그 문서의 절 번호)
   - 금액, 정책, 상태 소유권, 시간 기준은 모두 OMS 문서를 따른다.
2. **이 문서**
   - 결제 연동 방식, ID 규칙, 토스 API 사용법, 결제 상태
3. **토스페이먼츠 공식 문서**
   - 외부 API 동작. 이 문서 §7에 확인된 내용만 정리했다.

**위 셋이 서로 다르면 구현을 멈추고 사용자에게 보고한다.** 임의로 한쪽을 골라 구현하지 않는다.

### 0.2 구현 전에 반드시 할 일 (기존 프로젝트 조사)
아래를 먼저 조사해서 사용자에게 요약 보고한 뒤 구현을 시작한다. **새 프레임워크, DB, 메시징 도구를 도입하지 않고 기존 것을 따른다.**

- [ ] 언어·프레임워크, 모듈 구조, 계층 규칙(컨트롤러/서비스/리포지토리 등)
- [ ] DB 종류, 마이그레이션 도구, 트랜잭션·잠금 방식, 기존 엔티티 명명 규칙
- [ ] OMS 주문 초안·주문 원장·주문상품 모델이 이미 있는지, 위치는 어디인지 (OMS 문서 A.1.2)
- [ ] **OMS 금액 확정(청구액 계산)** 코드 위치 (OMS §5, A.1.3). 결제 모듈은 이 결과를 **사용만** 한다.
- [ ] 재고 예약(WMS 연계), 슬롯 점유(MFC 연계), 쿠폰·행사 점유 코드와 각각의 결과 표현 방식 (OMS §5.4, §6)
- [ ] 기존 멱등 처리 방식(요청 식별자 저장, 중복 요청 응답) (OMS A.1.1)
- [ ] 비동기 작업·스케줄러·재시도 방식 (OMS A.1.15)
- [ ] 설정·비밀값 관리 방식(환경변수, 비밀 저장소), 환경 구분(로컬/테스트/운영)
- [ ] API 스타일(REST 경로 규칙, 오류 응답 형식), 인증·권한 방식
- [ ] Commerce 앱(Flutter) 프로젝트 위치와 구조, 상태 관리 방식
- [ ] 이미 존재하는 결제 관련 코드가 있는지

### 0.3 금지 사항
- 시크릿 키를 앱 코드, 앱 빌드 설정, 로그, 응답, 문서, 커밋에 넣지 않는다.
- 운영 키(`live_`)를 테스트·로컬 환경에서 쓰지 않는다.
- **이 문서 §7에 없는 토스 API, 파라미터, 응답 필드를 추측해서 쓰지 않는다.** 필요하면 공식 문서를 확인하고 사용자에게 보고한다.
- 결제 모듈 안에서 가격·할인·배달팁·봉투값을 다시 계산하지 않는다. OMS 확정 청구액만 쓴다.
- 결제창 결과(인증 성공)를 결제 완료로 처리하지 않는다.
- 타임아웃이나 응답 유실을 "실패"로 확정하지 않는다.
- 결과가 불명인 상태에서 새 결제, 새 `orderId`, 새 승인 요청을 만들지 않는다.
- §12의 미결정 사항을 임의로 정하지 않는다. §12에 적힌 **안전한 기본 동작**만 구현하고 결정 지점은 설정이나 TODO로 남긴다.

---

## 1. 범위와 소유권

| 영역 | 소유하는 사실 | 결제 시스템과의 관계 |
|---|---|---|
| **토스페이먼츠** | 실제 승인·취소 거래 (실거래 정본) | 결제 시스템이 서버에서 API를 호출한다 |
| **OMS 결제 모듈** (이번 구현 대상, OMS 문서 A.1.6 "결제 협업·PG 거래 대사") | 결제시도, 토스 호출 기록, 대사 결과 | OMS 내부 모듈이다. 주문 확정 조정(A.1.4)에 결제 사실을 제공한다 |
| OMS 기타 모듈 | 주문 원장, 금액 확정, 상태, 취소·클레임, 환불 금액 산정 | 결제 모듈을 호출하거나 결과를 받는다 |
| WMS / MFC Operations / Delivery | 재고 / 슬롯·현장 / 배송 | 결제 모듈이 직접 호출하지 않는다. OMS 조정 모듈을 통한다 |
| **Commerce 앱 (Flutter)** | 고객 화면 | 결제위젯 렌더링, 결제창 호출, 인증 결과를 OMS에 전달한다 |

**이번 구현 범위**
1. OMS 결제 모듈
   - 결제시도 저장
   - 승인
   - 결과 불명 조회
   - 전체·부분 취소 실행
   - 대사
   - 토스 API 클라이언트
2. Commerce 앱의 결제 화면 연동 (Flutter 결제위젯 SDK)
3. OMS 기존 모듈과의 연결 지점
   - 결제 진입 전 조건 확인
   - 승인 후 주문 확정 요청
   - 취소·환불 실행 요청 수신

**범위 밖**
- 가격 계산
- 재고·슬롯·쿠폰 점유 로직 자체 (조건 확인만 한다)
- 클레임 정책, 환불 금액 산정 (OMS §10, A.1.13이 산정한다. 결제 모듈은 산정된 금액을 실행한다)
- 웹훅 (§12 D1)

---

## 2. 확정된 결정 사항

| # | 결정 | 근거 |
|---|---|---|
| F1 | 토스페이먼츠 **주문서형** 연동 = **결제위젯** 키(`gck`/`gsk`)와 Flutter 결제위젯 SDK | OMS §7 `[확정 정책]`, 토스 API 키 문서 |
| F2 | 토스 `orderId`는 **OMS가 생성**한다. `orderId` = **OMS 결제시도 ID** (결제창 1회 진입 = 1개) | 팀 결정 (2026-10-30) |
| F3 | 결제 금액(`amount`)은 **OMS 확정 청구액**이다. 앱은 금액을 정하지 않는다 | OMS §5.1, A.1.3 |
| F4 | 결제 요청 **전에** 서버에 `orderId`와 `amount`를 저장한다 | 토스 결제 흐름 문서 |
| F5 | **승인은 서버가 토스 승인 API를 호출해야 일어난다.** 결제창 성공은 인증일 뿐이다 | 토스 결제 흐름 문서, OMS §7 |
| F6 | 최종 승인 성공은 승인 응답 **`status = DONE`** 일 때만 인정한다 | 토스 Payment 상태 정의 |
| F7 | **결제 건 ID 일관성 규칙(§3)을 모든 구간에 적용한다** | 사용자 요구 (가장 중요) |

---

## 3. ID 체계와 일관성 규칙 ★ 가장 중요

### 3.1 원칙
- 모든 구간에서 ID **형식**이 같을 필요는 없다.
- 그러나 **같은 결제 건을 가리키는 ID는 생성된 뒤 어느 구간에서도 바뀌지 않고 그대로 전달된다.**
- **받는 쪽은 그 값이 자기가 알고 있는 값과 같은지 반드시 확인한다.** 다르면 처리를 멈추고 불일치로 기록한다.

### 3.2 ID 목록

| ID | 의미 | 생성 주체 | 형식 | 저장 위치 | 같은 결제 건을 가리키나 |
|---|---|---|---|---|---|
| **`orderId`** | 토스 주문 구분 ID = **OMS 결제시도 ID** | **OMS** | 6~64자, `[A-Za-z0-9\-_=]`, 충분히 무작위하고 고유 | 결제시도의 **고유 키(유일 제약)** | **예 (기준 ID)** |
| **`paymentKey`** | 토스 결제 키 | 토스 (인증 후 발급) | 최대 200자. 형식·길이가 바뀔 수 있으므로 **가공하지 않음** | 결제시도 (`orderId`와 1:1, 유일 제약) | **예 (인증 이후의 짝 ID)** |
| OMS 주문 ID / 초안 ID | OMS 원장의 주문 | OMS | 기존 프로젝트 규칙 | OMS 주문 원장 | 아니오. 결제시도가 참조함 (주문 1 : 결제시도 N) |
| 결제 진입 요청 식별자 | 앱 요청의 멱등 키 | 앱 (OMS A.1.1 규칙) | 기존 프로젝트 규칙 | 결제시도 | 아니오. 재전송 판별용 |
| 승인 `Idempotency-Key` | 토스 승인 재시도 멱등키 | OMS | 최대 300자 (UUID 권장) | 결제시도 (시도당 1개) | 아니오. 토스 호출용 |
| 취소 요청 ID + 취소 `Idempotency-Key` | OMS 환불 실행 1건 | OMS | 기존 규칙 / 최대 300자 | 결제취소 레코드 | 아니오. 환불 1건 식별용 |
| `transactionKey` | 토스 취소 거래 1건 | 토스 | 최대 64자 | 결제취소 레코드 | 아니오. 취소 1건 식별용 |
| `customerKey` | 토스 SDK 고객 식별값 | §12 D6 참고 | 22~50자, `[A-Za-z0-9\-_=.@]`, 무작위 | 회원 정보 | 아니오. 고객 단위 |

**`orderId` 생성 규칙**
- OMS 내부 순번, 주문 ID, 시각만으로 만든 값을 그대로 쓰지 않는다. 충분히 무작위한 부분을 포함한다.
- 예시 형식(참고): `pay-<UUID에서 하이픈 제거한 32자>` → 36자.

### 3.3 구간별 전달과 확인

| # | 구간 | 전달하는 ID | 받는 쪽이 반드시 확인할 것 | 불일치 시 |
|---|---|---|---|---|
| 1 | 앱 → OMS: 결제 진입 | 주문 초안 참조, 결제 진입 요청 식별자 | 같은 요청 식별자의 재전송이면 **기존 결제시도와 같은 `orderId`를 반환**. 같은 식별자로 다른 초안이 오면 거절 | `409` 충돌 |
| 2 | OMS → 앱 | `orderId`, `amount`, `orderName`, `customerKey` | 앱은 이 `orderId`·`amount`를 **결제가 끝날 때까지 보관**한다 (R1) | — |
| 3 | 앱 → 토스 SDK | `requestPayment(orderId, orderName)`, 위젯 금액 = `amount` | 위젯 금액이 받은 `amount`와 같은지. 다르면 `updateAmount` 후 진행 | 결제창을 열지 않음 |
| 4 | 토스 → 앱: 성공 | `paymentKey`, `orderId`, `amount` | `orderId` == 보관값, `amount` == 보관값 (R2) | 승인 요청을 보내지 않고 OMS에 불일치 보고 |
| 4' | 토스 → 앱: 실패·취소 | `errorCode`, `errorMessage`, `orderId`(**빈 값일 수 있음**) | 실패·취소를 **보관한 `orderId`**로 OMS에 보고 (R1) | — |
| 5 | 앱 → OMS: 승인 요청 | `orderId`, `paymentKey`, `amount` **만** (R4) | ① `orderId`로 결제시도 조회 ② 이미 다른 `paymentKey`가 있으면 거절 ③ `amount` == 결제시도 금액 ④ 상태가 승인 가능 상태인지 | `404` / `409` / `400`. 토스를 호출하지 않음 |
| 6 | OMS → 토스: 승인 API | **결제시도에 저장된** `paymentKey`, `orderId`, `amount` | — | — |
| 7 | 토스 → OMS: 승인 응답 | 응답의 `paymentKey`, `orderId`, `totalAmount`, `currency`, `status` | `paymentKey`·`orderId` == 결제시도 값, `totalAmount` == 결제시도 금액, `currency` == `KRW`, `status` == `DONE` (R3) | 주문 확정 차단 → `MISMATCH_HOLD` |
| 8 | OMS → 앱: 결과 | `orderId` 기준 결과 | 앱은 보관한 `orderId`의 결과인지 확인 | 화면 갱신하지 않음 |
| 9 | OMS → 토스: 조회·취소 | `paymentKey` (경로), 취소별 `Idempotency-Key` | 응답의 `paymentKey`·`orderId` == 결제시도 값. 취소 결과는 `transactionKey`로 OMS 취소 레코드에 연결 (R3) | 종결 보류 → 대사 |

### 3.4 규칙 (MUST)

**R1. 앱의 `orderId` 보관**
- 앱은 OMS가 준 `orderId`를 결제가 끝날 때까지 보관하고 기준으로 쓴다.
- 토스 실패·취소 결과의 `orderId`는 빈 문자열일 수 있다. SDK 소스에서 확인했고, 토스 문서도 사용자 취소(`PAY_PROCESS_CANCELED`) 시 `orderId`를 넘기지 않는다고 한다.
- 성공 결과의 `orderId`는 보관값과 **비교하는 데만** 쓴다.

**R2. 인증 결과 대조**
- 인증 결과가 보관값과 다르면 앱은 승인 요청을 보내지 않는다.
- OMS도 5번 구간에서 다시 대조한다(이중 확인).

**R3. 토스 응답 대조**
- OMS는 토스의 모든 응답(승인·조회·취소)에 있는 `paymentKey`·`orderId`를 결제시도 저장값과 대조한다.
- 다르면 주문 확정이나 환불 완료로 진행하지 않고 대사 대상으로 둔다 (OMS §7 "금액·통화·주문 불일치", A.4.1 "대사 불일치").

**R4. 결제 건 지정은 `orderId` 하나로**
- 앱은 결제 건을 `orderId` 하나로만 가리킨다.
- 승인 요청에 OMS 주문 ID 등 다른 식별자를 섞어 보내지 않는다.
- OMS는 `orderId` → 결제시도 → 주문 초안/주문 순으로 찾는다.

**R5. `orderId` 저장 방식**
- OMS는 `orderId`를 결제시도의 고유 키로 **그 값 그대로** 저장한다.
- 별도의 결제시도 ID를 만들어 `orderId`와 매핑하지 않는다.
- `paymentKey`에도 유일 제약을 둔다.

**R6. 중간 계층 무가공**
- 앱과 OMS 사이의 중간 계층(Commerce 서버, 게이트웨이 등)은 `orderId`·`paymentKey`를 가공하지 않고 그대로 전달한다.
- 대소문자 변환, 트림, 인코딩 변경도 금지한다.

**R7. `orderId` 발급 제한**
- 같은 주문에 대해 결과가 불명인 결제시도(`CONFIRMING`, `CONFIRM_UNKNOWN`)가 있으면 새 `orderId`를 발급하지 않는다 (OMS §5.3, §7).

**R8. `paymentKey` 사전 저장**
- `paymentKey`는 5번 구간에서 받는 즉시, **승인 API를 호출하기 전에** 결제시도에 저장한다.
- 결과 불명 시 조회의 유일한 열쇠다 (§7.5).

---

## 4. 데이터 모델 (논리)

기존 프로젝트의 명명·타입 규칙으로 옮긴다. 아래는 의미와 제약만 정의한다.

### 4.1 PaymentAttempt (결제시도) — 결제창 진입 1회 = 1행
| 필드 | 의미 | 제약 |
|---|---|---|
| `order_id` | 토스 `orderId` = 결제시도 ID | **PK 또는 UNIQUE**, OMS 생성 (§3.2) |
| `oms_order_ref` | OMS 주문/초안 참조 | NOT NULL. 주문 1 : 시도 N |
| `entry_request_id` | 결제 진입 요청 식별자 | UNIQUE (주문 범위 등 기존 멱등 규칙에 맞춤) |
| `amount` | OMS 확정 청구액 (원, 정수) | NOT NULL, > 0 |
| `currency` | `KRW` | 고정 |
| `order_name` | 결제창 표시 주문명 | 1~100자 |
| `customer_key` | 토스 `customerKey` | — |
| `status` | §5 상태 | NOT NULL |
| `payment_key` | 토스 `paymentKey` | UNIQUE, NULL 허용 (인증 전). 받은 그대로 저장 |
| `confirm_idempotency_key` | 승인용 멱등키 | 시도 생성 시 1회 발급, 이후 불변 |
| `confirm_called_at` | 승인 API 최초 호출 시각 | NULL이면 **승인 API를 호출한 적이 없음** (§7.5 판정 근거) |
| `toss_status`, `method`, `approved_at`, `total_amount`, `balance_amount`, `is_partial_cancelable` | 토스 응답값 | 승인·조회 응답에서 갱신 |
| `fail_stage`, `fail_code`, `fail_message` | 실패 정보 | 토스 `{code, message}` 등 |
| `resource_expires_at` | OMS 재고 예약·슬롯 중 **가장 이른 만료** | 승인 직전 확인용 (§8) |
| `price_valid_until` | 가격 동결 만료 (OMS §5.3) | 결제 진입 시 확인 |
| `unknown_retry_count`, `next_check_at` | 결과 불명 재조회 일정 (§7.5) | — |
| `version` / 갱신 시각 | 낙관적 잠금 등 | 기존 방식 |

### 4.2 PaymentCancel (결제취소 실행) — 토스 취소 요청 1건 = 1행
| 필드 | 의미 | 제약 |
|---|---|---|
| `cancel_request_id` | OMS 취소·환불 실행 요청 ID | UNIQUE |
| `order_id` | 대상 결제시도 | FK |
| `cancel_amount` | 취소 금액 (원) | OMS 환불 산정 결과 (OMS §10) |
| `cancel_reason` | 취소 사유 | 1~200자 |
| `refundable_amount_sent` | 요청 시 보낸 `refundableAmount` | — |
| `idempotency_key` | 취소용 멱등키 | 요청당 1개, 불변 |
| `status` | `REQUESTED` / `UNKNOWN` / `DONE` / `FAILED` | — |
| `transaction_key`, `canceled_at` | 토스 취소 결과 | 성공 시 |
| `oms_claim_ref` | 부분 품절·반품·전체 취소 등 OMS 근거 참조 | NOT NULL |

### 4.3 PaymentEventLog (감사)
- 모든 토스 호출과 상태 전이를 기록한다: 요청 시각, 응답 시각, 대상 `order_id`, HTTP 상태, 토스 `code`, 이전·이후 상태, 처리 주체.
- **요청·응답 원문을 저장할 때 `Authorization` 헤더와 시크릿 키를 제외한다.** (OMS A.1.15)

---

## 5. 결제시도 상태 머신

주문 상태(OMS 소유)와 별개다. OMS §3은 결제 상태와 주문 상태의 분리를 요구한다.

| 상태 | 의미 | 진입 조건 | 다음 상태 |
|---|---|---|---|
| `READY` | 결제시도 생성, 결제창 진입 허용 | 결제 진입 API 성공 (§6.1) | `AUTH_FAILED`, `CANCELED_BY_USER`, `CONFIRMING`, `EXPIRED` |
| `AUTH_FAILED` | 결제창 인증 실패 | 앱 실패 보고 (§6.2) | 종결 |
| `CANCELED_BY_USER` | 결제창 사용자 취소 (`PAY_PROCESS_CANCELED`) | 앱 취소 보고 | 종결 |
| `CONFIRMING` | `paymentKey` 저장 완료, 승인 API 호출 중 | 승인 요청 검증 통과 (§6.3) | `APPROVED`, `CONFIRM_FAILED`, `CONFIRM_UNKNOWN`, `MISMATCH_HOLD` |
| `CONFIRM_UNKNOWN` | 승인 결과 불명 (타임아웃·응답 유실·409 처리 중) | §7.5 | `APPROVED`, `CONFIRM_FAILED`, `MISMATCH_HOLD`, `MANUAL_REVIEW` |
| `APPROVED` | **`status = DONE` + §3.3-7 대조 통과** | 승인 응답·조회 | (취소 시) `PARTIAL_CANCELED`, `CANCELED` |
| `CONFIRM_FAILED` | 승인 실패 확정 (토스 오류 응답, `ABORTED`) | — | 종결 |
| `EXPIRED` | 승인 API를 호출하지 않고 만료 | §8 | 종결 |
| `MISMATCH_HOLD` | ID·금액·통화 불일치, 또는 `DONE`이 아닌 승인 응답 | R3, F6 | `MANUAL_REVIEW` |
| `MANUAL_REVIEW` | 자동 복구 한도 초과, 담당자 확인 필요 | OMS §7, A.1.15 | 담당자 조치 |
| `PARTIAL_CANCELED` / `CANCELED` | 토스 취소 결과 반영 | §6.5 | — |

**전이 규칙**
- 상태 전이는 **조건부 갱신**으로 한 번만 일어나게 한다. 예: `UPDATE ... WHERE status = 'READY'`. 특히 `READY → CONFIRMING`은 동시 승인 요청 중 하나만 통과해야 한다.
- 종결 상태(`AUTH_FAILED`, `CANCELED_BY_USER`, `CONFIRM_FAILED`, `EXPIRED`)가 되면 OMS 조정 모듈에 알린다. OMS는 그때 재고·슬롯·쿠폰 점유를 해제한다 (OMS §7 "승인 실패 확정"). **결제 모듈이 직접 해제하지 않는다.**
- `APPROVED`가 되면 OMS 주문 확정 조정(A.1.4)에 알린다. 주문 확정 실패 시 보상 취소는 §6.5를 통해 요청받는다.
- `CONFIRM_UNKNOWN`·`MISMATCH_HOLD`·`MANUAL_REVIEW` 동안에는 점유를 해제하지 않고, 같은 주문의 새 결제시도를 만들지 않는다 (R7, OMS §7).

---

## 6. 인터페이스 (논리 계약)

경로, 메서드, 오류 형식은 기존 프로젝트 API 규칙을 따른다. 아래는 입력·처리·출력의 의미다.

### 6.1 결제 진입 — 앱 → OMS
- **입력**: 주문 초안 참조, 결제 진입 요청 식별자, 고객 인증 정보
- **처리**
  1. 요청 식별자 재전송 판별. 같은 식별자·같은 초안이면 기존 결제시도의 응답을 그대로 반환한다. 다른 초안이면 `409`.
  2. 같은 주문에 `CONFIRMING`/`CONFIRM_UNKNOWN`/`MISMATCH_HOLD` 결제시도가 있으면 거절한다 (R7).
  3. OMS 기존 모듈로 **PG 진입 조건**을 확인한다. 하나라도 아니면 거절하고 결제시도를 만들지 않는다.
     - 금액 확정과 가격 동결 유효 (§5.1, §5.3)
     - 쿠폰·행사 점유 성공 (§5.4)
     - 지정 MFC 전품목 재고 예약 성공 (§6)
     - 필요한 슬롯 점유 성공 (§6.1)
     - 운영 제한 없음 (§13)
  4. `orderId` 생성 (§3.2), 승인 `Idempotency-Key` 생성.
  5. 결제시도를 저장한다(`READY`): `amount` = OMS 확정 청구액, `resource_expires_at` = 예약·슬롯 중 가장 이른 만료.
  6. 저장이 끝난 뒤에 응답한다.
- **출력**: `orderId`, `amount`, `orderName`, `customerKey`, `resourceExpiresAt`(안내용)
- 가격 동결 만료와 예약 만료 중 **더 이른 시각**을 고객에게 안내할 수 있게 함께 내려준다 (OMS §5.3).

### 6.2 인증 실패·취소 보고 — 앱 → OMS
- **입력**: `orderId`(**앱 보관값**, R1), `errorCode`, `errorMessage`
- **처리**: `READY`인 결제시도만 바꾼다.
  - `PAY_PROCESS_CANCELED`면 `CANCELED_BY_USER`
  - 그 외는 `AUTH_FAILED`
  - 그다음 OMS에 알린다.
- 이미 다른 상태면 무시하고 현재 상태를 반환한다(멱등).
- 앱이 보고하지 못해도(앱 종료) §8 만료 처리로 정리된다.

### 6.3 승인 요청 — 앱 → OMS ★
- **입력**: `orderId`, `paymentKey`, `amount` (R4: 이 세 값만)
- **처리 순서** (순서 고정)
  1. 형식 검사: `orderId` 규칙, `paymentKey` 1~200자, `amount` 양의 정수
  2. `orderId`로 결제시도를 조회한다. 없으면 `404`.
  3. 이미 `paymentKey`가 저장돼 있고 요청값과 다르면 `409 PAYMENT_KEY_MISMATCH`.
  4. `amount` ≠ 결제시도 `amount`면 `400 AMOUNT_MISMATCH`. **토스를 호출하지 않는다.**
  5. 상태 확인
     - `APPROVED`면 기존 결과를 반환한다(중복 승인 요청).
     - `CONFIRMING`/`CONFIRM_UNKNOWN`이면 진행 중임을 반환한다.
     - 종결 상태면 거절한다.
  6. **승인 직전 자원 확인** (§8)
     - `now ≥ resource_expires_at`이거나, OMS에 물어 예약·슬롯이 유효하지 않으면 **승인 API를 호출하지 않는다.**
     - 결제시도를 `EXPIRED`로 바꾸고 OMS에 알린다. 앱에는 `RESERVATION_EXPIRED`를 반환한다.
  7. 조건부 갱신 `READY → CONFIRMING`과 함께 `payment_key`를 저장한다(R8). 실패하면(동시 요청) 현재 상태를 반환한다.
  8. 토스 승인 API를 호출한다 (§7.3). 첫 호출이면 `confirm_called_at`을 기록한다.
  9. 응답을 처리한다 (§7.4)
     - 성공 응답 → R3 대조 → `status = DONE`이면 `APPROVED`, 아니면 `MISMATCH_HOLD`
     - 오류 응답 → `CONFIRM_FAILED`. 단 409 `IDEMPOTENT_REQUEST_PROCESSING`은 `CONFIRM_UNKNOWN`
     - 타임아웃·연결 오류 → `CONFIRM_UNKNOWN` (§7.5)
  10. 결과를 앱에 반환하고, 상태에 따라 OMS에 알린다.
- **출력**: `orderId`, 결제시도 상태, (승인 시) `method`, `approvedAt`, `totalAmount`, (실패 시) 단계·코드·메시지. 시크릿 정보는 넣지 않는다.

### 6.4 결제 상태 조회 — 앱 → OMS
- **입력**: `orderId`
- **출력**: 6.3 출력과 같은 형식
- 앱이 응답을 놓쳤을 때 이걸로 확인한다. 승인 요청을 다시 보내지 않는다.

### 6.5 결제 취소 실행 — OMS 내부 (취소·클레임·보상 모듈 → 결제 모듈)
- **입력**
  - `cancel_request_id` (멱등)
  - `orderId`
  - `cancelAmount` (생략하면 전액, OMS 환불 산정 결과)
  - `cancelReason`
  - `oms_claim_ref`
- **처리**
  1. 같은 `cancel_request_id`가 있으면 기존 결과를 반환한다.
  2. 결제시도가 `APPROVED`/`PARTIAL_CANCELED`인지 확인한다.
  3. **환불 한도 검사** (OMS §10)
     - 요청액 ≤ 최초 승인액 − 성공한 취소 누계 − 진행 중(`REQUESTED`/`UNKNOWN`) 취소 금액
     - 넘으면 거절한다.
  4. 부분 취소인데 `is_partial_cancelable = false`면 토스를 호출하지 않고 거절한다 (§12 D2).
  5. `PaymentCancel`을 저장하고(`REQUESTED`) 취소 API를 호출한다 (§7.3).
     - `refundableAmount` = 결제 모듈이 알고 있는 토스 잔액(`balance_amount`)
  6. 응답을 처리한다.
     - 성공 → R3 대조, `cancels[]`에서 이번 취소의 `transactionKey` 저장, `balance_amount` 갱신 → `DONE`
     - 오류 → `FAILED`
     - 불명 → `UNKNOWN` (§7.5)
- 결제 모듈은 환불 **실행 결과**만 돌려준다. 고객 안내, 쿠폰 복원, 클레임 종결은 OMS가 한다 (OMS §10.1, A.1.13).

### 6.6 대사·재조회 작업 — 스케줄러
- `CONFIRM_UNKNOWN` / 취소 `UNKNOWN`: §7.5 일정으로 재조회
- `READY`이고 `resource_expires_at`이 지난 결제시도 → §8 만료 처리
- `APPROVED`인데 OMS 주문 확정이 일정 시간 안에 반영되지 않은 건 → OMS에 재통지 또는 `MANUAL_REVIEW` (OMS §7 "승인 성공·주문 저장 실패")

---

## 7. 토스페이먼츠 연동 (확인된 사실만)

### 7.1 키와 환경
| 키 | 형식 | 위치 |
|---|---|---|
| 클라이언트 키 | 테스트 `test_gck_…` / 운영 `live_gck_…` | 앱 빌드 설정 (환경별) |
| 시크릿 키 | 테스트 `test_gsk_…` / 운영 `live_gsk_…` | **서버 비밀 저장소·환경변수에만** |

- 클라이언트 키와 시크릿 키는 **같은 쌍**이어야 한다. 섞이면 `UNAUTHORIZED_KEY`/`INVALID_API_KEY`가 난다.
- API 개별 연동 키(`ck`/`sk`)와 섞지 않는다.
- 결제위젯 시크릿 키는 위젯 클라이언트 키로 만든 결제의 **승인·취소·조회**에 쓴다.
- 서버는 기동할 때 키 접두사를 검사한다. 테스트·로컬 환경에서는 `live_`면 기동을 거부한다.
- 키 값을 로그에 남기지 않는다. 마스킹한 앞부분이나 접두사 정도만 허용한다.

### 7.2 공통
- Base URL: `https://api.tosspayments.com`
- 인증 헤더: `Authorization: Basic base64("{시크릿키}:")` — **콜론 포함**
- `Content-Type: application/json`
- 멱등키: `Idempotency-Key: {값}`
  - 최대 300자
  - 키, API 키, URL, HTTP 메서드 조합으로 중복을 판별하고 15일간 유효하다
  - 처리 중에 재요청하면 `409 IDEMPOTENT_REQUEST_PROCESSING`
  - **멱등 요청이 오류를 반환하면 키를 바꿔 재시도하지 않는다.** 원인을 확인한다
- 타임아웃: 설정값으로 둔다 (참고: 사전 테스트에서는 15초를 썼다)

### 7.3 사용하는 API

| 용도 | 요청 | 비고 |
|---|---|---|
| **승인** | `POST /v1/payments/confirm`, body `{ "paymentKey", "orderId", "amount" }`, 헤더 `Idempotency-Key` = 결제시도 승인 키 | **인증 후 10분 안에** 호출해야 한다. 넘기면 `NOT_FOUND_PAYMENT_SESSION` |
| **조회** | `GET /v1/payments/{paymentKey}` | 결과 불명·대사용 |
| **취소** | `POST /v1/payments/{paymentKey}/cancel`, body `{ "cancelReason", "cancelAmount"?, "refundableAmount"? }`, 헤더 `Idempotency-Key` = 취소 요청 키 | `cancelReason` 필수(최대 200자). `cancelAmount`를 생략하면 전액. `refundableAmount`가 토스 잔액과 다르면 취소를 처리하지 않고 오류 |

**사용 금지**: `GET /v1/payments/orders/{orderId}`. 결제위젯 시크릿 키(`gsk`)로는 호출할 수 없다. 조회는 항상 `paymentKey`로 한다(R8).

### 7.4 응답에서 읽는 값 (Payment 객체)
| 필드 | 용도 |
|---|---|
| `paymentKey`, `orderId` | R3 대조 |
| `status` | `DONE`만 승인 성공. 전체 값: `READY`, `IN_PROGRESS`(인증 완료·승인 전), `WAITING_FOR_DEPOSIT`(가상계좌 입금 대기), `DONE`, `CANCELED`, `PARTIAL_CANCELED`, `ABORTED`(승인 실패), `EXPIRED`(승인 없이 만료) |
| `totalAmount` | 최초 결제 금액. 취소 후에도 그대로. R3 금액 대조 |
| `balanceAmount` | 취소 가능 잔액. 취소 후 갱신, 다음 취소의 `refundableAmount` |
| `currency` | `KRW` 대조 |
| `method`, `approvedAt` | 기록·표시 |
| `isPartialCancelable` | `false`면 전체 취소만 가능 (§12 D2) |
| `cancels[]` | 취소별 `transactionKey`, `cancelAmount`, `canceledAt`, `cancelStatus`(`DONE` = 성공), `cancelReason` |

오류 응답은 HTTP 상태와 `{ code, message }`를 기록한다. 앱에는 비밀정보 없이 전달한다.

### 7.5 결과 불명 처리
**판정 기준 — 토스에서는 서버가 승인 API를 호출해야만 결제된다.**
- `confirm_called_at`이 NULL이면 **결제되지 않은 것**으로 판정할 수 있다. 토스 쪽은 `IN_PROGRESS`로 남았다가 `EXPIRED`가 된다.
- `paymentKey`를 받지 못한 결제시도(앱 종료 등)도 같다.

**승인 결과 불명 (`CONFIRM_UNKNOWN`)**
1. 같은 `paymentKey`로 조회(`GET /v1/payments/{paymentKey}`)한다.
   - `DONE`이고 R3 대조를 통과하면 `APPROVED`
   - `ABORTED`/`EXPIRED`면 `CONFIRM_FAILED`/`EXPIRED`
   - `IN_PROGRESS`이고 승인 기한 안이면 **같은 `Idempotency-Key`**로 승인을 재요청한다
2. 일정 (OMS §7): 직후 1회 → **1분 간격 최대 5회** → 그래도 불명이면 `MANUAL_REVIEW`로 상신하고 **15분 간격** 후속 조회를 유지한다.
3. **시간 경과만으로 실패 처리하거나 점유를 해제하지 않는다.** 수동 종결에도 토스 조회 결과(미승인·실패·취소 또는 적격 복구)를 근거로 남긴다.
4. 멱등키 유효기간(15일)을 넘는 미결은 `MANUAL_REVIEW`에서 처리한다.

**취소 결과 불명 (`UNKNOWN`)**
1. 조회해서 `cancels[]`에 해당 금액의 `cancelStatus = DONE` 취소가 있는지 확인한다.
2. 없으면 **같은 멱등키**로 재요청한다.
3. 새 키나 새 금액으로 요청하지 않는다 (OMS §10).

---

## 8. 시간 규칙

| 시계 | 시작 | 길이 | 근거 |
|---|---|---|---|
| 가격 동결 | 결제 페이지 초안 생성 | 10분 | OMS §5.3 |
| 재고 예약 | PG 진입 직전 | 10분 | OMS §6, §7 |
| 토스 승인 기한 | 결제창 인증 완료 | **10분** | 토스 승인 API 문서. 상태 설명에는 30분이라 적혀 있어 문서 안에서 다르다. 보수적으로 10분 (§12 D5) |

**규칙**
- 결제 진입(§6.1)은 가격 동결과 재고 예약이 **둘 다 유효할 때만** 허용한다.
- **승인 API 호출 직전(§6.3-6)에 예약·슬롯 유효성을 다시 확인한다.** 만료됐으면 승인 API를 호출하지 않는다.
  - 그러면 토스 결제는 `EXPIRED`가 되어 결제되지 않으므로, OMS §7의 "예약 소멸 후 지연 승인 → 승인취소" 상황이 거의 생기지 않는다.
- 그래도 승인 호출 중에 예약이 만료되거나 결과 불명 뒤 승인이 확인되면, OMS §7을 그대로 따른다.
  1. 기존 이행 권리를 복구할 수 있는지 OMS가 재검증한다.
  2. 복구할 수 없으면 OMS가 §6.5로 **같은 원거래 전액 취소**를 요청한다.
  3. 취소 성공 전에는 재고를 재판매하지 않는다.
- `READY`이고 `resource_expires_at`이 지났으며 `confirm_called_at`이 NULL인 결제시도는 `EXPIRED`로 바꾸고 OMS에 알린다. OMS가 점유를 해제한다.

---

## 9. 금액 규칙 (기준: OMS 문서)

**결제 모듈은 금액을 계산하지 않는다.** 아래는 구현·테스트에서 확인할 기준값이다. 실제 값과 계산은 OMS 기존 코드를 따른다.

| 항목 | 기준 | OMS |
|---|---|---|
| 청구액 | 쿠폰 적용 후 상품액 + 확정 배달팁 + 봉투값. 원 단위, 음수 금지 | §5.1 |
| 봉투값 | 주문당 100원 1회 (분할배송이어도 1회) | §5.2 |
| 배달팁 | 한집배달 4,000원, 알뜰·예약배달 3,000원. 상품액 30,000원 이상 또는 첫 구매면 0원 | §5.2 |
| 최소 주문금액 | 15,000원. **오퍼·프로모션 후, 쿠폰 적용 전** 상품액 기준. 결제 진입 전에 OMS가 판정 | §5.1 |
| 통화 | KRW, 정수 (토스 KRW는 소수점 없음) | §5.1 |
| 결제 수 | 주문당 PG 결제 1건. 분할배송이어도 1건 | §9 |
| 결제수단 | 포인트, 선물 잔액, 자체 간편결제는 도입하지 않음 | §5.1 |

- 청구액에는 봉투값이 항상 들어가므로 **결제 금액은 최소 100원**이다. `amount ≤ 0`이면 결제 진입을 거절한다.
- **금액 대조 지점** (모두 정수 일치)
  1. 앱의 위젯 금액 == OMS가 내려준 `amount` (§3.3-3)
  2. 인증 결과 `amount` == 결제시도 `amount` (§6.3-4)
  3. 승인 응답 `totalAmount` == 결제시도 `amount` (§3.3-7)

**환불 기준** (OMS §10, §10.2)
- 원주문 가격 스냅샷과 품목별 할인 안분으로 환불액을 **OMS가 산정**한다. 결제 모듈은 실행만 한다.
- 추가 환불 가능 한도 = 최초 승인액 − 성공한 누적 환불액 − 진행 중 환불 점유액. 결제 모듈도 §6.5-3에서 이 한도를 검사한다.
- 부분 품절·반품은 원결제의 **부분 결제취소**로 처리한다 (OMS §10 `[확정 정책]`). 단, §12 D2.
- 테스트 기준 예시 (OMS §10.2): A 20,000원 / B 10,000원, 쿠폰 3,000원, 무료배송, 봉투 100원
  - 원결제 **27,100원**
  - B 원안분 환불 **9,000원**
  - 잔액 **18,100원** → 토스 `balanceAmount`도 18,100이어야 한다

---

## 10. Commerce 앱 (Flutter) 구현

### 10.1 SDK
- 패키지: `tosspayments_widget_sdk_flutter` (검증 버전 2.3.0)
- 사용 API (SDK 소스로 확인)
  - `PaymentWidget(clientKey: …, customerKey: …)`
  - `renderPaymentMethods(selector: …, amount: Amount(value: …))` → `PaymentMethodWidgetControl` (`updateAmount`, `getSelectedPaymentMethod`)
  - `renderAgreement(selector: …)` → `AgreementWidgetControl` (`getAgreementStatus().agreedRequiredTerms`)
  - `requestPayment(paymentInfo: PaymentInfo(orderId: …, orderName: …))` → `Result { success | fail | pending }`
  - 위젯: `PaymentMethodWidget(paymentWidget:, selector:)`, `AgreementWidget(paymentWidget:, selector:)`
  - 결과 타입
    - `Success(paymentKey, orderId, amount, additionalParams)`
    - `Fail(errorCode, errorMessage, orderId)`
    - `Pending(paymentKey, orderId, amount)`
- 결제창을 뒤로가기로 닫으면 SDK가 `Fail("PAY_PROCESS_CANCELED", …)`을 반환한다.

### 10.2 반드시 지킬 구현 사항
1. **render 타이밍** (사전 테스트에서 실제로 겪은 버그)
   - `render…`는 위젯이 트리에 붙은 **다음 프레임**에 호출한다 (`WidgetsBinding.instance.addPostFrameCallback`).
   - 지연 생성 리스트(`ListView` 등) 안에 결제위젯을 두지 않는다. `SingleChildScrollView` + `Column` 등을 쓴다.
   - render에 타임아웃을 두고 실패를 화면에 표시한다.
   - 이걸 지키지 않으면 SDK 내부에서 `PaymentMethod is not rendered` 예외가 나고, Future가 끝나지 않으며, 결제위젯이 빈 화면으로 남는다. 참고 구현: 이 저장소 `app/lib/main.dart` `_initPaymentWidget`.
2. **결제 흐름**
   1. §6.1 결제 진입을 호출한다.
   2. 받은 `orderId`·`amount`를 상태에 **보관**한다 (R1).
   3. 위젯 금액을 `updateAmount(amount)`로 맞춘다.
   4. 필수 약관 동의를 확인한다.
   5. `requestPayment(PaymentInfo(orderId: 보관값, orderName))`를 호출한다.
3. **결과 처리**
   - `success`: `orderId`·`amount`를 보관값과 대조한다 (R2) → 같으면 §6.3 승인 요청(`orderId`, `paymentKey`, `amount`만, R4) → 서버 결과로 화면을 표시한다.
   - `fail`: **보관한 `orderId`**로 §6.2에 보고한다 (R1). `PAY_PROCESS_CANCELED`는 "취소", 그 외는 "실패"로 표시하고 `errorCode`·`errorMessage`를 보여준다.
   - `pending`: §12 D1 기본 동작에 따라 승인 요청을 보내지 않고 "확인 필요"로 표시하며 OMS에 보고한다.
4. **표시 규칙**
   - "인증 성공"과 "결제 완료"를 구분한다. **서버가 `APPROVED`를 돌려준 뒤에만** 완료로 표시한다.
   - 승인 요청 응답을 놓치면 §6.4 조회로 확인한다. 승인 요청을 다시 보내지 않는다.
   - 결제·승인 진행 중에는 중복 클릭과 뒤로가기를 막는다.
5. **금액 입력 UI 없음**: 금액은 OMS가 내려준 값만 쓴다.
6. **Android 설정** (토스 문서)
   - `<uses-permission android:name="android.permission.INTERNET" />`
   - `android:usesCleartextTraffic="true"` (Flutter SDK 문서 요구)
   - 카드사·간편결제 앱 호출용 `<queries>` 패키지 목록. 토스 WebView 가이드의 목록을 그대로 쓴다. 참고 구현: 이 저장소 `app/android/app/src/main/AndroidManifest.xml`.
7. **빌드 주의**: SDK 의존성 `flutter_inappwebview_android` 1.1.3은 AGP 9.x에서 빌드가 실패했다(`proguard-android.txt` 미지원). 사전 테스트에서는 AGP 8.11.1 / Gradle 8.14.3으로 해결했다. 실제 프로젝트 AGP 버전을 먼저 확인한다.
8. **알려진 문제**: 사전 테스트에서 결제위젯 내부를 탭하면 화면이 맨 위로 스크롤되는 현상이 있었다. 원인은 확인하지 못했다. 결제 자체는 막지 않는다.

### 10.3 `customerKey`
- 로그인 회원만 주문하므로(OMS §4) `PaymentWidget.anonymous`는 쓰지 않는다.
- 회원 계정당 1개의 무작위 값을 쓴다. 이메일, 전화번호, 순번 ID는 쓰지 않는다.
- 탈퇴 후 재가입한 계정에는 새 값을 쓴다 (OMS §13).
- 생성·보관 위치는 §12 D6.

---

## 11. 수용 테스트 (구현 완료 기준)

토스 **테스트 키**로 실행한다. "확인함"은 실제 실행 결과로만 표시하고, 실행하지 않은 항목은 "미확인"으로 남긴다.

### 11.1 ID 일관성 ★
| # | 상황 | 기대 결과 |
|---|---|---|
| ID-1 | 정상 결제 1건 | 앱 보관 `orderId` = 결제위젯 요청 `orderId` = 인증 결과 `orderId` = 승인 요청 `orderId` = 토스 승인 응답 `orderId` = DB `order_id`. `paymentKey`도 인증 결과 = 승인 요청 = 승인 응답 = DB |
| ID-2 | 결제 진입 응답 유실 후 같은 요청 식별자로 재전송 | **같은 `orderId`** 반환, 결제시도 1개 |
| ID-3 | 결제창 사용자 취소 (결과 `orderId`가 빈 값) | 앱이 보관한 `orderId`로 보고 → 해당 결제시도가 `CANCELED_BY_USER` |
| ID-4 | 인증 결과 `orderId`를 조작해 승인 요청 | 앱이 승인 요청을 보내지 않음. 서버로 직접 보내도 `404`/불일치로 거절, 토스 호출 0회 |
| ID-5 | 같은 `orderId`에 다른 `paymentKey`로 승인 요청 | `409 PAYMENT_KEY_MISMATCH`, 토스 호출 0회 |
| ID-6 | (모의) 승인 응답의 `orderId`/`paymentKey`가 요청과 다름 | `MISMATCH_HOLD`, 주문 확정 안 됨 |
| ID-7 | 같은 주문에 `CONFIRM_UNKNOWN` 결제시도가 있을 때 결제 진입 | 거절, 새 `orderId` 미발급 |
| ID-8 | 전체·부분 취소 | 취소 응답의 `paymentKey`·`orderId` = 결제시도 값, `transactionKey`가 취소 레코드에 저장 |

### 11.2 결제 흐름
| # | 상황 | 기대 결과 |
|---|---|---|
| P-1 | 정상 결제 | `READY → CONFIRMING → APPROVED`, 토스 `status = DONE`, OMS 주문 확정 통지 |
| P-2 | 금액 변조 승인 요청 (+1원) | `AMOUNT_MISMATCH`, 토스 호출 0회 |
| P-3 | 같은 결제 승인 요청 2회 (동시·순차) | 토스 승인 1회, 두 번째는 기존 결과 반환 |
| P-4 | 결제창에서 오래 머물러 예약 만료 후 인증 성공 | 승인 API 호출 0회, `EXPIRED`, `RESERVATION_EXPIRED` 응답 |
| P-5 | 승인 API 타임아웃 (모의) | `CONFIRM_UNKNOWN` → 조회로 해소, 같은 멱등키로만 재요청, 새 결제 없음 |
| P-6 | 승인 응답 `status ≠ DONE` (모의) | `MISMATCH_HOLD`, 주문 확정 안 됨 |
| P-7 | 잘못된·누락된 키 | 서버 기동 시 또는 호출 시 명확한 오류. 앱은 결제 버튼 비활성과 안내 |
| P-8 | 앱 종료로 승인 요청이 오지 않음 | 만료 시각 후 `EXPIRED` (`confirm_called_at` NULL), OMS 점유 해제 통지 |

### 11.3 취소·환불
| # | 상황 | 기대 결과 |
|---|---|---|
| C-1 | OMS §10.2 예시: 27,100원 결제 후 B 9,000원 부분 취소 | 토스 `balanceAmount` = 18,100, OMS 잔액과 일치 |
| C-2 | 한도 초과 취소 요청 | 결제 모듈이 거절, 토스 호출 0회 |
| C-3 | 취소 응답 유실 | 조회로 `cancels[]` 확인, 같은 멱등키로만 재요청, 이중 환불 없음 |
| C-4 | `isPartialCancelable = false` 결제의 부분 취소 요청 | 토스 호출 없이 거절 → OMS에 결정 필요 통지 (D2) |
| C-5 | 승인 후 주문 확정 실패 → 보상 전액 취소 | 원거래 `CANCELED`, 재판매는 취소 성공 후 |

---

## 12. 미결정 사항 — 임의로 정하지 말 것

아래 항목은 **기본 동작(안전한 쪽)만 구현**한다. 결정 지점은 설정값이나 명확한 TODO로 남기고, 구현 완료 보고에 목록으로 포함한다.

| # | 내용 | 구현할 기본 동작 |
|---|---|---|
| D1 | 가상계좌(입금 대기), 해외 간편결제(`pending`) 등 비동기 결제수단 허용 여부. 권장안: 허용하지 않음 | `DONE` 외의 승인 응답은 `MISMATCH_HOLD`. `pending`은 승인 요청 없이 OMS에 보고. **운영 결제 UI·계약에서 해당 수단 제외를 사용자에게 요청.** 웹훅 미구현 |
| D2 | `isPartialCancelable = false` 결제에서 부분 품절·반품 처리 | 부분 취소를 실행하지 않고 OMS에 "결정 필요"로 반환. 전체 취소 후 재결제는 하지 않음 (OMS §10) |
| D3 | 주류 성인인증. OMS §6은 "토스 결제 인증으로 대체"라고 하지만, 토스 결제 인증은 **카드 소유자 인증**이며 연령 확인 근거를 찾지 못함 | 결제 모듈은 성인인증을 했다고 기록·표시하지 않음. 사용자에게 확인 필요로 보고 |
| D4 | 인증 성공 시 재고 예약 연장·재확보 여부 | 연장하지 않음. 만료면 §8대로 승인하지 않음 |
| D5 | 토스 승인 유효시간 10분/30분 | 10분 기준 |
| D6 | `customerKey` 생성·보관 주체 (Commerce 회원 정보 vs OMS) | 기존 회원 모델에 보관하는 쪽으로 조사·제안하고, 사용자 확인 후 구현 |
| D7 | 이미 결제된 `orderId` 재사용 시 토스 동작 | 재사용하지 않음 (결제시도마다 새 `orderId`) |
| D8 | 결제 UI 노출 설정·`variantKey` | 기본 UI (`variantKey` 미지정) |

---

## 13. 구현 순서 (권장)

1. §0.2 조사 → 사용자에게 요약 보고. 기존 구조와 이 문서가 충돌하면 보고하고 대기한다.
2. 데이터 모델·마이그레이션 (§4): 유일 제약(`order_id`, `payment_key`, `entry_request_id`, `cancel_request_id`)
3. 토스 API 클라이언트 (§7): 승인, 조회, 취소, 인증 헤더, 멱등키, 타임아웃, 오류 분류. 비밀값 로그 제외
4. 결제 진입 (§6.1)과 OMS PG 진입 조건 연결
5. 승인 요청 (§6.3)과 상태 머신 (§5). 조건부 갱신으로 동시성 처리
6. 인증 실패·취소 보고 (§6.2), 상태 조회 (§6.4)
7. 결과 불명·만료·대사 스케줄러 (§6.6, §7.5, §8)
8. 취소 실행 (§6.5)과 OMS 취소·클레임·보상 모듈 연결
9. Flutter 결제 화면 (§10)
10. 수용 테스트 (§11): 자동 테스트(토스 호출 모의) + 테스트 키 실기기 시험. 결과를 "확인함/미확인"으로 기록
11. 완료 보고: 구현 범위, 테스트 결과, §12 미결정 항목의 현재 기본 동작, 남은 수동 검증

---

## 14. 근거
- 토스페이먼츠
  - [결제 흐름](https://docs.tosspayments.com/guides/v2/get-started/payment-flow)
  - [코어 API — 승인·조회·취소·Payment 객체](https://docs.tosspayments.com/reference)
  - [API 키](https://docs.tosspayments.com/reference/using-api/api-keys)
  - [인증·멱등키](https://docs.tosspayments.com/reference/using-api/authorization)
  - [통합결제창 연동(10분 승인 기한, 사용자 취소 시 orderId 미전달)](https://docs.tosspayments.com/guides/v2/payment-window/integration)
  - [Flutter SDK](https://docs.tosspayments.com/sdk/v2/widget-flutter)
  - [WebView·Android `<queries>`](https://docs.tosspayments.com/guides/v2/webview)
- `tosspayments_widget_sdk_flutter` 2.3.0 소스 (`payment_widget.dart`, `widgets/payment_method.dart`, `model/tosspayments_result.dart`, `model/tosspayments_url.dart`)
- 실제 프로젝트 `OMS-business-logic.md` (§ 번호로 참조)
- 이 저장소 (사전 테스트)
  - [`OMS_PAYMENT_ALIGNMENT.md`](OMS_PAYMENT_ALIGNMENT.md): OMS·토스 조율 근거 P1~P16
  - [`INTEGRATION_GUIDE.md`](INTEGRATION_GUIDE.md)
  - 참고 구현: `app/lib/main.dart`, `server/server.js`. **예시 코드이며 운영용이 아니다.** 메모리 저장, 승인 응답 ID 대조 없음.
