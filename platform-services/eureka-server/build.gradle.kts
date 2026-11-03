// eureka-server: Spring Cloud Netflix Eureka 서버(ADR-0032). local·LAN 의 서비스 등록·조회. 운영(Kubernetes)은 Service DNS 를 쓰고 이것을 띄우지 않는다.
// - 서버 자신은 등록하지 않는다(단일 노드). 등록·조회는 Basic 인증(사용자 pmart, 비밀번호 PMART_EUREKA_PASSWORD).
// - 업무 로직·업무 API 없음 → OpenAPI 생성·계약 검증을 끈다. 설정은 다른 앱처럼 config-server 에서 받는다(ADR-0029).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    enabled.set(false)
}

tasks.named<com.pmart.buildlogic.ContractCheckTask>("contractCheck") {
    required.set(false)
}

dependencies {
    implementation(libs.spring.cloud.starter.eureka.server)
    implementation(libs.spring.boot.starter.security) // 등록·조회 Basic 인증
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)

    "integrationTestImplementation"(platform(libs.spring.cloud.bom))
}

jib {
    container {
        mainClass = "com.pmart.eureka.EurekaServerApplication"
    }
}
