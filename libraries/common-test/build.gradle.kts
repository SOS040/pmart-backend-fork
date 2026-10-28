// common-test: Testcontainers(PostgreSQL 논리복제·Kafka) 베이스, WireMock, 이벤트 봉투 픽스처, Asia/Seoul 고정 시계.
// 서비스의 testImplementation / integrationTestImplementation 에만 들어간다. 업무 규칙 금지.
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.bundles.test.integration)
    api(libs.bundles.test.unit)
    api(libs.wiremock.spring.boot)
    api(libs.spring.boot.starter.test)
    api(project(":libraries:common-messaging"))
    implementation(libs.jackson.databind)
    // TestJwt: 서비스는 common-security 로 resource server(nimbus)를 이미 가진다
    compileOnly(libs.spring.boot.starter.oauth2.resource.server)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.oauth2.resource.server)
}

dependencies {
    // 베이스 클래스 자체 검증용(@ServiceConnection 주입): 서비스에는 영향 없음
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc)
    "integrationTestImplementation"(libs.spring.boot.starter.kafka)
    "integrationTestImplementation"(libs.postgresql)
}
