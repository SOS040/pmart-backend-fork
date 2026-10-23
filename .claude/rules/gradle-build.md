---
paths:
  - "**/*.gradle.kts"
  - "gradle/**"
  - "build-logic/**"
  - "gradle.properties"
---

# Gradle 빌드 규칙

## 버전 관리
- Gradle은 Wrapper, Java는 Toolchain으로 고정.
- 라이브러리·플러그인 좌표는 `gradle/libs.versions.toml`에서만 관리. 서브프로젝트에 버전 중복 선언 금지.
- 버전 정렬은 Spring Boot BOM(+ Spring Cloud Release Train BOM)으로 보장. 카탈로그는 선언 중앙화 용도.
- 동적 버전, 운영용 SNAPSHOT 금지.
- 버전의 정본은 빌드 파일. 문서에 버전을 적지 않는다.

## 멀티 프로젝트 구성
- 루트 `settings.gradle.kts`에서 서브프로젝트를 명시적으로 등록.
- 공통 빌드 규칙은 `build-logic`의 convention plugin으로. 루트 `allprojects`/`subprojects` 일괄 설정은 피한다.
- 실행 서비스는 `bootJar`, 라이브러리는 일반 `jar`. 라이브러리에 Spring Boot 진입점 금지.
- 루트 프로젝트는 집계·품질 검증 전용. 업무 코드 없음.
- 모든 서비스에 모든 의존성을 추가하지 않는다. 검색(OpenSearch)·AI(Spring AI, pgvector)는 해당 서비스에만.

## 프로젝트 의존성
허용: 서비스 → 공통 기술 라이브러리 / 버전 관리된 계약, 테스트 → common-test

금지:
- 서비스 → 다른 서비스 구현 프로젝트 (예: order → payment)
- mfc 서비스 → CDC 저장소 클래스
- 서비스 간 JPA 엔티티·Repository 공유
- 공통 라이브러리에 주문·재고·가격 업무 규칙
- 전역 DTO 라이브러리로 모든 서비스 결합

## 작업 시
- 기존 convention plugin을 먼저 읽고 따른다.
- 기존 Maven 프로젝트 전환은 명시적으로 요청된 범위에서만.
