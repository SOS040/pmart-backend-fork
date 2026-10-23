// 모든 JVM 서브프로젝트 공통: Java 21 toolchain, 테스트(JUnit Jupiter), JaCoCo, Spotless, 컴파일 옵션.
// 라이브러리·서비스 플러그인이 이 플러그인을 적용한다. 직접 적용하지 않는다.

import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
}

group = "com.pmart"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(libs.versions.java.get().toInt())
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

// ---- 테스트: JVM Test Suite. test(단위) + integrationTest(통합, Testcontainers) ----
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation.bundle(libs.bundles.test.unit)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation.bundle(libs.bundles.test.unit)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    // Testcontainers는 Docker가 필요. CI/로컬에서 Docker가 없으면 -PskipIntegration=true 로 건너뛴다.
                    val skipIntegration = providers.gradleProperty("skipIntegration").map { it.toBoolean() }.orElse(false)
                    onlyIf("skipIntegration 속성이 false") { !skipIntegration.get() }
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.timezone", "UTC") // ADR-0010: 저장·전송은 UTC. 기한 판정 테스트는 명시적으로 Asia/Seoul 사용
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

// ---- JaCoCo: 단위 테스트 커버리지 XML (SonarQube가 읽음) ----
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
}
tasks.named("check") { dependsOn(tasks.jacocoTestReport) }

// ---- Spotless: Java·Gradle 파일 형식 ----
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---- 의존성 금지 규칙 (.claude/rules/gradle-build.md "프로젝트 의존성") ----
// 서비스/플랫폼 앱 → 다른 서비스/플랫폼 앱 프로젝트 의존 금지. 라이브러리(:libraries:*)는 허용.
val selfPath = project.path
val selfIsService = selfPath.startsWith(":services:") || selfPath.startsWith(":platform-services:")
if (selfIsService) {
    configurations.configureEach {
        allDependencies.withType<ProjectDependency>().configureEach {
            val target = path
            if (target != selfPath && (target.startsWith(":services:") || target.startsWith(":platform-services:"))) {
                throw GradleException(
                    "$selfPath 가 다른 서비스 프로젝트 $target 에 의존합니다. 서비스 간 결합은 API 또는 이벤트로만 (CLAUDE.md 절대 규칙)."
                )
            }
        }
    }
}
