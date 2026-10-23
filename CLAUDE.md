# CLAUDE.md — PMart Backend

Spring Boot 마이크로서비스 / Gradle Multi-Project (Kotlin DSL) / Java 21

## 구조
```
services/           # 업무 마이크로서비스 (독립 Spring Boot 앱, bootJar)
platform-services/  # api-gateway 등 플랫폼 앱
libraries/          # 공통 기술 라이브러리 (jar, 업무 로직 금지)
contracts/          # openapi/, events/ — 서비스 간 계약의 정본
build-logic/        # convention plugin (included build)
gradle/libs.versions.toml  # 의존성 좌표 중앙 관리
deploy/helm/, Jenkinsfile
```
서비스 내부: `com.pmart.<domain>.{api, application, domain, infrastructure}`

## 상세 규칙 (작업 영역에 따라 자동 로드)
- 빌드/의존성: `.claude/rules/gradle-build.md`
- 서비스 구조·데이터·트랜잭션: `.claude/rules/service-architecture.md`
- API·Kafka 이벤트: `.claude/rules/api-and-events.md`
- 테스트: `.claude/rules/testing.md`
- 보안·설정: `.claude/rules/security-config.md`
- CI/CD: `.claude/rules/ci-cd.md`
- 기술 스택 전체 표: `docs/architecture/tech-stack.md` (필요 시 참조)

## Commands
```bash
./gradlew :services:<name>:test
./gradlew :services:<name>:integrationTest
./gradlew :services:<name>:check
./gradlew :services:<name>:bootJar
```
Windows는 `.\gradlew.bat`. 실행 전 프로젝트 경로와 task 존재 여부를 먼저 확인한다.

## 절대 규칙
- 다른 서비스의 구현 클래스·DB·JPA 엔티티에 직접 접근하지 않는다. API 또는 이벤트만.
- 요청 없이 서비스 경계 병합, 새 프레임워크 도입, 프레임워크 주 버전 변경 금지.
- Maven 등 별도 빌드 체계 추가 금지. 새 의존성은 `libs.versions.toml`에 등록.
- 비밀정보를 Git·`application.yml`·이미지에 넣지 않는다. 개인정보·토큰은 로그 금지.
- 명시적 요청 없이 원격 배포·운영 DB 변경·Slack 발송 금지.
- 실행하지 않은 테스트나 배포를 완료했다고 보고하지 않는다.
- `libraries/` 변경 시 영향받는 서비스를 확인하고 보고한다.
