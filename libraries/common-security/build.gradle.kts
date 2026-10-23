// common-security: JWT 리소스 서버 기본 SecurityFilterChain, 주체(PmartPrincipal) 추출, 서비스 간 호출 토큰·헤더 전파,
// auth-service 행위 시점 검증 클라이언트(fail-closed). 업무 규칙 금지. Spring MVC 서비스 전용.
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.oauth2.resource.server)
    api(libs.spring.boot.starter.restclient)
    api(libs.jackson.databind)
    compileOnly(libs.spring.boot.starter.webmvc)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.restclient.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}
