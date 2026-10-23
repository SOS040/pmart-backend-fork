// api-gateway: Spring Cloud Gateway Server WebFlux + Reactor Netty. 라우팅·JWT 검증·traceId 발급·rate limit 만, 업무 로직 없음.
// 규칙: docs/architecture/service-map.md 의 api-gateway 절, .claude/rules/security-config.md
plugins {
    id("pmart.spring-gateway")
}

dependencies {
    implementation(project(":libraries:common-observability")) // 로그 JSON·추적 기본값(서블릿 필터는 WebFlux 에서 비활성)
    implementation(libs.bundles.service.observability)
    implementation(libs.spring.boot.starter.data.redis.reactive) // RedisRateLimiter (토큰 버킷 상태)

    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.wiremock.spring.boot)

    "integrationTestImplementation"(libs.spring.boot.starter.oauth2.resource.server) // mockJwt() 가 Jwt 타입을 컴파일 시점에 참조
    "integrationTestImplementation"(libs.spring.boot.starter.webflux.test)
    "integrationTestImplementation"(libs.spring.boot.starter.security.test)
    "integrationTestImplementation"(libs.wiremock.spring.boot)
}

jib {
    container {
        mainClass = "com.pmart.gateway.ApiGatewayApplication"
    }
}
