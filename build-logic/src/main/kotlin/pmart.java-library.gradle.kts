// libraries/* 용: 일반 jar. Spring Boot 진입점·업무 규칙 금지 (.claude/rules/gradle-build.md).

import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()

plugins {
    `java-library`
    id("pmart.java-conventions")
}

dependencies {
    // 버전 정렬: Boot BOM. 라이브러리는 BOM을 api로 노출하지 않고 자기 컴파일에만 쓴다.
    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))
    "integrationTestImplementation"(platform(libs.spring.boot.bom))
    compileOnly(libs.jspecify)
}

java {
    withSourcesJar()
}

// 라이브러리에는 bootJar/bootRun이 없다. 실수로 Boot 플러그인을 적용하면 실패시킨다.
plugins.withId("org.springframework.boot") {
    throw GradleException("${project.path}: 라이브러리에 Spring Boot 플러그인을 적용할 수 없습니다 (pmart.java-library).")
}
