# 배송 운영 사내 정책 문서 — RAG 원고 (초안)

배송 권역·도착 시간대·배차·픽업과 전달 실패·배송 사고와 회수·관제·라이더 수행료·라이더 알림에 관해 라이더 운영(지원센터), 운영 관리자, 고객센터 상담원, 센터 관리자가 업무 기준을 물을 때 AI 가 근거로 쓸 **직원용 사내 정책 문서** 초안입니다. 고객·라이더에게 공개하는 문서가 아닙니다. 라이더에게 공개하는 기준은 `rider_app/`, 라이더 계정 제한·검토·소명·탈퇴 목록은 `backoffice/07-rider-operations.md`, 센터의 포장 인계는 `mfc/05-dispatch-handover.md`, 접수 건 종류·처리 기한은 `backoffice/05-customer-center-cases.md` 에 있으며 여기서 다시 적지 않았습니다.

- 문서 버전: 0.1 (**초안 — 사용자 승인 전**)
- 작성: Claude, 2026-11-20 (사용자 요청 "운영자용 RAG 문서 정비")
- 근거:
  - 권역·배송 조건: `services/delivery-serviceability-service/CLAUDE.md`(권역 매핑 버전·승인·시험 주소, 도로명 정확 일치 판정, 겹침, 배송 조건), 서비스 시간 08~24시(사용자 2026-11-13, `docs/business/Delivery-business-logic.md` §2.5 PMart 결정), `docs/rag/rag-docs/backoffice/02-roles-permissions.md`(배송 권역 승인자)
  - 도착 시간대·약속: `services/delivery-capacity-service/CLAUDE.md`(오늘·내일, 1시간, 08시 전 없음, 점유 10분, 정원 미설정, 교체 점유, CP-1 유지 + 안내), `services/order-orchestration-service/CLAUDE.md`(S4), `services/notification-service/CLAUDE.md`(지연 가능 안내), `services/delivery-dispatch-service/CLAUDE.md`(약속 50분·예약 시간대 끝·지연 20분·배차 지연 목록 0.12.0), ADR-0069(재발송 약속)
  - 배차: `services/delivery-dispatch-service/CLAUDE.md`(점원 배차 요청, 후보 조건, 묶음·한집 단독, 재요청 2회, 배차 실패, 자동 취소 약속 + 30분, 배차 취소 권한, 제한 반영, 배차취소 감점, 배정 이력·조치 이력), ADR-0058(두 배차 방식·AI 결과 검증·대체 계산·덧붙임 한집 단독), `services/rider-service/CLAUDE.md`(첫 가입 AI 추천 모드)
  - 픽업·완료·실패: `services/delivery-execution-service/CLAUDE.md`(픽업 판정·재포장, 고객 수령 확인·자동 완료 0.20.0, 실패 확정 권한 0.31.3, 사건함 완료 = 확정 2026-11-20, 직접 확정 0.18.0, 반납 0.15.0·0.18.0, 반납 인증번호 0.28.0, 되돌리기 0.32.0, 상태 정정 0.17.0·0.22.0, 조치 이력), `services/delivery-dispatch-service/CLAUDE.md`(반납 배정·다른 라이더 대체), `services/delivery-tracking-service/CLAUDE.md`(받았는지 묻기 20분)
  - 사고·도움요청·회수: ADR-0069, `services/delivery-execution-service/CLAUDE.md`(도움요청 답장 권한 DE-2·가림, 오배송 회수 0.5.0, 반품 회수 0.10.0), `services/delivery-dispatch-service/CLAUDE.md`(DD-2 회수 배차, DD-3 반품 회수·전달 완료 뒤만), `docs/delivery/HANDOVER.md` §4(DD-2 결정 a~d)
  - 관제·위치: ADR-0068, `services/delivery-tracking-service/CLAUDE.md`(관제 0.8.0~0.12.0, 위치 공유 조건, 끊김 3분·5시간, 보존 30일, 고객 공개 범위 0.6.0, 정시 배송률은 dispatch 0.13.0), `services/delivery-execution-service/CLAUDE.md`(직원 전달지 열람 기록 0.24.0, 운행시간·접속·대기 0.11.0·0.25.0), `docs/delivery/HANDOVER.md` §4(2026-11-15 결정 2)
  - 수행료 운영: `services/delivery-execution-service/CLAUDE.md`(산정 불가 조정 0.13.0, 실패·반납 수행료 0.15.0·0.18.0, 사고 수행료 0.23.0, 오배송 귀책, 추가 배달료 0.19.0, 보상금 0.30.0, 지급 결과 0.29.0), ADR-0069 결정 2·3
  - 라이더 알림: ADR-0066, ADR-0067(라이더 부분), `services/delivery-execution-service/CLAUDE.md`(수행료 조정 알림)
- 작성 규칙(다른 RAG 원고와 같음): 앱 약관 문서 형식 — 제목·시행일·제N조(제목)·번호 항목, ~합니다 체. 내부 코드·API·클래스·사건·서비스 이름은 넣지 않고 역할은 표시 이름(라이더 운영·운영 관리자·고객센터 상담원·센터 관리자·배송 권역 승인자)으로 쓴다. 결정·구현된 규칙만 넣는다. 승인되지 않은 ADR(0007·0008·0023 은 상태 "제안")은 구현된 동작으로 확인되는 부분만 썼다. 업무 원문(Delivery)의 외부 브랜드 참고값은 PMart 결정이나 구현으로 확인된 것만 썼다.

