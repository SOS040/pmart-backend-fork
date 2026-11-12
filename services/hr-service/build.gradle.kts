// hr-service: 직원 등록·인사 정보·계정 발급(사서함 → Keycloak 계정 → 비밀번호 설정 메일), 직원 사건 발행(ADR-0056 결정 5·7·8·10·12·16).
// docs/architecture/service-map.md 참조. Keycloak 은 서비스 계정 클라이언트 hr-provisioner(manage/view/query-users 만)로 Admin API 를 부른다.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.hr")
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
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox·진행 상태 행 확인
    "integrationTestImplementation"(libs.wiremock.spring.boot) // mail-provisioner 흉내
}
