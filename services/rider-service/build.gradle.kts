// rider-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.rider")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    // 프로필 사진 사본 저장소(2026-11-25 — 고객 customer-service 와 같게): S3 호환(LAN SeaweedFS). 동기 클라이언트만 쓰므로 netty 는 뺀다
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.wiremock.spring.boot) // audit-service 개인정보 열람 기록 응답 고정(0.4.0)
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox 행 확인
}
