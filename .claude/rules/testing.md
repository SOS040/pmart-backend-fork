---
paths:
  - "**/src/test/**"
  - "**/src/integrationTest/**"
  - "libraries/common-test/**"
---

# 테스트 규칙

## Gradle task
- `test`: 단위 테스트
- `integrationTest`: 통합 테스트. Gradle 기본 task가 아니며 convention plugin에서 정의 (source set 또는 JVM Test Suite)
- `check`: 단위·통합·형식 검사 연결
- `sonar`: CI에서만 실행 (서버 접근·자격증명은 CI 관리)

## 작성 기준
- 업무 규칙: JUnit Jupiter + Mockito + AssertJ 단위 테스트
- 통합: Spring Boot Test + Testcontainers (PostgreSQL, Kafka 등 실제 기술)
- PostgreSQL 동작을 H2 통과만으로 검증하지 않는다.
- 외부 시스템(PG 등)은 WireMock으로 응답·오류·지연을 재현하고 격리.
- 형식은 Spotless 준수.

## 실행·보고
- 변경에 맞는 테스트를 실행한다. 예: `./gradlew :services:order-service:check`
- 실행 전 프로젝트 경로와 task 존재 여부를 확인한다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.
- 테스트를 삭제하거나 `@Disabled`로 통과시키지 않는다.
