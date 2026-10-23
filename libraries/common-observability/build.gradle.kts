// common-observability: 구조화 JSON 로그(traceId·correlationId), Micrometer Tracing + OpenTelemetry + Zipkin, Prometheus 메트릭,
// Actuator 기본값, correlationId 전파 필터. 업무 규칙 금지.
plugins {
    id("pmart.java-library")
}

dependencies {
    api(libs.spring.boot.starter.actuator)
    api(libs.micrometer.registry.prometheus)
    api(libs.micrometer.tracing.bridge.otel)
    api(libs.opentelemetry.exporter.zipkin)
    api(libs.logstash.logback.encoder)

    // 서블릿 필터는 MVC 서비스에서만 활성화. 라이브러리 자체는 MVC를 강제하지 않는다.
    compileOnly(libs.spring.boot.starter.webmvc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc)
}
