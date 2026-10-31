// customer-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.customer")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    // 프로필 사진 사본 저장소: S3 호환(로컬·LAN SeaweedFS). 동기 클라이언트만 쓰므로 netty 는 뺀다(ADR-0016·0024)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.wiremock.spring.boot) // delivery-serviceability 판정·Google 사진 응답 고정
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox·프로필 행 확인
    "integrationTestImplementation"(libs.bundles.service.kafka) // 시험에서 CustomerAuthenticated 를 토픽에 흘림
    "integrationTestImplementation"(platform(libs.aws.sdk.bom))
    "integrationTestImplementation"(libs.aws.sdk.s3) { // 시험에서 프로필 사진 사본 확인
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
}
