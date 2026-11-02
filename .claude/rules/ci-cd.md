---
paths:
  - "Jenkinsfile"
  - "deploy/**"
  - "build-logic/src/main/kotlin/pmart.container-image.gradle.kts"
---

# CI/CD 규칙

## CI (Jenkins)
1. GitHub Webhook → Jenkins
2. 정확한 commit SHA checkout·빌드
3. 단위·통합 테스트
4. 테스트 직후 Slack에 결과 공지 (Slack 공지 범위는 CI까지)
5. 성공 시 SonarQube 분석·Quality Gate, 실패 시 중단
6. 승인된 main 빌드만 이미지 생성 → Docker Hub 공개 저장소(ADR-0012). 이미지는 **Jib**으로 만든다: `./gradlew :services:<name>:jib -PcontainerRegistry=docker.io/<계정> -PimageTag=<git-sha> -PgitRevision=<git-sha>` → `docker.io/<계정>/pmart-<name>:<git-sha>`. Dockerfile·Docker 데몬을 쓰지 않는다.

## CD
1. 검증된 이미지 digest 선택
2. Kubernetes Staging 배포 (Helm)
3. Readiness·Smoke·E2E 검증
4. 릴리스 승인
5. 동일 digest로 Production 배포
6. 상태 확인, 필요 시 이전 검증 이미지로 롤백

## 컨테이너 이미지 (Jib)
- 설정은 convention plugin `pmart.container-image` 한 곳. 서비스 build.gradle.kts에서는 `jib.container.mainClass` 지정 정도만 허용.
- 베이스 이미지는 digest 고정(`eclipse-temurin:21-jdk@sha256:…`, ADR-0028 — 개발 단계: Exec 탭 셸·JVM 진단 도구. 운영은 GraalVM Native 로 따로 정한다). 갱신은 PR로, 태그만 바꾸지 않는다. 실행 사용자는 비루트 `ubuntu`(1000).
- 태그는 git SHA 하나. `latest` 태그를 만들지 않는다. 배포는 push 결과의 digest로 한다.
- 레지스트리 인증은 `REGISTRY_USERNAME`/`REGISTRY_PASSWORD` 환경변수(Jenkins credentials)로만. 저장소·이미지·로그에 넣지 않는다. 비밀번호 대신 Docker Hub 액세스 토큰(push용 읽기·쓰기, 실행 PC용 읽기 전용).
  - 예외: 빌드 PC(PC 1)는 환경변수 대신 `docker login`(Windows 자격 증명 관리자, `credsStore: desktop`)을 쓴다. 환경변수가 없으면 Jib 이 Docker 설정의 자격 증명 도우미를 읽는다(ADR-0012).
- 이미지는 **공개**된다. 비밀정보·실데이터를 넣지 않고, 운영 설정값은 기본값 없는 환경변수(`${PMART_...}`)로만 받는다. `application-local.yml`의 로컬 고정값도 이미지에 포함되므로 로컬 밖에서 local 프로파일을 쓰지 않는다.
- `jib*` 태스크는 Gradle configuration cache와 호환되지 않으므로 CI의 이미지 단계는 다른 단계와 분리해 실행한다.
- 로컬 확인은 `jibBuildTar`(데몬 불필요) 또는 `jibDockerBuild`(Docker 데몬).

## 원칙
- 테스트 실패와 Quality Gate 실패를 구분해 보고한다.
- 신뢰하지 않는 PR 작업에 운영·이미지 발행 자격증명을 주지 않는다.
- 서비스마다 별도 이미지(Jib 태스크도 서비스 프로젝트 단위). 변경 서비스와 영향받는 라이브러리 소비자만 검증·배포한다.
- 컨테이너 롤백이 DB 마이그레이션을 되돌리지 않는다.
- 명시적 요청 없이 원격 배포, 운영 DB 변경, Slack 발송을 하지 않는다.
