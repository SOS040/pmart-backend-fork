// order-orchestration-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.order.orchestration")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.wiremock.spring.boot) // 참여 정본(재고·여력·슬롯·결제)·auth 서비스 토큰 응답 고정
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 S1 확정 사가(점유) 행 준비
    "integrationTestImplementation"(libs.bundles.service.kafka) // 시험에서 CancellationRequested 를 토픽에 흘림
}
