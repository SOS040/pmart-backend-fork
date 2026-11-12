// employee-auth-service: 직원 로그인(Keycloak 기밀 클라이언트 — 인가 코드 + PKCE)·PMart 직원 토큰 발급자·직원 역할·권한 센터 정본(ADR-0056).
// docs/architecture/service-map.md 참조. 아직 Kafka 를 쓰지 않는다(Employee* 구독·SubjectBlocked 발행은 ADR-0056 5단계).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.employeeauth")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-security")) // 시험이 JWT·Nimbus 를 직접 쓴다
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 DB 행 확인
}
