// PMart Backend — Gradle Multi-Project (Kotlin DSL)
// 규칙: .claude/rules/gradle-build.md. 서브프로젝트는 아래에 명시적으로 등록한다.

rootProject.name = "pmart-backend"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Java Toolchain 자동 프로비저닝 (JDK가 없을 때만 다운로드). 로컬 JDK 21이 있으면 그것을 쓴다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Confluent Schema Registry 클라이언트·JSON Schema serializer (common-messaging에서만 사용)
        maven {
            url = uri("https://packages.confluent.io/maven/")
            content { includeGroupByRegex("io\\.confluent.*") }
        }
    }
    // versionCatalogs: gradle/libs.versions.toml 이 기본 'libs' 카탈로그로 자동 등록된다.
}

// ---------------------------------------------------------------------------
// 서브프로젝트 등록. 디렉토리 규칙: services/{name}, platform-services/{name}, libraries/{name}
// 새 서비스는 해당 목록에 한 줄 추가. 와일드카드 자동 등록은 쓰지 않는다(명시성 우선).
// ---------------------------------------------------------------------------

val libraries = listOf<String>(
    "common-web",
    "common-observability",
    // 2단계에서 추가: "common-web", "common-messaging", "common-security", "common-observability", "common-test"
)

val platformServices = listOf<String>(
    // "api-gateway"
)

val services = listOf<String>(
    // 3단계에서 추가. 1단계 서비스 목록은 docs/architecture/architecture-design.md §8
)

libraries.forEach { name ->
    include(":libraries:$name")
    project(":libraries:$name").projectDir = file("libraries/$name")
}
platformServices.forEach { name ->
    include(":platform-services:$name")
    project(":platform-services:$name").projectDir = file("platform-services/$name")
}
services.forEach { name ->
    include(":services:$name")
    project(":services:$name").projectDir = file("services/$name")
}