## 문서 목록

| 파일 | 주제 |
|---|---|
| [01-service-area.md](01-service-area.md) | 권역 매핑 변경(승인·시험 주소·발효), 배송 가능 판정(도로명 주소 기준·지도 핀 안 씀·겹침), 배송 조건(방식 3가지·10,000g·00~08시 없음), 배달 서비스 시간 08~24시 |
| [02-arrival-slots-promise.md](02-arrival-slots-promise.md) | 도착 시간대(오늘·내일·1시간·시작 시각 마감), 정원·점유 10분·미설정이면 선택 불가·시간대 바꾸기, 작업 여력 감소 때 유지 + 안내, 배송 약속(50분·예약은 시간대 끝·재발송), 배차 지연 안내(20분)·목록 |
| [03-dispatch.md](03-dispatch.md) | 배차 시작(점원 요청), AI 추천·일반 배차모드와 AI 결과 검증, 제안 대상 조건, 한집 단독·알뜰 묶음(최대 3건·약속 안), 배차 실패·재요청·자동 취소(약속 + 30분), 배차 취소 권한·재배차, 계정 제한 반영, 배차 기록 조회 |
| [04-pickup-completion-failure.md](04-pickup-completion-failure.md) | 픽업 판정(모든 포장·재포장), 전달 완료·고객 수령 확인·받았는지 묻기, 전달 실패 확정·반려 권한(라이더 운영·운영 관리자)·접수 건 완료 = 확정·직접 확정, 반납 배송(같은 라이더·다른 라이더), 되돌리기 30분, 상태 정정 |
| [05-incidents-help-recovery.md](05-incidents-help-recovery.md) | 도움요청 답장 권한·가림, 사고·파손 새 상품 재발송(배송 쪽), 오배송 회수 배송, 고객 반품 회수 배송 |
| [06-control-location.md](06-control-location.md) | 관제(라이더 운영·운영 관리자, 운행 중 현재 위치만, 10초), 위치 공유 조건, 직원 전달지 열람 기록, 고객·라이더 공개 범위, 위치 끊김 3분, 보존 30일, 운행·접속·대기 시간, 정시 배송률 |
| [07-earnings-operations.md](07-earnings-operations.md) | 산정 불가 수행료 조정(귀책·사유·3영업일), 사고 간 거리 재는 법, 오배송 귀책과 수행료, 추가 배달료 결정, 이의 인정 보상금, 지급 결과 표시, 조치 이력 |
| [08-rider-notifications.md](08-rider-notifications.md) | 라이더 알림함·휴대폰 푸시, 알리는 일 5가지, 알림 문구에 넣지 않는 것 |

## 색인에 넣으려면 (아직 안 함)
- 운영자용 RAG(ai-assistant-service) 색인 대상 후보 — 넣을지는 사용자 결정, 승인 뒤.

## 넣지 않은 것
- **결정 전·구현 전**: 고객센터 배차 불가 보상 쿠폰(2,000원 — 쿠폰 기능 생길 때), 센터 관리자 관제·위치 이력 열람(ADR-0068 "다음 단계"), 관제 실시간 구독, 추가·분할 배차, 묶음 배달료 할인·할증, 주류·보냉·용량 거르기, 배달리스트에서 묶음째 고르기, 라이더 가입 승인 정책("정책은 나중에"), 위치 권역 밖 판정, AI 추천 배차를 LAN 에서 켜는 시점(추가 학습 뒤), 재발송 지연 기한(ADR-0052·0069 남은 것), 사고·파손 재발송 고객 알림(N-4).
- **값이 설정에 있고 운영 값이 정해지지 않은 것**: 배차 반경 단계의 거리(업무 원문의 1·2·3km 는 외부 참고값), 재요청 간격, 도착 시간대 정원, 다른 라이더 반납이 대체되는 시간(LAN 5분), 고객에게 곧 도착 알림 거리.
- **값이 문서마다 다른 것**: 알뜰배달 묶음의 "바로 갈 때보다 더 늦어도 되는 한도"(서비스 문서 안에 20분·50분 두 값) — 본문은 사용자 결정인 "모든 고객이 약속 안에"만 적음.
- **다른 문서 몫**: 오배송·사고 고객 환불·보상·다시 배송비(주문·반품 정책), 배차 불가 주문 취소의 취소·환불 처리(주문 취소 정책), 접수 건 종류·처리 기한(`backoffice/05`), 라이더 계정 제한·검토·소명(`backoffice/07`), 포장 인계·인계 번호 잠금(`mfc/05`).
- **개발·인프라**: AI 최적화 서버 구성·모델, 길찾기·주소 변환 외부 API, 호출 수 제한·캐시, 사진 저장소.

## 확인 필요
1. **전달 실패 확정 권한**: `docs/architecture/auth/staff-roles.md` 는 그 센터 센터 관리자·상담원도 확정할 수 있는 것처럼 적혀 있으나, 2026-11-19 사용자 결정("라이더 백오피스만")으로 구현이 라이더 운영·운영 관리자만으로 바뀌었다. 본문은 최신 구현(04 제4조)을 따름 — `staff-roles.md` 갱신 필요(`backoffice/README.md` 확인 필요 6번도 이것으로 풀림).
2. **반납 인증번호 필수 여부**: 구현 기본값은 "필수 아님", LAN 설정은 필수(사용자 "인증코드를 입력해야 전달완료"). 본문은 사용자 결정대로 필수로 적음 — 운영 설정도 필수인지.
