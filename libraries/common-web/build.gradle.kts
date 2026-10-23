// common-web: HTTP 요청·응답 공통 규약 (헤더, CommandResponse, RFC 9457 ProblemDetail, PolicyValue, Asia/Seoul 기한 계산)
// 업무 규칙 금지. Spring MVC(서블릿) 서비스 전용 — WebFlux(api-gateway)는 의존하지 않는다.
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.validation)
    implementation(libs.jackson.databind)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
}
