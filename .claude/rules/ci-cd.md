---
paths:
  - "Jenkinsfile"
  - "deploy/**"
  - "**/Dockerfile"
---

# CI/CD 규칙

## CI (Jenkins)
1. GitHub Webhook → Jenkins
2. 정확한 commit SHA checkout·빌드
3. 단위·통합 테스트
4. 테스트 직후 Slack에 결과 공지 (Slack 공지 범위는 CI까지)
5. 성공 시 SonarQube 분석·Quality Gate, 실패 시 중단
6. 승인된 main 빌드만 이미지 생성 → Private Registry

## CD
1. 검증된 이미지 digest 선택
2. Kubernetes Staging 배포 (Helm)
3. Readiness·Smoke·E2E 검증
4. 릴리스 승인
5. 동일 digest로 Production 배포
6. 상태 확인, 필요 시 이전 검증 이미지로 롤백

## 원칙
- 테스트 실패와 Quality Gate 실패를 구분해 보고한다.
- 신뢰하지 않는 PR 작업에 운영·이미지 발행 자격증명을 주지 않는다.
- 서비스마다 별도 이미지. 변경 서비스와 영향받는 라이브러리 소비자만 검증·배포한다.
- 컨테이너 롤백이 DB 마이그레이션을 되돌리지 않는다.
- 명시적 요청 없이 원격 배포, 운영 DB 변경, Slack 발송을 하지 않는다.
