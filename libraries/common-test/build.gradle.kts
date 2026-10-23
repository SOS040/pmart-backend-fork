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

    testImplementation(libs.spring.boot.starter.test)
}
