// common-messaging: 이벤트 봉투, Outbox(Debezium이 발행) / Inbox(멱등 소비), Kafka 기본 설정·DLT, contracts/events JSON Schema 검증.
// 업무 규칙 금지. 발행 릴레이는 애플리케이션에 없다 — Debezium PostgreSQL 커넥터 + Outbox Event Router (deploy/local 참조).
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.spring.boot.starter.kafka)
    api(libs.spring.kafka)
    api(libs.jackson.databind)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.json.schema.validator)
    // Flyway 위치 추가(FlywayConfigurationCustomizer)는 서비스가 Flyway starter를 쓸 때만 활성화
    compileOnly(libs.spring.boot.starter.flyway)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)

    "integrationTestImplementation"(libs.bundles.test.integration)
    "integrationTestImplementation"(libs.spring.boot.starter.flyway)
    "integrationTestImplementation"(libs.flyway.postgresql)
    "integrationTestImplementation"(libs.postgresql)
}

// contracts/events 의 JSON Schema 를 jar 에 포함해 런타임 검증에 쓴다 (정본은 contracts/, 여기는 사본).
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/events")) {
        into("pmart-events")
        include("*.json")
    }
}
