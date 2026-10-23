---
paths:
  - "**/security/**"
  - "**/config/**"
  - "**/application*.yml"
  - "**/application*.yaml"
  - "libraries/common-security/**"
  - "platform-services/api-gateway/**"
---

# 보안·설정 규칙

## 인증·인가
- Spring Security + OAuth2/OIDC.
- Gateway뿐 아니라 각 서비스에서도 토큰·권한을 검증한다.
- 사용자 역할, 센터 접근 권한, 서비스 호출 권한을 구분한다.
- 관리 API와 Actuator 노출 범위를 제한한다.

## 비밀정보·로그
- 비밀정보를 Git, `application.yml`, 컨테이너 이미지에 저장하지 않는다 (Vault 또는 Cloud Secret Manager).
- 개인정보, 인증 토큰, PG 자격증명을 로그에 남기지 않는다.

## 설정·서비스 검색
- Eureka 환경: Spring Cloud Config + Eureka
- Kubernetes 환경: Service DNS + ConfigMap + Secret Manager
- 두 체계를 이유 없이 중복 도입하지 않는다. 현재 환경의 방식을 먼저 확인한다.
