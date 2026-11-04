// ai-assistant-service: 내부 운영자용 정책·업무 절차 질의응답(RAG, 1차 — ADR-0036). 저장소 문서의 사본 색인과 답변 기록만 소유한다.
// Spring AI·pgvector 는 이 서비스에만(.claude/rules/gradle-build.md). 외부 모델 제공자 모듈은 결정 전이라 넣지 않는다.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.assistant")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.model)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(platform(libs.spring.ai.bom))
    "integrationTestImplementation"(libs.spring.ai.model)
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc)
}

// 통제된 색인·정리 작업(ADR-0036). 평소 bootRun·컨테이너 기동에서는 색인하지 않는다.
// 앱을 local 프로필로 띄워 한 번 실행하고 종료한다 — 먼저 config-server(local)와 deploy/local/ai-assistant compose 를 띄운다.
//   ./gradlew :services:ai-assistant-service:ingestKnowledge -Prepo=<저장소 경로>
//   ./gradlew :services:ai-assistant-service:pruneSnapshots
val assistantMain = "com.pmart.assistant.AiAssistantServiceApplication"
tasks.register<JavaExec>("ingestKnowledge") {
    group = "pmart"
    description = "manifest 문서를 색인해 새 스냅숏을 활성화한다 (-Prepo=<저장소 경로>)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(assistantMain)
    val repo = providers.gradleProperty("repo")
    val profile = providers.gradleProperty("profile").orElse("local")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--spring.profiles.active=${profile.get()}",
            "--spring.main.web-application-type=none",
            "--pmart.assistant.ingest.run=true",
            "--pmart.assistant.ingest.repo=${repo.orNull ?: throw GradleException("-Prepo 가 필요하다")}",
        )
    })
}
tasks.register<JavaExec>("pruneSnapshots") {
    group = "pmart"
    description = "유예가 지난 RETIRED·FAILED 스냅숏 중 처리 중 요청이 고정하지 않은 것만 지운다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(assistantMain)
    val profile = providers.gradleProperty("profile").orElse("local")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--spring.profiles.active=${profile.get()}",
            "--spring.main.web-application-type=none",
            "--pmart.assistant.ingest.prune=true",
        )
    })
}
