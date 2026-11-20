# 라이더앱 정책 안내 문서 — RAG 원고 (초안)

라이더 앱 지원 화면·상담에서 AI 가 답할 때 근거로 쓸 **라이더 공개 안내 문서** 초안입니다.

- 문서 버전: 0.1 (**초안 — 사용자 승인 전**)
- 작성: Claude, 2026-11-20 (사용자 "라이더앱 정책 문서도 아까처럼 md 파일 문서로 만들어줘")
- 근거: `services/rider-service/CLAUDE.md`(가입·면허·계좌·휴대폰 확인·계정 제한·컴플레인 검토·소명·탈퇴), `services/delivery-execution-service/CLAUDE.md`(픽업·전달·전달 실패·도움요청·수행료·지급), `contracts/openapi/delivery-execution-service.yaml`(엘리베이터 추가 배달료), `deploy/lan/.config-repo/delivery-dispatch-service.yml`(배차 제안 1분), `docs/architecture/service-map.md`, ADR-0071·ADR-0078
- 작성 규칙(사용자 2026-11-20 "약관 문서처럼"): 앱 약관 문서와 같은 형식 — 제목·시행일·제N조(제목)·번호 항목, "회사"·"라이더" 표현, ~합니다 체. 내부 처리·내부 용어는 넣지 않는다.

## 문서 목록

| 파일 | 주제 |
|---|---|
| [01-signup.md](01-signup.md) | 가입·로그인, 휴대폰 번호 확인, 배달 수단·면허, 거주지·정산 계좌 |
| [02-operation-dispatch.md](02-operation-dispatch.md) | 운행 상태, 배차 제안·수락, 배차 취소 |
| [03-pickup-delivery.md](03-pickup-delivery.md) | 가게 도착·픽업, 전달 완료, 전달 실패, 도움요청 |
| [04-earnings.md](04-earnings.md) | 수행료 산정·확정·지급일, 산정 불가, 엘리베이터 추가 배달료, 소명 |
| [05-restriction-withdrawal.md](05-restriction-withdrawal.md) | 계정 제한, 컴플레인 검토, 탈퇴·재가입 |

## 색인에 넣으려면 (아직 안 함)
- 라이더용 RAG 는 아직 없다(운영자용·고객용만). 라이더 RAG 를 만들 때 색인 대상으로 쓴다 — 사용자 승인 뒤.

## 넣지 않은 것
- 배차 재요청 간격·반납 배송 대체 규칙(LAN 시험 설정이 운영과 다름 — 운영 값 확정 뒤).
- AI 추천 배차·하나씩 배달하기의 상세 동작(설정 이름만 있음 — 동작 설명은 확정 뒤).
