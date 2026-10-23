// 루트 프로젝트: 집계·품질 검증 전용. 업무 코드 없음 (.claude/rules/gradle-build.md).
// 공통 빌드 규칙은 build-logic의 convention plugin에 있다. allprojects/subprojects 일괄 설정은 쓰지 않는다.

plugins {
    base
    alias(libs.plugins.sonarqube)
}

group = "com.pmart"
description = "PMart quick-commerce backend"

// SonarQube: CI에서만 실행 (.claude/rules/testing.md). 서버 주소·토큰은 CI 환경변수(SONAR_HOST_URL, SONAR_TOKEN)로 주입.
sonar {
    properties {
        property("sonar.projectKey", "pmart-backend")
        property("sonar.projectName", "PMart Backend")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.gradle.skipCompile", "true")
    }
}

// 전체 테스트 커버리지 보고서 경로 집계는 각 서브프로젝트의 jacocoTestReport(xml)를 Sonar가 읽는다.
// (sonar.coverage.jacoco.xmlReportPaths 는 convention plugin에서 서브프로젝트별로 설정)
