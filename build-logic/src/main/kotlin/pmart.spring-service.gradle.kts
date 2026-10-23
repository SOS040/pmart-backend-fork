// services/* · platform-services/* 용: 독립 Spring Boot 앱 (bootJar). 공통 의존성 번들, Boot/Cloud BOM, 계약 검증.

import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()

plugins {
    java
    id("org.springframework.boot")
    id("pmart.java-conventions")
    id("pmart.contract-check")
    id("pmart.container-image")
    id("pmart.openapi-server")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    annotationProcessor(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))
    "integrationTestImplementation"(platform(libs.spring.boot.bom))

    // 모든 서비스 공통 최소 집합. 데이터·Kafka·관측은 각 서비스 build.gradle.kts에서 bundle로 추가한다.
    implementation(libs.bundles.service.core)
    implementation(libs.springdoc.openapi.webmvc.ui) // 생성 코드의 @Operation 등 + /v3/api-docs
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    "integrationTestImplementation"(libs.bundles.test.integration)
    // integrationTest 는 project() 의 런타임 산출물에만 의존하므로 컴파일에 필요한 starter 를 다시 선언한다
    "integrationTestImplementation"(libs.bundles.service.core)
}

springBoot {
    buildInfo() // /actuator/info 에 빌드 정보
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
    layered { enabled.set(true) } // 컨테이너 레이어 캐시
}

// 일반 jar(-plain.jar)는 유지한다: integrationTest 스위트가 project() 의존으로 main 클래스를 받는 경로이기 때문. 배포 산출물은 bootJar만 쓴다.

// integrationTest는 bootJar 산출물이 아니라 클래스에 의존한다 (JVM Test Suite 기본).
