# 딥러닝 수요예측 — 정할 것 (작업 전 기록)

> 2026-11-09 기록. 사용자: "수요예측은 딥러닝 모델 사용할거야", 작업은 2026-11-10 이후 시작 예정. 아래 "추천"은 결정이 아니다. 결정하면 이 문서와 새 ADR 에 적는다.

## 1. 출발점

- **담당 서비스**: `demand-planning-service`(SCM, 아직 `services/` 에 없음). 계약은 있음 — `contracts/openapi/demand-planning-service.yaml`(`POST /forecast-runs`, `GET /forecast-runs/{id}`, `GET /forecasts`), 사건 `ForecastVersionPublished`(`docs/architecture/events.md`) → `replenishment-service`.
- **기존 방침과 충돌**: `docs/architecture/tech-stack.md:49` "별도 Python 런타임은 두지 않는다. Python 전용 모델은 3단계에 별도 승인" — 딥러닝 모델을 쓰려면 **새 ADR 로 이 방침을 바꿔야 한다**. 서비스 맵(`service-map.md` §3)의 "1차는 수동 수요 + 이동평균"도 함께 고친다.
- **참고 자료**: 시뮬레이터 저장소 `docs/MFC 시스템 구성안 — Chronos-2 + Google OR-Tools.md`(Chronos-2 를 Python 서비스로 두는 안), `docs/plan-forecast-assignment.md`(D-1~D-3), baro-pick-docs 브리프. 예측 결과 소비처는 백엔드 설계상 SCM `replenishment-service` 이고 MFC IV-04 가 직접 쓰는 경로는 없다(D-2 미결).

## 2. 실행 방식 — 정할 것

| 방식 | 쓰는 것 | 장점 | 단점 |
|---|---|---|---|
| **(가) Python 배치 작업** | PyTorch + 모델 패키지. 정해진 시각에 돌고, 결과를 API 로 demand-planning(Java)에 넣는다 | 딥러닝 모델 대부분이 Python 기준이라 그대로 쓴다. 예측은 하루·시간 단위 배치라 상주 서버 불필요. Java 서비스는 그대로 | 새 런타임·이미지 하나를 관리 |
| (나) Java 안에서 추론 | DJL(Deep Java Library) 또는 ONNX Runtime Java 를 demand-planning 에 넣음 | Python 서버 없음 | 학습은 결국 Python. 쓸 모델을 ONNX·TorchScript 로 내보낼 수 있어야 함 — 최신 예측 모델은 안 되는 경우가 있어 모델마다 확인 필요 |

- **추천(미결)**: (가). 예측은 실시간 요청이 아니라 배치라서 떨어진 Python 작업이 가장 단순하고, 백엔드는 결과 저장·`ForecastVersionPublished` 발행만 한다.
- (가)와 함께 쓸 만한 것: 판매 이력 집계는 Java 쪽 Spring Batch(이미 `libs.versions.toml` 에 있음), 모델 버전·실험 관리(MLflow 등)는 재학습을 시작할 때 정한다.
- 이름 정리: OR-Tools 문서(`OR-Tools_개념과_바로픽_작업자배정_적용.md` 7장)의 **방식 A**(Java 서비스 안에 라이브러리로) ≈ 여기 (나), **방식 B**(Python 서비스 분리) ≈ 여기 (가). OR-Tools 는 방식 A 로 이미 정했고(2026-11-07), 수요예측은 따로 정한다.

## 3. 같이 정할 것

| # | 정할 것 | 메모 |
|---|---|---|
| F-1 | 쓸 모델 | 예: Chronos-2(시뮬레이터 구성안). 모델이 정해져야 (나) 가능 여부·자원 크기를 안다 |
| F-2 | 실행 방식 (가)/(나) | 위 2장 |
| F-3 | 이동평균과의 관계 | 버림 / 별도 모델로 공존(실패 시 자동 대체 금지 — 시뮬레이터 계획서 명세 4.4) |
| F-4 | 예측 결과 소비처 | SCM replenishment 만 / MFC IV-04(선반 보충)도 구독 |
| F-5 | 예측 단위·주기·기간 | 센터 × SKU × 시간 버킷, 하루 몇 번, 며칠 앞까지 |
| F-6 | 출력 형식 | 지금 사건은 `forecastQty` 하나 + basis·confidence. 분위수(p10/p50/p90)·모델 ID·버전 필드 추가(하위 호환) 여부 |

## 4. 먼저 확인할 것

- **CPU 로 돌릴 수 있는 크기인지**: 상품 약 1만 개 × 센터 수. LAN PC 는 GPU 가 없는 것으로 알고 있다(확인 필요). 모델을 정한 뒤 실제로 돌려 시간·메모리를 잰다. LAN 메모리 여유는 `docs/simulator/mfc/mfc-simulator-integration.md` SIM-10 참고(pc2 여유가 가장 큼).
- **판매 이력이 있는지**: 지금 LAN 에는 예측에 쓸 만한 판매 이력이 없다. 시험 입력을 어떻게 만들지(시뮬레이터 가상 판매 이력 — 실데이터와 구분 표시 필수, 실제 주문 누적) 함께 정한다.
- 판매 0 과 데이터 없음 구분, 품절 구간(팔 수 없어 0)의 표시 — 품절 사실 원천은 mfc-inventory 가용 0 구간.

## 5. 작업 순서 제안 (결정 뒤)

1. ADR(방침 변경 + 실행 방식) → tech-stack.md·service-map.md 갱신.
2. demand-planning-service 생성(계약 기존) — 판매 이력 집계, 예측 실행 기록·결과 저장, `ForecastVersionPublished`.
3. 예측 작업(가: Python 이미지 / 나: Java 추론) — 처음에는 작은 SKU 묶음으로 자원 측정.
4. 소비처 연결(F-4).
