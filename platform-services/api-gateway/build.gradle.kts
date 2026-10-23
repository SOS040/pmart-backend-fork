// api-gateway: Spring Cloud Gateway Server WebFlux + Reactor Netty. 라우팅·JWT 검증·traceId 발급만, 업무 로직 없음.
// 규칙: docs/architecture/service-map.md 의 api-gateway 절, .claude/rules/security-config.md
plugins {
    id("pmart.spring-gateway")
}

dependencies {
    implementation(project(":libraries:common-observability")) // 로그 JSON·추적 기본값(서블릿 필터는 WebFlux 에서 비활성)
    implementation(libs.bundles.service.observability)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.wiremock.spring.boot)
}

jib {
    container {
        mainClass = "com.pmart.gateway.ApiGatewayApplication"
    }
}
