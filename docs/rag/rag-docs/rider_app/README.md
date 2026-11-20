# 라이더앱 정책 안내 문서 — RAG 원고 (초안)

라이더 앱 지원 화면·상담에서 AI 가 답할 때 근거로 쓸 **라이더 공개 안내 문서** 초안입니다.

- 문서 버전: 0.1 (**초안 — 사용자 승인 전**)
- 작성: Claude, 2026-11-20 (사용자 "라이더앱 정책 문서도 아까처럼 md 파일 문서로 만들어줘")
- 근거: `services/rider-service/CLAUDE.md`(가입·면허·계좌·휴대폰 확인·계정 제한·컴플레인 검토·소명·탈퇴), `services/delivery-execution-service/CLAUDE.md`(픽업·전달·전달 실패·도움요청·수행료·지급), `contracts/openapi/delivery-execution-service.yaml`(엘리베이터 추가 배달료), `deploy/lan/.config-repo/delivery-dispatch-service.yml`(배차 제안 1분), `docs/architecture/service-map.md`, ADR-0071·ADR-0078
- 2026-11-20 보강(사용자 요청 "운영자용 RAG 문서 정비" — 직원용 배송 운영 문서 `delivery/` 를 만들며 라이더에게 공개할 결정 사항을 더함): 배차 방식·한집 단독·알뜰 묶음·서비스 시간(ADR-0058 덧붙임, `services/delivery-dispatch-service/CLAUDE.md`), 고객 수령 확인·자동 완료·실패 대화·재개 요청·반납 배송·반납 인증번호(`services/delivery-execution-service/CLAUDE.md` 0.14.0·0.15.0·0.18.0·0.20.0·0.28.0), 고객 사유·사고 실패 수행료(0.15.0·ADR-0069), 이의 인정 보상금(0.30.0). 직원 처리 기준은 `delivery/` 에 두고 여기에는 다시 적지 않는다.
- 작성 규칙(사용자 2026-11-20 "약관 문서처럼"): 앱 약관 문서와 같은 형식 — 제목·시행일·제N조(제목)·번호 항목, "회사"·"라이더" 표현, ~합니다 체. 내부 처리·내부 용어는 넣지 않는다.

## 문서 목록

| 파일 | 주제 |
|---|---|
| [01-signup.md](01-signup.md) | 가입·로그인, 휴대폰 번호 확인, 배달 수단·면허, 거주지·정산 계좌 |
| [02-operation-dispatch.md](02-operation-dispatch.md) | 운행 상태, 배차 방식(AI 추천·일반)·최대 3건·한집 단독·알뜰 묶음·서비스 시간, 배차 제안·수락, 배차 취소 |
| [03-pickup-delivery.md](03-pickup-delivery.md) | 가게 도착·픽업, 전달 완료·고객 수령 확인·자동 완료, 전달 실패·재개 요청·반납 배송·반납 인증번호, 도움요청 |
| [04-earnings.md](04-earnings.md) | 수행료 산정·확정·지급일, 산정 불가, 전달 실패·사고·반납 수행료, 엘리베이터 추가 배달료, 소명·보상금 |
| [05-restriction-withdrawal.md](05-restriction-withdrawal.md) | 계정 제한, 컴플레인 검토, 탈퇴·재가입 |

## 색인에 넣으려면 (아직 안 함)
- 라이더용 RAG 는 아직 없다(운영자용·고객용만). 라이더 RAG 를 만들 때 색인 대상으로 쓴다 — 사용자 승인 뒤.

## 넣지 않은 것
- 배차 재요청 간격·반납 배송 대체 규칙(LAN 시험 설정이 운영과 다름 — 운영 값 확정 뒤).
- AI 추천 배차의 계산 방식(라이더에게 공개할 내용 아님), 배차 반경 거리(설정값 — 운영 값 확정 뒤).

## LAN 과 다른 값 (문서는 운영 기준값)
- 탈퇴 뒤 이름·전화 보관·같은 번호 재가입 막기: 문서 30일, **LAN 5분**(`rider-service-lan.yml` rejoin-block PT5M).
- 가입 휴대폰 확인 필수 여부는 LAN 스위치(`phone-verification.required`)를 따른다(라이더 앱 새 버전 배포 뒤 켬).
- 수행료 실제 송금은 외부 회계와 연결 전 — 서버는 지급 예정일·결과 표시까지.
