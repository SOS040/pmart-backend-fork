# 고객앱 정책 안내 문서 — 고객용 RAG 원고 추가분 (초안)

고객앱 고객센터 채팅에서 AI 가 답할 때 근거로 쓸 **고객 공개 안내 문서** 초안입니다. 2026-11-09 판(`docs/business/customer-faq/`) 뒤에 정해진 회원·로그인·탈퇴·찜·동의 정책을 담았습니다.

- 문서 버전: 0.1 (**초안 — 사용자 승인 전**)
- 작성: Claude, 2026-11-20 (사용자 "고객앱 정책 관련해서 md 파일을 docs/rag/ragdocs 안에 만들어줘")
- 근거: ADR-0074(가입 휴대폰 확인), ADR-0077(탈퇴), ADR-0078(네이버 로그인), ADR-0081(찜), BR-72(가입 다시 시작), 개인정보 처리방침 2026-11-20판(`services/customer-service/src/main/resources/consent-terms/PRIVACY_POLICY/2026-11-20.md`)
- 작성 규칙(사용자 2026-11-20 "약관 문서처럼"): 앱 약관 문서(`services/customer-service/src/main/resources/consent-terms/`)와 같은 형식 — 제목·시행일·제N조(제목)·번호 항목, "회사"·"회원" 표현, ~합니다 체. 내부 처리·내부 용어는 넣지 않고 지위 태그도 달지 않는다.

## 문서 목록

| 파일 | 주제 |
|---|---|
| [01-signup-login.md](01-signup-login.md) | 가입·로그인(구글·카카오·네이버), 약관 동의, 휴대폰 번호 확인, 한 번호 한 계정 |
| [02-withdrawal.md](02-withdrawal.md) | 회원 탈퇴, 탈퇴 처리 대기, 철회, 재가입 |
| [03-wishlist.md](03-wishlist.md) | 찜 |
| [04-consents-privacy.md](04-consents-privacy.md) | 약관·개인정보 처리방침 재동의, 마케팅 수신, 개인정보 보관 기간, 프로필 사진 |

## 색인에 넣으려면 (아직 안 함)
1. 사용자 승인.
2. `services/customer-assistant-service/src/main/resources/knowledge/manifest.yml` 에 파일 경로 추가(지금 색인 대상은 `docs/business/customer-faq/*` 만).
3. 겹치는 옛 내용 정리 — **`docs/business/customer-faq/12-account.md` 가 낡음**("Google 로그인만", "탈퇴는 고객센터 문의") — 이 문서와 맞게 고치거나 manifest 에서 빼기.
4. `deploy/lan/lan.sh render` → `lan.sh rag-ingest-customer pc2`(customer-assistant-service 재색인).

## 넣지 않은 것
- 앱 화면이 아직 없는 기능의 화면 위치(탈퇴·찜 화면은 고객 앱 반영 뒤 문구를 맞춘다).
- 로그아웃 뒤 "지금 주소로 계속 둘러보기"(서버 작업 중, 2026-11-20).
- 네이버 앱 "개발 중" 테스터 제한(내부 시험 사정 — 고객 안내 아님).

## LAN 과 다른 값 (문서는 운영 기준값)
- 탈퇴 뒤 같은 휴대폰 번호 재가입 막기·확인 번호 보관: 문서 30일, **LAN 5분**(사용자 2026-11-20 탈퇴 시험 — pmart-config `customer-service-lan.yml` identity.rejoin-block PT5M).
- 탈퇴·찜·로그아웃 뒤 주소 이어 보기는 서버만 있고 고객 앱 화면은 아직(2026-11-20).
