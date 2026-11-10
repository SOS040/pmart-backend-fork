// common-jwt: 믿는 발급자 표와 발급자 ↔ 사용자 종류 짝 규칙(ADR-0054). 서블릿·WebFlux 어느 쪽에도 묶이지 않는다 —
// common-security(서비스, 서블릿)와 api-gateway(WebFlux)가 같은 규칙을 쓴다. 업무 규칙 금지.
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.spring.boot.starter.oauth2.resource.server)

    testImplementation(libs.spring.boot.starter.test)
}
