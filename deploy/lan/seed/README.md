# 시험 데이터 seed (LAN·로컬)

승인 경로 API 로만 적재한다(ADR-0014, DB 직접 입력 없음). JDK 21 만 있으면 된다.

```bash
java deploy/lan/seed/Seed.java                                  # LAN: 게이트웨이 = placement.conf 의 api-gateway PC :8080
PMART_SEED_ADMIN_PASSWORD=pmart-local java deploy/lan/seed/Seed.java --gateway http://localhost:8080   # 로컬
```

- 관리자 아이디·비밀번호: `deploy/lan/.secrets/operator-admin-login`·`operator-admin-password`(git 제외) 또는 환경변수 `PMART_SEED_ADMIN_LOGIN`·`PMART_SEED_ADMIN_PASSWORD`.
- 시험 운영자 `seed-md`(MD, 센터 `MFC-SEED-01`)·`seed-approver`(PRODUCT·DISPLAY·PRICE·SERVICEABILITY 승인)를 만들거나 맞춘다. 비밀번호는 실행마다 무작위로 초기화하고 저장하지 않는다(발의자 ≠ 승인자 규칙 때문에 두 계정).
- 순서: 카테고리 트리 → 배송 권역 → 상품 → 콘텐츠·대표 이미지(합성 PNG) → 기본가 → MFC 취급 ON. 이미 있으면 건너뛴다(상품은 바코드로 확인). 다시 돌려도 안전하다.
- 다른 카테고리 트리나 권역 매핑이 이미 발효 중이면 덮어쓰지 않는다(트리 발효본은 그대로 쓰고, 권역은 넣지 않는다).
- 데이터: `seed-data.json`. 모두 합성(상품명 `[시험] …`). `local-reference/` 캡처 자료는 쓰지 않는다.
