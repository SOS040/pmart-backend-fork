// product-content-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.product.content")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    // 이미지 저장소: S3 호환(로컬·LAN SeaweedFS). 동기 클라이언트만 쓰므로 netty 는 뺀다(ADR-0016)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox·이미지 행 확인
    "integrationTestImplementation"(libs.bundles.service.kafka) // 시험에서 ProductVersionPublished·ApprovalDecided 를 토픽에 흘림
    "integrationTestImplementation"(libs.wiremock.spring.boot) // mfc-inventory 가용·auth 서비스 토큰 응답 고정
    "integrationTestImplementation"(platform(libs.aws.sdk.bom))
    "integrationTestImplementation"(libs.aws.sdk.s3) { // 시험에서 버킷 내용 확인
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
}
