# ADR-0004 BackOffice 원장을 위해 approval-service, case-service, center-master-service를 추가한다

- 상태: 제안 (2026-10-23) — CLAUDE.md 50개 구성에 **3개 추가**
- 관련: CLAUDE.md L118 `backoffice-bff`, BackOffice v2.0 1.1·1.2·2장·3장·4장·5장·BO-06·BO-09·BO-10·BO-22·BO-26·BO-27·BO-Q01~Q06

## 배경
CLAUDE.md의 `backoffice-bff`는 "관리자 화면별 조회 조합·도메인 명령 전달"이며 상태가 없다. 그러나 BackOffice 문서는 BackOffice가 다음 **지속 원장**을 소유한다고 명시한다: 권한 논리 정본(RoleGrant/Delegation), 승인 기록(ChangeRequest/ApprovalDossier, 5축 상태, 부분 집행 불변식), 통합 사건(Case/SubCase/Obligation/ShiftHandover), 대량 변경 배치, 정책 참조 레지스트리. 또한 센터 코드·영업 캘린더·취급 온도대(BO-06)는 order/replenishment/transport/delivery/mfc/cdc가 모두 소비하지만 CLAUDE.md에 센터 마스터 서비스가 없다.

## 결정
| 서비스 | 소유 | 소유하지 않음 |
|---|---|---|
| `approval-service` | ChangeRequest, ApprovalDossier(원도메인 승인 ID 참조·이중 발급 금지), 요청/심사/실행/전달 상태 축, `승인량 = 누적 집행 + 미집행 취소 + 열린 잔량`, 통합 승인함, 대량 변경 배치, PolicyReference(원정책 ID·버전·반영 상태) | 승인 결과의 업무 정본(상품=product, 가격=pricing, 공급=replenishment, 환불=claim, 품질=quality, 권역=serviceability, 권한=auth, 조정=inventory) |
| `case-service` | Case/SubCase/Obligation/ActionRequest/Evidence, ShiftHandover/CloseSnapshot, CS 접수, MFC 문제함, 라이더 도움요청·고객 연락 기록, 귀책·비용 결정 기록 | 도메인 사고·클레임·수불 정본, 알림 |
| `center-master-service` | 센터 코드·유형·주소·좌표·하차/인계 위치·층수·반입 조건·취급 온도대·다섯 시간표(고객 주문/근무/차량 진입/하차·인계/검수·적치)·휴폐점 의무 | 센터 내부 LOC(각 location), 현장 용량(mfc-capacity), 권역(serviceability) |

- 유효 권한 grant·대행·긴급권한의 **정본은 `auth-service`**(CLAUDE.md "역할/권한 정보"). 부여·회수 **승인 흐름**은 approval-service. 교집합 모델이 auth 범위를 넘으면 `access-control-service` 분리 재검토 `[BO-Q01]`.
- 업무 정책값은 각 도메인 서비스가 소유하고 approval-service는 참조만. (`config-service`는 2026-10-23 제거, Kubernetes ConfigMap 사용.)
- `backoffice-bff`는 그대로 상태 없음. 조회 조합(원천별 asOf·신선도)과 명령 전달(멱등키)만.

## 대안
- approval·case를 `backoffice-bff`에 넣기 — "BFF는 상태 없음"과 충돌, 감사·보존 요구 불가.
- 승인 원장을 각 도메인에 분산 — 통합 승인함(BO-10)·재심사 계보·대량 변경이 불가능.
- 센터 마스터를 `delivery-serviceability`나 `mfc-location`에 두기 — CDC와 TMS가 같은 센터 정보를 쓰므로 Delivery/MFC 전용 서비스에 둘 수 없음.

## 결과
- 총 서비스 51개(원안 50 − config/discovery 2 + 추가 3). 서비스 목록 정본은 `service-map.md`.
  - 2026-10-24 정정: service-map 기준 **49개**(업무 40 + 플랫폼·지원 6 + 추가 3). 위 "51개"는 셈 오류이며 결정 내용은 바뀌지 않는다.
- 승인 발효는 "approval → ApprovalDecided(이벤트) → 정본 반영 → Receipt 회신" 순. 2 hop 규칙 때문에 approval이 정본을 거쳐 3자를 동기 호출하지 않는다.
